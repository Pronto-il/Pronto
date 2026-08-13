package com.pronto.issues.repository;

import com.pronto.issues.entity.Issue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/**
 * {@link #bookIfOpen}/{@link #revertToOpen} implement this milestone's atomic
 * {@code UPDATE ... WHERE <current-state-guard>} pattern (§3.2 of
 * {@code docs/architecture/api-contract-bookings.md}) for {@code issues.status}, used by
 * {@code bookings.service.BookingsService} — see that doc's §2.4 step 9 / §2.6 step 6 /
 * §2.7 step 7 / §3.3.
 */
public interface IssueRepository extends JpaRepository<Issue, Long> {

    /**
     * §2.4 step 9. {@code 0} affected rows means the issue was booked by a concurrent
     * request between the caller's own read and this write — the whole order-creation
     * transaction is rolled back in that case.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Issue i SET i.status = com.pronto.issues.entity.IssueStatus.BOOKED, "
            + "i.updatedAt = :now WHERE i.id = :issueId AND i.status = com.pronto.issues.entity.IssueStatus.OPEN")
    int bookIfOpen(@Param("issueId") Long issueId, @Param("now") Instant now);

    /**
     * §2.6 step 6 / §2.7 step 7. Unconditional (no {@code WHERE status = 'BOOKED'} guard) —
     * safe because the single-active-order-per-issue invariant (§3.3) guarantees there is
     * structurally at most one active order per issue at a time.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Issue i SET i.status = com.pronto.issues.entity.IssueStatus.OPEN, i.updatedAt = :now WHERE i.id = :issueId")
    int revertToOpen(@Param("issueId") Long issueId, @Param("now") Instant now);

    /**
     * §4.5 of {@code api-contract-notifications.md} — used by {@code
     * BookingsService.expireIfPending}. Guarded on {@code BOOKED} (the only state an order's
     * issue can be in while that order is still {@code PENDING}, per the single-active-order
     * invariant, §3.3 of {@code api-contract-bookings.md}) rather than unconditional like
     * {@link #revertToOpen}.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Issue i SET i.status = com.pronto.issues.entity.IssueStatus.EXPIRED, i.updatedAt = :now "
            + "WHERE i.id = :issueId AND i.status = com.pronto.issues.entity.IssueStatus.BOOKED")
    int expireIfBooked(@Param("issueId") Long issueId, @Param("now") Instant now);

    /**
     * §2.17 step 5 of {@code api-contract-bookings.md} (Milestone 6) — used by
     * {@code BookingsService.complete}. Mirrors {@link #expireIfBooked}'s exact shape, guarded
     * on {@code BOOKED} rather than unconditional like {@link #revertToOpen}. Not branched
     * on/checked for a {@code 0}-row result by its caller, exactly like {@link #expireIfBooked}
     * isn't — the single-active-order-per-issue invariant (§3.3) guarantees this always
     * succeeds when reached (§2.17's own reasoning).
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Issue i SET i.status = com.pronto.issues.entity.IssueStatus.COMPLETED, i.updatedAt = :now "
            + "WHERE i.id = :issueId AND i.status = com.pronto.issues.entity.IssueStatus.BOOKED")
    int completeIfBooked(@Param("issueId") Long issueId, @Param("now") Instant now);
}
