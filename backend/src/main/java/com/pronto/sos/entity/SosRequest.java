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

    @Column(name = "matching_expires_at")
    private Instant matchingExpiresAt;

    @Column(name = "selection_expires_at")
    private Instant selectionExpiresAt;

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
        this.status = SosRequestStatus.CREATED;
        this.searchExpansions = 0;
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

    public Instant getSelectionExpiresAt() {
        return selectionExpiresAt;
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
