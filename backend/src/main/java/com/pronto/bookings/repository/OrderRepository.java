package com.pronto.bookings.repository;

import com.pronto.bookings.entity.CancelledBy;
import com.pronto.bookings.entity.Order;
import com.pronto.bookings.entity.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * See {@code docs/architecture/api-contract-bookings.md} §2.1/§2.4-2.9/§3.2. The three
 * {@code @Modifying} methods below are this package's atomic
 * {@code UPDATE ... WHERE <current-state-guard>} transitions (§3.2) — every
 * {@code orders.order_status} change in this milestone goes through exactly one of them, never
 * a load-mutate-save round trip.
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    /** §2.1 step 4 — most-recently-created order for an issue, regardless of status. */
    Optional<Order> findFirstByIssueIdOrderByCreatedAtDesc(Long issueId);

    /** §2.1 step 3 — a professional may view an issue if they have any order against it. */
    boolean existsByIssueIdAndProfessionalId(Long issueId, Long professionalId);

    List<Order> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    List<Order> findByCustomerIdAndOrderStatusOrderByCreatedAtDesc(Long customerId, OrderStatus orderStatus);

    List<Order> findByProfessionalIdOrderByCreatedAtDesc(Long professionalId);

    List<Order> findByProfessionalIdAndOrderStatusOrderByCreatedAtDesc(Long professionalId, OrderStatus orderStatus);

    /** §2.5 step 4. {@code 0} affected rows means the order wasn't {@code PENDING}. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Order o SET o.orderStatus = com.pronto.bookings.entity.OrderStatus.CONFIRMED, "
            + "o.updatedAt = :now WHERE o.id = :orderId AND o.orderStatus = com.pronto.bookings.entity.OrderStatus.PENDING")
    int acceptIfPending(@Param("orderId") Long orderId, @Param("now") Instant now);

    /** §2.6 step 4. {@code 0} affected rows means the order wasn't {@code PENDING}. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Order o SET o.orderStatus = com.pronto.bookings.entity.OrderStatus.REJECTED, "
            + "o.updatedAt = :now WHERE o.id = :orderId AND o.orderStatus = com.pronto.bookings.entity.OrderStatus.PENDING")
    int rejectIfPending(@Param("orderId") Long orderId, @Param("now") Instant now);

    /**
     * §2.7 step 5. {@code expectedStatus} is the status the caller already read/validated
     * in step 4 of that section — re-checked here as the concurrency guard. {@code 0}
     * affected rows means the order changed state between the read and this write (lost a
     * race).
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Order o SET o.orderStatus = com.pronto.bookings.entity.OrderStatus.CANCELLED, "
            + "o.cancelledBy = :cancelledBy, o.updatedAt = :now "
            + "WHERE o.id = :orderId AND o.orderStatus = :expectedStatus")
    int cancelIfStatus(@Param("orderId") Long orderId, @Param("expectedStatus") OrderStatus expectedStatus,
                        @Param("cancelledBy") CancelledBy cancelledBy, @Param("now") Instant now);

    /**
     * §4.5 of {@code api-contract-notifications.md} — mirrors {@link #rejectIfPending}
     * exactly, target status {@code EXPIRED} instead of {@code REJECTED}. {@code 0} affected
     * rows means the order already left {@code PENDING} (another caller won the race).
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Order o SET o.orderStatus = com.pronto.bookings.entity.OrderStatus.EXPIRED, "
            + "o.updatedAt = :now WHERE o.id = :orderId AND o.orderStatus = com.pronto.bookings.entity.OrderStatus.PENDING")
    int expireIfPending(@Param("orderId") Long orderId, @Param("now") Instant now);

    /**
     * §2.16 step 4, extended by the active-booking-floating-indicator design to also persist
     * the ETA the service layer already computed (DistanceEtaStrategy.calculate is a pure,
     * stateless call made in BookingsService.onTheWay, never inside this repository) at the
     * moment of transition. 0 affected rows means the order wasn't CONFIRMED.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Order o SET o.orderStatus = com.pronto.bookings.entity.OrderStatus.ON_THE_WAY, "
            + "o.updatedAt = :now, o.expectedArrivalAt = :expectedArrivalAt "
            + "WHERE o.id = :orderId AND o.orderStatus = com.pronto.bookings.entity.OrderStatus.CONFIRMED")
    int onTheWayIfConfirmed(@Param("orderId") Long orderId, @Param("now") Instant now,
                             @Param("expectedArrivalAt") Instant expectedArrivalAt);

    /**
     * <b>Production MS2.</b> {@code ON_THE_WAY -> ARRIVED}, the atomic half of verified arrival.
     *
     * <p>Called only after {@code BookingsService#arrived} has measured the professional's
     * verified position against the order's destination snapshot and found it inside the
     * geofence — this statement records that decision, it does not make it. {@code 0} affected
     * rows means the order left {@code ON_THE_WAY} between the check and this write (a cancel, or
     * a duplicate arrival claim from a second tab), which is a {@code 409}, not a silent success:
     * a second claim must not restamp {@code arrived_at} and move the evidence.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Order o SET o.orderStatus = com.pronto.bookings.entity.OrderStatus.ARRIVED, "
            + "o.updatedAt = :now WHERE o.id = :orderId "
            + "AND o.orderStatus = com.pronto.bookings.entity.OrderStatus.ON_THE_WAY")
    int arrivedIfOnTheWay(@Param("orderId") Long orderId, @Param("now") Instant now);

    /**
     * §2.17 step 4 (Milestone 6), <b>widened by Production MS2 to accept {@code ARRIVED}</b>.
     *
     * <p>{@code ARRIVED} is an optional intermediate state ({@code V51}): a professional whose
     * device has no usable GPS never reaches it, and every order in flight when MS2 shipped is
     * still {@code ON_THE_WAY}. Completion therefore has to be legal from both, and narrowing it
     * to {@code ARRIVED} only would have made verified arrival a toll gate capable of stranding a
     * professional mid-job — exactly what {@code OrderStatus.ARRIVED}'s Javadoc says it must not
     * be.
     *
     * <p>{@code 0} affected rows still means the order was in neither status — including the
     * deliberately-disallowed {@code CONFIRMED -> COMPLETED} skip-ahead, which fails this guard
     * exactly as it always did.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Order o SET o.orderStatus = com.pronto.bookings.entity.OrderStatus.COMPLETED, "
            + "o.updatedAt = :now WHERE o.id = :orderId AND o.orderStatus IN "
            + "(com.pronto.bookings.entity.OrderStatus.ON_THE_WAY, com.pronto.bookings.entity.OrderStatus.ARRIVED)")
    int completeIfOnTheWay(@Param("orderId") Long orderId, @Param("now") Instant now);

    /**
     * §4.5 — candidate {@code PENDING} orders past their per-urgency-type timeout, for the
     * expiry sweep. Cross-entity comma-join JPQL, same style as
     * {@code ProfessionalListingRepository}'s existing Professional/User/SosAvailability
     * joins.
     */
    @Query("SELECT o.id FROM Order o, Issue i WHERE o.issueId = i.id "
            + "AND o.orderStatus = com.pronto.bookings.entity.OrderStatus.PENDING "
            + "AND ((i.urgencyType = com.pronto.issues.entity.IssueUrgencyType.STANDARD AND o.createdAt < :standardCutoff) "
            + "OR (i.urgencyType = com.pronto.issues.entity.IssueUrgencyType.SOS AND o.createdAt < :sosCutoff))")
    List<Long> findPendingExpiryCandidateIds(@Param("standardCutoff") Instant standardCutoff,
                                              @Param("sosCutoff") Instant sosCutoff);
}
