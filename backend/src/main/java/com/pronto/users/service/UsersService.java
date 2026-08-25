package com.pronto.users.service;

import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.maps.service.ServiceAddressGeocoder;
import com.pronto.common.security.AuthenticatedUser;
import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.professionals.service.ProfessionalCoverageService;
import com.pronto.storage.service.StorageService;
import com.pronto.users.dto.DefaultAddressInfo;
import com.pronto.users.dto.ProfessionalInfo;
import com.pronto.users.dto.UpdateUserMeRequest;
import com.pronto.users.dto.UserMeResponse;
import com.pronto.users.entity.User;
import com.pronto.users.entity.UserRole;
import com.pronto.auth.service.PhoneNumberNormalizer;
import com.pronto.users.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Self-service profile operations: {@code GET}/{@code PUT}/{@code DELETE /api/users/me}.
 * See {@code docs/architecture/api-contract.md} §2.4-2.6.
 */
@Service
public class UsersService {

    private final UserRepository userRepository;
    private final ProfessionalRepository professionalRepository;
    private final StorageService storageService;
    private final ProfessionalCoverageService professionalCoverageService;
    private final PhoneNumberNormalizer phoneNumberNormalizer;
    private final ServiceAddressGeocoder serviceAddressGeocoder;

    public UsersService(UserRepository userRepository, ProfessionalRepository professionalRepository,
                         StorageService storageService,
                         ProfessionalCoverageService professionalCoverageService,
                         PhoneNumberNormalizer phoneNumberNormalizer,
                         ServiceAddressGeocoder serviceAddressGeocoder) {
        this.serviceAddressGeocoder = serviceAddressGeocoder;
        this.userRepository = userRepository;
        this.professionalRepository = professionalRepository;
        this.storageService = storageService;
        this.professionalCoverageService = professionalCoverageService;
        this.phoneNumberNormalizer = phoneNumberNormalizer;
    }

    @Transactional(readOnly = true)
    public UserMeResponse getMe(Long userId) {
        User user = loadActiveUser(userId);

        ProfessionalInfo professionalInfo = null;
        if (user.getRole() == UserRole.PROFESSIONAL) {
            professionalInfo = professionalRepository.findByUserId(user.getId())
                    .map(p -> new ProfessionalInfo(professionalCoverageService.categoryIds(p.getId()),
                            professionalCoverageService.load(p).serviceRegionNameHe(), p.getBasePrice(),
                            p.getProfileImageKey() == null ? null
                                    : storageService.getPresignedUrl(user.getId(), p.getProfileImageKey())))
                    .orElse(null);
        }

        DefaultAddressInfo defaultAddress = null;
        if (user.getRole() == UserRole.CUSTOMER && user.getDefaultCity() != null) {
            defaultAddress = new DefaultAddressInfo(user.getDefaultCity(), user.getDefaultStreet(),
                    user.getDefaultHouseNumber(), user.getDefaultApartment(), user.getDefaultFloor(),
                    user.getDefaultEntrance(), user.getDefaultAddressNotes());
        }

        // Production MS1: no longer blanked for a PROFESSIONAL. The CUSTOMER-only rule was right
        // when this column held customer contact detail; phone is now the account's second identity
        // and every role has one, so hiding it from its own owner would only mean a professional
        // could not see the number they log in with. Still null on a legacy row that has none.
        return new UserMeResponse(user.getId(), user.getFullName(), user.getEmail(),
                user.getRole(), user.isEmailVerified(), professionalInfo, defaultAddress,
                user.getPhone(), user.isPhoneVerified());
    }

    /**
     * {@code PUT /api/users/me} — {@code CUSTOMER} only. Covers §3.2/§4 of the MS10 profile
     * redesign design doc: {@code fullName}/{@code phone}/{@code defaultAddress} become
     * editable, reversing the two previously-"read-only, no endpoint" contract sentences for
     * those fields (see {@code docs/architecture/api-contract.md} §2.4/§2.6 — retroactively
     * editing a saved default address has no correctness impact on any existing/in-flight
     * order, since {@code orders.service_*} is its own snapshot captured at order-creation
     * time, decoupled from {@code users.default_*}).
     *
     * <p>Defense-in-depth {@code 403 FORBIDDEN} if {@code caller}'s role isn't
     * {@code CUSTOMER} — the route-level gate ({@code users.config.UsersWebConfig}) already
     * prevents this in practice; re-checked here as the same belt-and-suspenders convention
     * {@code professionals}/{@code bookings} already use for their own role-restricted
     * writes.
     *
     * <p>Load-mutate-save on a single-owner, non-concurrency-contended row — same pattern
     * {@link #deleteMe} and {@code professionals.service.ProfessionalsService#updateMyProfile}
     * already use. Returns the same shape {@link #getMe} does; no new response DTO.
     */
    @Transactional
    public UserMeResponse updateMe(AuthenticatedUser caller, UpdateUserMeRequest request) {
        if (!UserRole.CUSTOMER.name().equals(caller.role())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "This action requires role CUSTOMER.");
        }

