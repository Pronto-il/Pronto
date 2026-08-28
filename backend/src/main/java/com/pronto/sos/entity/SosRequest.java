package com.pronto.sos.entity;

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
 * JPA entity for the {@code sos_requests} table. All ids are plain FK columns (not
 * {@code @ManyToOne} associations), matching this codebase's universal convention — see
 * {@code bookings.entity.Order}'s Javadoc. Mapping matches {@code V34__create_sos.sql}
 * exactly ({@code ddl-auto: validate}).
 *
 * <p><b>No setter exists for {@link #status}.</b> Every status change goes through one of
 * {@code sos.repository.SosRequestRepository}'s atomic {@code UPDATE ... WHERE
 * <current-state-guard>} methods, never a load-mutate-save round trip — the same rule
 * {@code Order}/{@code OrderRepository} already established, and the reason two concurrent
 * callers cannot both drive this row out of the same status. Callers reload a fresh instance
 * after a successful transition to build a response.
 */
@Entity
@Table(name = "sos_requests")
public class SosRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "issue_id", nullable = false)
    private Long issueId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(name = "sub_service_id")
    private Long subServiceId;

    @Column(name = "issue_summary", length = 300)
    private String issueSummary;

    @Enumerated(EnumType.STRING)
    @Column(name = "urgency", nullable = false, length = 20)
    private SosUrgency urgency;

    @Column(name = "service_city", nullable = false, length = 100)
    private String serviceCity;

    @Column(name = "service_street", nullable = false, length = 150)
    private String serviceStreet;

    @Column(name = "service_house_number", nullable = false, length = 20)
    private String serviceHouseNumber;

    @Column(name = "service_apartment", length = 20)
    private String serviceApartment;

    @Column(name = "service_floor", length = 20)
    private String serviceFloor;

    @Column(name = "service_entrance", length = 20)
    private String serviceEntrance;

    @Column(name = "service_address_notes", length = 500)
    private String serviceAddressNotes;

    /** Optional, never read by v1 matching — see the column comment in {@code V34}. */
    @Column(name = "latitude", precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 9, scale = 6)
    private BigDecimal longitude;

    /**
     * Production MS2 ({@code V50}) — whether {@link #latitude}/{@link #longitude} were resolved,
     * and if not, why. One of {@code maps.GeocodeStatus}' names.
     *
     * <p>{@code V34}'s column comment described the coordinates above as "captured but unused".
     * They are now load-bearing: every candidate's real driving distance is measured to this
     * point, and the radius filter ({@code pronto.sos.max-dispatch-radius-km}) is applied against
     * that distance. A request with no resolved destination cannot be matched geographically at
     * all, and is failed with a message that says so rather than with "no professionals
     * available" — see {@code SosDispatchService#failDegraded}.
     */
    @Column(name = "geocode_status", length = 20)
    private String geocodeStatus;

    /**
     * The place the customer <b>selected</b> for this SOS destination ({@code V55}), rather than
     * the text they typed. Same snapshot and grandfathering semantics as
     * {@code bookings.entity.Order#getServicePlaceId()}: written at creation, never rewritten,
     * and legitimately null for a request raised against a legacy default address.
     */
    @Column(name = "service_place_id", length = 255)
    private String servicePlaceId;

    @Column(name = "service_formatted_address", length = 500)
    private String serviceFormattedAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private SosRequestStatus status;

    @Column(name = "selected_professional_id")
    private Long selectedProfessionalId;

    @Column(name = "selected_offer_id")
    private Long selectedOfferId;

    @Column(name = "order_id")
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancelled_by", length = 20)
    private SosActorType cancelledBy;

    /**
     * How many manual "scan again" expansions this attempt has used ({@code V39}).
     *
     * <p>Canonical backend state, and the compare-and-set target of
     * {@code SosRequestRepository#expandSearch} — which is what makes a double-tapped button
     * produce exactly one expansion rather than two dispatch waves. Never mutated through this
     * entity; like {@link #status} it has no setter.
     */
    @Column(name = "search_expansions", nullable = false)
    private Short searchExpansions;

    /**
     * When the <b>active scanning window</b> closes ({@code pronto.sos.scan-window-seconds} after
     * activation) — the moment the platform stops looking for new professionals to contact.
     *
     * <p>Column name predates the MS3 lifecycle redesign, which split what used to be one
     * "matching window" into three independent timers. It is kept because the meaning did not
     * invert, it narrowed: this is still "how long the platform searches", it simply no longer
     * doubles as the professionals' response deadline (each offer now carries its own
     * {@code expires_at}) — and, since the MS3 follow-up, there is no customer decision deadline
     * at all. Renaming it would have meant rewriting every guarded update in the repository to
     * buy a synonym.
     */
    @Column(name = "matching_expires_at")
    private Instant matchingExpiresAt;

    /**
     * When this request's search next widens automatically, or {@code null} when it never will
     * again — the expansion ceiling was reached, the scan window closed, or a professional was
     * selected ({@code V41}).
     *
     * <p>Server-owned, like {@link #searchExpansions}, and advanced in the same atomic statement
     * as it: the 2-minute expansion cadence is therefore immune to a refresh, a second device,
     * or a client that never comes back. Never mutated through this entity.
     */
    @Column(name = "next_expansion_at")
    private Instant nextExpansionAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "matched_at")
    private Instant matchedAt;

    @Column(name = "candidates_ready_at")
    private Instant candidatesReadyAt;

    @Column(name = "selected_at")
    private Instant selectedAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected SosRequest() {
        // JPA
    }

    /**
     * Always starts {@link SosRequestStatus#CREATED}. {@code categoryId}/{@code subServiceId}
     * are snapshotted from the anchoring {@code issues} row by the caller, not looked up here.
     */
    public SosRequest(Long issueId, Long customerId, Long categoryId, Long subServiceId, String issueSummary,
                       SosUrgency urgency, String serviceCity, String serviceStreet, String serviceHouseNumber,
                       String serviceApartment, String serviceFloor, String serviceEntrance,
                       String serviceAddressNotes, BigDecimal latitude, BigDecimal longitude) {
        this.issueId = issueId;
        this.customerId = customerId;
        this.categoryId = categoryId;
        this.subServiceId = subServiceId;
        this.issueSummary = issueSummary;
        this.urgency = urgency;
        this.serviceCity = serviceCity;
        this.serviceStreet = serviceStreet;
        this.serviceHouseNumber = serviceHouseNumber;
        this.serviceApartment = serviceApartment;
        this.serviceFloor = serviceFloor;
        this.serviceEntrance = serviceEntrance;
        this.serviceAddressNotes = serviceAddressNotes;
        this.latitude = latitude;
        this.longitude = longitude;
        // Production MS2: client-supplied coordinates arrive already resolved -- the customer's
        // own device fix is a better answer than any geocode of their typed address. Everything
        // else starts PENDING for the geocoder.
        this.geocodeStatus = (latitude != null && longitude != null)
                ? com.pronto.maps.GeocodeStatus.RESOLVED.name()
                : com.pronto.maps.GeocodeStatus.PENDING.name();
        this.status = SosRequestStatus.CREATED;
        this.searchExpansions = 0;
    }

    /**
     * Production MS2 — fill in the destination coordinates from a geocode of the service address.
     *
     * <p>Called once, at creation, only when the client supplied no fix of its own. There is no
     * path that overwrites resolved coordinates afterwards: the SOS destination is a snapshot for
     * exactly the same reason an order's is — professionals were dispatched, and their distances
     * evaluated, against a specific point.
     */
    public void applyGeocode(BigDecimal latitude, BigDecimal longitude, com.pronto.maps.GeocodeStatus status) {
        if (this.latitude != null && this.longitude != null) {
            return;
        }
        this.latitude = latitude;
        this.longitude = longitude;
        this.geocodeStatus = status.name();
    }

    public String getGeocodeStatus() {
        return geocodeStatus;
    }

    public String getServicePlaceId() {
        return servicePlaceId;
    }

    public String getServiceFormattedAddress() {
        return serviceFormattedAddress;
    }

    /**
     * Record the place the customer selected for this destination ({@code V55}), and adopt its
     * coordinates when the request does not already have a position.
     *
     * <p>The coordinate half defers to {@link #applyGeocode}'s existing rule — a client-supplied
     * device fix already on the row wins, because "where the phone says I am" is a better answer
     * for an emergency than "the address I picked". The place id is recorded either way: which
     * address was chosen and where the handset was are two different facts, and SOS wants both.
     */
    public void applySelectedPlace(com.pronto.maps.SelectedPlace place) {
        if (place == null) {
            return;
        }
        this.servicePlaceId = place.placeId();
        this.serviceFormattedAddress = place.formattedAddress();
        applyGeocode(place.coordinates().latitude(), place.coordinates().longitude(),
                com.pronto.maps.GeocodeStatus.RESOLVED);
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

    public Long getCategoryId() {
        return categoryId;
    }

    public Long getSubServiceId() {
        return subServiceId;
    }

    public String getIssueSummary() {
        return issueSummary;
    }

    public SosUrgency getUrgency() {
        return urgency;
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

    public BigDecimal getLatitude() {
        return latitude;
    }

    public BigDecimal getLongitude() {
        return longitude;
    }

    public SosRequestStatus getStatus() {
        return status;
    }

    public Long getSelectedProfessionalId() {
        return selectedProfessionalId;
    }

    public Long getSelectedOfferId() {
        return selectedOfferId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public SosActorType getCancelledBy() {
        return cancelledBy;
    }

    /** Never {@code null} — the column is {@code NOT NULL DEFAULT 0} and the constructor seeds it. */
    public int getSearchExpansions() {
        return searchExpansions == null ? 0 : searchExpansions;
    }

    public Instant getMatchingExpiresAt() {
        return matchingExpiresAt;
    }

    /** When the automatic search expansion is next due, or {@code null} if never again. */
    public Instant getNextExpansionAt() {
        return nextExpansionAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getMatchedAt() {
        return matchedAt;
    }

    public Instant getCandidatesReadyAt() {
        return candidatesReadyAt;
    }

    public Instant getSelectedAt() {
        return selectedAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
