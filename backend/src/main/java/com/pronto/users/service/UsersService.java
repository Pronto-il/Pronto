package com.pronto.users.service;

import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
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

    public UsersService(UserRepository userRepository, ProfessionalRepository professionalRepository,
                         StorageService storageService,
                         ProfessionalCoverageService professionalCoverageService) {
        this.userRepository = userRepository;
        this.professionalRepository = professionalRepository;
        this.storageService = storageService;
        this.professionalCoverageService = professionalCoverageService;
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

        // §9.1 of the professional weekly availability calendar design: same
        // CUSTOMER-only/self-view convention as defaultAddress above -- always null for a
        // PROFESSIONAL (the column itself is always null for that role, but the explicit
        // role check mirrors defaultAddress's own defensive convention rather than relying
        // on the column value alone).
        String phone = user.getRole() == UserRole.CUSTOMER ? user.getPhone() : null;

        return new UserMeResponse(user.getId(), user.getFullName(), user.getEmail(),
                user.getRole(), user.isEmailVerified(), professionalInfo, defaultAddress, phone);
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
        user.setPhone(request.phone());

        UpdateUserMeRequest.Address address = request.defaultAddress();
        user.setDefaultCity(address.city());
        user.setDefaultStreet(address.street());
        user.setDefaultHouseNumber(address.houseNumber());
        user.setDefaultApartment(address.apartment());
        user.setDefaultFloor(address.floor());
        user.setDefaultEntrance(address.entrance());
        user.setDefaultAddressNotes(address.addressNotes());
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
        userRepository.save(user);
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
