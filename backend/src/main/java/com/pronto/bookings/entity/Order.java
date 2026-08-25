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

    @Column(name = "expected_arrival_at")
    private Instant expectedArrivalAt;

    @Column(name = "final_price", precision = 10, scale = 2)
    private BigDecimal finalPrice;

    @Column(name = "slot_id")
    private Long slotId;

    @Column(name = "service_city", length = 100)
    private String serviceCity;

    @Column(name = "service_street", length = 150)
    private String serviceStreet;

    @Column(name = "service_house_number", length = 20)
    private String serviceHouseNumber;

    @Column(name = "service_apartment", length = 20)
    private String serviceApartment;

    @Column(name = "service_floor", length = 20)
    private String serviceFloor;

    @Column(name = "service_entrance", length = 20)
    private String serviceEntrance;

    @Column(name = "service_address_notes", length = 500)
    private String serviceAddressNotes;

    /**
     * Production MS2 — the destination coordinates, <b>snapshotted at creation</b> ({@code V50}),
     * on exactly the same footing as the {@code service_*} text columns above.
     *
     * <p>Immutable for the life of the order, and that is load-bearing rather than tidy: a
     * customer who later edits their default address must not move an order that already exists.
     * The professional was dispatched to a specific place, arrival was (or will be) verified
     * against that place, and silently re-pointing it would invalidate an arrival that already
     * happened. There is deliberately no setter.
     *
     * <p>Nullable — the address may not have been geocodable, in which case the order is still
     * perfectly valid, it simply cannot have its arrival verified geographically. Refusing to
     * create an order because a geocoder was unavailable would turn a provider blip into an
     * outage of the whole booking flow.
     */
    @Column(name = "service_latitude", precision = 9, scale = 6)
    private BigDecimal serviceLatitude;

    @Column(name = "service_longitude", precision = 9, scale = 6)
    private BigDecimal serviceLongitude;

    /**
     * Production MS2 — the arrival verification record ({@code V51}). Written once, by the
     * {@code ON_THE_WAY -> ARRIVED} transition, and never rewritten: this is evidence, and
     * evidence that can be overwritten is not evidence.
     *
     * <p>The coordinates here are the <b>professional's</b> position at the moment they claimed
     * arrival — private operational data on the same footing as {@code professional_locations},
     * and read by no customer-facing DTO. {@link #arrivalDistanceMeters} is the backend's own
     * measurement at that moment, stored rather than recomputed because the destination snapshot
     * is immutable but the configured radius is not: a dispute reviewed months later needs the
     * measured distance, not what today's threshold would have decided.
     */
    @Column(name = "arrived_at")
    private Instant arrivedAt;

    @Column(name = "arrival_latitude", precision = 9, scale = 6)
    private BigDecimal arrivalLatitude;

    @Column(name = "arrival_longitude", precision = 9, scale = 6)
    private BigDecimal arrivalLongitude;

    @Column(name = "arrival_accuracy_meters", precision = 8, scale = 2)
    private BigDecimal arrivalAccuracyMeters;

    @Column(name = "arrival_distance_meters", precision = 10, scale = 2)
    private BigDecimal arrivalDistanceMeters;

    @Column(name = "base_price_snapshot", precision = 10, scale = 2)
    private BigDecimal basePriceSnapshot;

    @Column(name = "sos_surcharge", nullable = false, precision = 10, scale = 2)
    private BigDecimal sosSurcharge;

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
     *
     * <p>{@code serviceApartment} is the only nullable field of the four service-address
     * arguments (§1 classification item 5's request/booking snapshot — apartment is optional
     * on the request DTOs). {@code sosSurcharge} is always explicitly {@code 0.00} for
     * Standard orders, never relying on the DB column default alone in this insert path (see
     * {@code BookingsService}'s {@code SOS_SURCHARGE_AMOUNT} Javadoc).
     */
    public Order(Long issueId, Long customerId, Long professionalId, Instant bookedStart,
                 Instant bookedEnd, BigDecimal finalPrice, Long slotId, String serviceCity, String serviceStreet,
                 String serviceHouseNumber, String serviceApartment, String serviceFloor, String serviceEntrance,
                 String serviceAddressNotes, BigDecimal basePriceSnapshot, BigDecimal sosSurcharge) {
        this.issueId = issueId;
        this.customerId = customerId;
        this.professionalId = professionalId;
        this.bookedStart = bookedStart;
        this.bookedEnd = bookedEnd;
        this.orderStatus = OrderStatus.PENDING;
        this.cancelledBy = null;
        this.finalPrice = finalPrice;
        this.slotId = slotId;
        this.serviceCity = serviceCity;
        this.serviceStreet = serviceStreet;
        this.serviceHouseNumber = serviceHouseNumber;
        this.serviceApartment = serviceApartment;
        this.serviceFloor = serviceFloor;
        this.serviceEntrance = serviceEntrance;
        this.serviceAddressNotes = serviceAddressNotes;
        this.basePriceSnapshot = basePriceSnapshot;
        this.sosSurcharge = sosSurcharge;
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

    public Instant getExpectedArrivalAt() {
        return expectedArrivalAt;
    }

    public BigDecimal getFinalPrice() {
        return finalPrice;
    }

    public Long getSlotId() {
        return slotId;
    }

    public String getServiceCity() {
        return serviceCity;
    }

    public String getServiceStreet() {
        return serviceStreet;
    }

    public String getServiceHouseNumber() {
        return serviceHouseNumber;
    }

    public String getServiceApartment() {
        return serviceApartment;
    }

    public String getServiceFloor() {
        return serviceFloor;
    }

    public String getServiceEntrance() {
        return serviceEntrance;
    }

    public String getServiceAddressNotes() {
        return serviceAddressNotes;
    }

    public BigDecimal getBasePriceSnapshot() {
        return basePriceSnapshot;
    }

    public BigDecimal getSosSurcharge() {
        return sosSurcharge;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    // ---- Production MS2: destination snapshot and arrival verification ----

    public BigDecimal getServiceLatitude() {
        return serviceLatitude;
    }

    public BigDecimal getServiceLongitude() {
        return serviceLongitude;
    }

    /**
     * The order's destination, or {@code null} when the address was not geocodable.
     *
     * <p>Returns the {@code maps} value type rather than two loose {@link BigDecimal}s so that
     * every consumer — the arrival geofence, the {@code ON_THE_WAY} estimate — works with a
     * validated pair and cannot accidentally use a half-populated one.
     */
    public com.pronto.maps.GeoCoordinates serviceCoordinates() {
        return com.pronto.maps.GeoCoordinates.ofNullable(serviceLatitude, serviceLongitude);
    }

    /**
     * Write the destination snapshot. Called once, at creation, immediately after construction —
     * the same "optional fields populated after construction" pattern
     * {@code professionals.entity.Professional} already uses, chosen over two more constructor
     * parameters because the constructor already takes sixteen.
     *
     * <p><b>Write-once, enforced.</b> Attempting to change an existing snapshot throws rather than
     * silently succeeding: re-pointing an order that a professional has already been dispatched to
     * — possibly one whose arrival has already been verified against the old point — is never a
     * correct thing to do, and a method that permitted it would eventually be called.
     */
    public void snapshotServiceCoordinates(com.pronto.maps.GeoCoordinates coordinates) {
        if (coordinates == null) {
            return;
        }
        if (serviceLatitude != null || serviceLongitude != null) {
            throw new IllegalStateException("Order " + id + " already has a destination snapshot; an order's "
                    + "destination is immutable once created.");
        }
        this.serviceLatitude = coordinates.latitude();
        this.serviceLongitude = coordinates.longitude();
    }

    public Instant getArrivedAt() {
        return arrivedAt;
    }

    public BigDecimal getArrivalAccuracyMeters() {
        return arrivalAccuracyMeters;
    }

    public BigDecimal getArrivalDistanceMeters() {
        return arrivalDistanceMeters;
    }

    /**
     * Record the evidence behind a verified arrival. Called by the {@code ON_THE_WAY -> ARRIVED}
     * transition <em>after</em> the backend has measured the distance and decided it is inside the
     * geofence — this method records a decision, it does not make one.
     *
     * <p>The status change itself is <b>not</b> done here: like every other transition on this
     * entity it goes through {@code OrderRepository}'s atomic
     * {@code UPDATE ... WHERE <current-state-guard>}, so two concurrent arrival claims cannot both
     * win. This writes only the evidence columns, in the same transaction.
     */
    public void recordArrivalEvidence(com.pronto.maps.GeoCoordinates position, BigDecimal accuracyMeters,
                                       BigDecimal distanceMeters, Instant verifiedAt) {
        this.arrivalLatitude = position.latitude();
        this.arrivalLongitude = position.longitude();
        this.arrivalAccuracyMeters = accuracyMeters;
        this.arrivalDistanceMeters = distanceMeters;
        this.arrivedAt = verifiedAt;
    }
}
