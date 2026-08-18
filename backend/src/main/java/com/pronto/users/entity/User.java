package com.pronto.users.entity;

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

import java.time.Instant;

/**
 * JPA entity for the {@code users} table — shared by both {@code CUSTOMER} and
 * {@code PROFESSIONAL} accounts. Mapping matches the already-applied
 * {@code V2__create_users.sql} migration exactly (schema is the source of truth;
 * {@code ddl-auto: validate} rejects any mismatch). See
 * {@code docs/architecture/data-model.md} §2.2.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private UserRole role;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "failed_login_attempts", nullable = false)
    private short failedLoginAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /**
     * Customer default address, collected at registration (backend registration flow
     * separation task §4). Always {@code null} for a {@code PROFESSIONAL} account.
     * {@code defaultCity}/{@code defaultStreet}/{@code defaultHouseNumber} are required
     * at the API layer for a {@code CUSTOMER} registration; the rest are optional. Set
     * via setters rather than the constructor, mirroring how {@code Professional}'s
     * optional {@code bio}/{@code city} are populated after construction.
     */
    @Column(name = "default_city", length = 100)
    private String defaultCity;

    @Column(name = "default_street", length = 150)
    private String defaultStreet;

    @Column(name = "default_house_number", length = 20)
    private String defaultHouseNumber;

    @Column(name = "default_apartment", length = 20)
    private String defaultApartment;

    @Column(name = "default_floor", length = 20)
    private String defaultFloor;

    @Column(name = "default_entrance", length = 20)
    private String defaultEntrance;

    @Column(name = "default_address_notes", length = 500)
    private String defaultAddressNotes;

    /**
     * Customer phone number, collected at registration (professional weekly availability
     * calendar design §2.5/§9.1). Always {@code null} for a {@code PROFESSIONAL} account,
     * same convention as {@code default_city} et al. Set via setter, not the constructor,
     * mirroring how the default-address fields above are populated after construction.
     */
    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected User() {
        // JPA
    }

    public User(String fullName, String email, String passwordHash, UserRole role) {
        this.fullName = fullName;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.emailVerified = false;
        this.failedLoginAttempts = 0;
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

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public UserRole getRole() {
        return role;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public void setEmailVerified(boolean emailVerified) {
        this.emailVerified = emailVerified;
    }

    public short getFailedLoginAttempts() {
        return failedLoginAttempts;
    }

    public void setFailedLoginAttempts(short failedLoginAttempts) {
        this.failedLoginAttempts = failedLoginAttempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(Instant lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public String getDefaultCity() {
        return defaultCity;
    }

    public void setDefaultCity(String defaultCity) {
        this.defaultCity = defaultCity;
    }

    public String getDefaultStreet() {
        return defaultStreet;
    }

    public void setDefaultStreet(String defaultStreet) {
        this.defaultStreet = defaultStreet;
    }

    public String getDefaultHouseNumber() {
        return defaultHouseNumber;
    }

    public void setDefaultHouseNumber(String defaultHouseNumber) {
        this.defaultHouseNumber = defaultHouseNumber;
    }

    public String getDefaultApartment() {
        return defaultApartment;
    }

    public void setDefaultApartment(String defaultApartment) {
        this.defaultApartment = defaultApartment;
    }

    public String getDefaultFloor() {
        return defaultFloor;
    }

    public void setDefaultFloor(String defaultFloor) {
        this.defaultFloor = defaultFloor;
    }

    public String getDefaultEntrance() {
        return defaultEntrance;
    }

    public void setDefaultEntrance(String defaultEntrance) {
        this.defaultEntrance = defaultEntrance;
    }

    public String getDefaultAddressNotes() {
        return defaultAddressNotes;
    }

    public void setDefaultAddressNotes(String defaultAddressNotes) {
        this.defaultAddressNotes = defaultAddressNotes;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
