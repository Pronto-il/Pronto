package com.pronto.users.service;

import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.users.dto.DefaultAddressInfo;
import com.pronto.users.dto.ProfessionalInfo;
import com.pronto.users.dto.UserMeResponse;
import com.pronto.users.entity.User;
import com.pronto.users.entity.UserRole;
import com.pronto.users.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Self-service profile operations: {@code GET}/{@code DELETE /api/users/me}. See
 * {@code docs/architecture/api-contract.md} §2.4-2.5.
 */
@Service
public class UsersService {

    private final UserRepository userRepository;
    private final ProfessionalRepository professionalRepository;

    public UsersService(UserRepository userRepository, ProfessionalRepository professionalRepository) {
        this.userRepository = userRepository;
        this.professionalRepository = professionalRepository;
    }

    @Transactional(readOnly = true)
    public UserMeResponse getMe(Long userId) {
        User user = loadActiveUser(userId);

        ProfessionalInfo professionalInfo = null;
        if (user.getRole() == UserRole.PROFESSIONAL) {
            professionalInfo = professionalRepository.findByUserId(user.getId())
                    .map(p -> new ProfessionalInfo(p.getCategoryId(), p.getServiceArea(), p.getBasePrice()))
                    .orElse(null);
        }

        DefaultAddressInfo defaultAddress = null;
        if (user.getRole() == UserRole.CUSTOMER && user.getDefaultCity() != null) {
            defaultAddress = new DefaultAddressInfo(user.getDefaultCity(), user.getDefaultStreet(),
                    user.getDefaultHouseNumber(), user.getDefaultApartment(), user.getDefaultFloor(),
                    user.getDefaultEntrance(), user.getDefaultAddressNotes());
        }

        return new UserMeResponse(user.getId(), user.getFullName(), user.getEmail(),
                user.getRole(), user.isEmailVerified(), professionalInfo, defaultAddress);
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