        User user = loadActiveUser(caller.id());
        user.setFullName(request.fullName());
        applyPhoneChange(user, request.phone());

        UpdateUserMeRequest.Address address = request.defaultAddress();
        user.setDefaultCity(address.city());
        user.setDefaultStreet(address.street());
        user.setDefaultHouseNumber(address.houseNumber());
        user.setDefaultApartment(address.apartment());
        user.setDefaultFloor(address.floor());
        user.setDefaultEntrance(address.entrance());
        user.setDefaultAddressNotes(address.addressNotes());

        // Production MS2. An address edit invalidates the coordinates immediately and then
        // re-resolves them, in that order and inside this same transaction.
        //
        // The invalidation is strictly redundant -- ServiceAddressGeocoder compares an address
        // digest and would notice the change by itself -- and it is here anyway, deliberately.
        // It makes the handling visible in the edit path rather than requiring a reader to know
        // about a hash comparison three packages away, and it clears the old coordinates
        // synchronously, so a read that lands between the edit and the re-resolve cannot route to
        // where the customer used to live.
        //
        // Geocoding HERE rather than on the listing read is the point: this is a read-write
        // transaction, so the resolved coordinates actually persist. A read path cannot do it --
        // a listing runs readOnly, where the mutation would be discarded at flush and the geocode
        // paid for again on every single request.
        serviceAddressGeocoder.invalidateCustomerDefault(user);
        serviceAddressGeocoder.resolveCustomerDefault(user, Instant.now());
        userRepository.save(user);

        return getMe(user.getId());
    }

    /**
     * Soft-delete + PII anonymization, per api-contract.md §2.5. Does not touch
     * {@code professionals}/{@code issues}/{@code orders} rows — those are `RESTRICT`-FK
     * business records out of scope for this endpoint (flagged there: Standard/SOS
     * listing queries in later milestones need to join against {@code users.deleted_at
     * IS NULL} to hide a deleted professional's still-existing profile row).
     */
    @Transactional
    public void deleteMe(Long userId) {
        User user = loadActiveUser(userId);
        user.setDeletedAt(Instant.now());
        user.setFullName("Deleted User");
        user.setEmail("deleted-user-" + user.getId() + "@pronto.invalid");
        // Production MS1: release the phone number too. ux_users_phone is a total unique index, so
        // leaving it on the tombstone would reserve that number forever and stop its actual owner
        // from ever registering -- the identical problem the email rewrite above already solves.
        user.setPhone(null);
        user.setPhoneVerified(false);
        userRepository.save(user);
    }

    /**
     * Applies a customer's self-service phone edit.
     *
     * <p>Production MS1 made this more than a field assignment. Phone is now a login identifier, so
     * an edit has to normalize to E.164 (or the value would not match anything and would violate
     * {@code ck_users_phone_e164}), has to check uniqueness (or it would collide at
     * {@code ux_users_phone}), and -- the part that actually matters -- has to <b>drop the verified
     * flag when the number changes</b>. Without that last rule this endpoint would be a complete
     * bypass of phone verification: type any number, keep the verified flag you earned on a
     * different one, and receive login codes at an address nobody proved you own.
     *
     * <p>A no-op edit (same canonical number resubmitted with the rest of the profile) deliberately
     * leaves the flag alone, so saving the profile form does not cost the user their verification.
     */
    private void applyPhoneChange(User user, String submittedPhone) {
        String phone = phoneNumberNormalizer.normalize(submittedPhone, "phone");
        if (phone.equals(user.getPhone())) {
            return;
        }

        userRepository.findByPhone(phone)
                .filter(owner -> !owner.getId().equals(user.getId()))
                .ifPresent(owner -> {
                    throw new ApiException(ErrorCode.DUPLICATE_PHONE,
                            "Phone number is already registered.");
                });

        user.setPhone(phone);
        user.setPhoneVerified(false);
    }

    /**
     * Re-checked here (not just trusted from the JWT filter's own check) as a defensive
     * boundary re-validation against the small race window between token validation and
     * handler execution — e.g. a concurrent delete of the same account between requests.
     */
    private User loadActiveUser(Long userId) {
        return userRepository.findById(userId)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED,
                        "User no longer exists or has been deleted."));
    }
}
