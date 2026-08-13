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
}
