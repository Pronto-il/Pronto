package com.pronto.bookings.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * JPA entity for the {@code orders} table. {@code issueId}/{@code customerId}/
 * {@code professionalId}/{@code slotId} are plain FK columns (not {@code @ManyToOne}
 * associations), matching the convention already used by {@code issues.entity.Issue}. Mapping
 * matches the already-applied {@code V8__create_orders.sql}, plus this milestone's
 * {@code V11}/{@code V12} migrations, exactly ({@code ddl-auto: validate}). See
 * {@code docs/architecture/data-model.md} §2.9 and
 * {@code docs/architecture/api-contract-bookings.md} §1.
 *
 * <p>Every state transition on this entity (accept/reject/cancel) is performed via
 * {@code bookings.repository.OrderRepository}'s atomic {@code UPDATE ... WHERE
 * <current-state-guard>} methods, not by loading-mutating-saving this object — see that
 * repository's javadoc and §3.2 of the contract doc. This class exposes no setters for
 * {@code orderStatus}/{@code cancelledBy} for that reason; callers reload a fresh instance
 * via {@code findById} after a successful transition to build a response.
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "issue_id", nullable = false)
    private Long issueId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "professional_id", nullable = false)
    private Long professionalId;

    @Column(name = "booked_start", nullable = false)
    private Instant bookedStart;

    @Column(name = "booked_end")
    private Instant bookedEnd;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_status", nullable = false, length = 20)
    private OrderStatus orderStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancelled_by", length = 20)
    private CancelledBy cancelledBy;

    @Column(name = "final_price", precision = 10, scale = 2)
    private BigDecimal finalPrice;

    @Column(name = "slot_id")
    private Long slotId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Order() {
        // JPA
    }

    /**
     * Always starts {@code PENDING} with {@code cancelledBy = NULL} — this milestone's
     * {@code POST /api/bookings/orders} (§2.4) is the only place a row is created.
     * {@code slotId} is always set for a Standard order (§2.4 step 10); a future SOS order
     * (Milestone 4) would pass {@code null} and leave {@code bookedEnd} null.
     */
    public Order(Long issueId, Long customerId, Long professionalId, Instant bookedStart,
                 Instant bookedEnd, BigDecimal finalPrice, Long slotId) {
        this.issueId = issueId;
        this.customerId = customerId;
        this.professionalId = professionalId;
        this.bookedStart = bookedStart;
        this.bookedEnd = bookedEnd;
        this.orderStatus = OrderStatus.PENDING;
        this.cancelledBy = null;
        this.finalPrice = finalPrice;
        this.slotId = slotId;
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getIssueId() {
        return issueId;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public Long getProfessionalId() {
        return professionalId;
    }

    public Instant getBookedStart() {
        return bookedStart;
    }

    public Instant getBookedEnd() {
        return bookedEnd;
    }

    public OrderStatus getOrderStatus() {
        return orderStatus;
    }

    public CancelledBy getCancelledBy() {
        return cancelledBy;
    }

    public BigDecimal getFinalPrice() {
        return finalPrice;
    }

    public Long getSlotId() {
        return slotId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
