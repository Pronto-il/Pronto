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
     * Production MS2 — the geocoded coordinates of the default address above, and the state of
     * that resolution ({@code V50}).
     *
     * <p>Added <b>beside</b> the address text, never instead of it: the text is what a
     * professional reads to find the door, and a geocoder's rendering of an address is not the
     * customer's address. Nullable for legacy rows, and for any address the geocoder could not
     * resolve — every consumer already handles absent coordinates, because a live geocode can
     * fail too.
     *
     * <p>{@link #defaultAddressHash} is what makes reuse safe and invalidation automatic: it is a
     * digest of the exact text these coordinates were resolved from, so an address edit is
     * self-detecting rather than something every edit path must remember to announce. See
     * {@code maps.service.ServiceAddressGeocoder}.
     */
    @Column(name = "default_latitude", precision = 9, scale = 6)
    private java.math.BigDecimal defaultLatitude;

    @Column(name = "default_longitude", precision = 9, scale = 6)
    private java.math.BigDecimal defaultLongitude;

    /** One of {@code maps.GeocodeStatus}' names, or {@code null} for "never attempted". */
    @Column(name = "default_geocode_status", length = 20)
    private String defaultGeocodeStatus;

    @Column(name = "default_geocoded_at")
    private Instant defaultGeocodedAt;

    @Column(name = "default_address_hash", length = 64)
    private String defaultAddressHash;

    /**
     * Advisory reconciliation of the free-text {@link #defaultCity} against the closed
     * {@code service_cities} catalogue. <b>Never gates anything</b> — a customer in a town outside
     * the catalogue keeps working exactly as before. See {@code V50}'s header.
     */
    @Column(name = "default_service_city_id")
    private Long defaultServiceCityId;

    /**
     * The place the customer <b>selected</b> from address autocomplete ({@code V55}), as opposed
     * to the free text they typed into {@link #defaultCity} and friends.
     *
     * <p>Answers a different question from the coordinates above. Those say "where does this text
     * resolve to?", which a geocoder will attempt for any string; this says "a human picked this
     * from a list of places that exist". Only the second makes an address <em>validated</em>.
     *
     * <p><b>Null means legacy, not broken.</b> Every address saved before {@code V55} has no place
     * id, is deliberately not backfilled, and keeps working for booking exactly as before. It
     * gains one the next time the customer edits the address, which is the only moment a
     * re-selection can be asked for without interrupting somebody who was not editing anything.
     */
    @Column(name = "default_place_id", length = 255)
    private String defaultPlaceId;

    @Column(name = "default_formatted_address", length = 500)
    private String defaultFormattedAddress;

    /**
     * Phone number in canonical E.164, e.g. {@code +972501234567}.
     *
     * <p><b>Production MS1 changed what this column means.</b> It arrived (V28) as free-text
     * contact detail collected for {@code CUSTOMER} registrations only. It is now an identity:
     * unique ({@code ux_users_phone}), shape-constrained ({@code ck_users_phone_e164}), required at
     * the API layer for every new registration of <em>every</em> role, and usable as a login
     * identifier once {@link #phoneVerified}. Always written through
     * {@code auth.service.PhoneNumberNormalizer} — never from raw user input.
     *
     * <p>Still nullable at the database level, and that nullability is doing real work: it is the
     * legacy cohort. Every {@code PROFESSIONAL} and {@code ADMIN} row created before MS1 has no
     * phone, as does any {@code CUSTOMER} predating V28 and any row whose stored text V46 could not
     * canonicalize. Those accounts keep authenticating by email and are refused sensitive
     * marketplace mutations ({@code PHONE_VERIFICATION_REQUIRED}) until they complete phone
     * capture. A {@code NOT NULL} here would have meant inventing phone numbers for them.
     */
    @Column(name = "phone", length = 20)
    private String phone;

    /**
     * Whether an SMS OTP sent to {@link #phone} has actually been redeemed. Mirrors
     * {@link #emailVerified} exactly, including defaulting to {@code false} for every pre-existing
     * row: a legacy phone number was never confirmed, and V46 deliberately grandfathers nobody.
     */
    @Column(name = "phone_verified", nullable = false)
    private boolean phoneVerified;

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
        this.phoneVerified = false;
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

    /**
     * Production MS1: password recovery needs to replace the hash. Deliberately takes the
     * already-encoded value — this entity does not know what a {@code PasswordEncoder} is, and a
     * setter that took a plaintext password would be one refactor away from storing one.
     */
    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
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

    // ---- Production MS2: default-address geocoding state ----

    public java.math.BigDecimal getDefaultLatitude() {
        return defaultLatitude;
    }

    public java.math.BigDecimal getDefaultLongitude() {
        return defaultLongitude;
    }

    public String getDefaultGeocodeStatus() {
        return defaultGeocodeStatus;
    }

    public Instant getDefaultGeocodedAt() {
        return defaultGeocodedAt;
    }

    public String getDefaultAddressHash() {
        return defaultAddressHash;
    }

    public Long getDefaultServiceCityId() {
        return defaultServiceCityId;
    }

    public void setDefaultServiceCityId(Long defaultServiceCityId) {
        this.defaultServiceCityId = defaultServiceCityId;
    }

    /**
     * Record the outcome of one geocoding attempt.
     *
     * <p>Written as a single method rather than six setters because the fields are one fact and
     * must move together — {@code ck_users_default_geocode_consistency} refuses a row where the
     * status says {@code RESOLVED} and the coordinates are absent, or vice versa, and six
     * independent setters is how a caller ends up writing half of that.
     */
    public void applyDefaultGeocode(java.math.BigDecimal latitude, java.math.BigDecimal longitude,
                                     String status, Instant geocodedAt, String addressHash) {
        this.defaultLatitude = latitude;
        this.defaultLongitude = longitude;
        this.defaultGeocodeStatus = status;
        this.defaultGeocodedAt = geocodedAt;
        this.defaultAddressHash = addressHash;
    }

    /**
     * Back to "never attempted", clearing the coordinates immediately.
     *
     * <p>Called when the address is edited. The coordinates go first and synchronously, so no read
     * between the edit and the next resolve can route to where the customer used to live.
     */
    public String getDefaultPlaceId() {
        return defaultPlaceId;
    }

    public String getDefaultFormattedAddress() {
        return defaultFormattedAddress;
    }

    /**
     * Adopt a selected place as this account's default address: its identity, its normalized
     * rendering, and its coordinates, which are marked {@code RESOLVED} against the current
     * address text.
     *
     * <p><b>This is a write path that costs no provider call.</b> The coordinates came from the
     * place the customer picked, which is a better answer than geocoding the text would produce
     * and one that has already been paid for. The address hash is still written, so every
     * existing reuse and invalidation rule in {@code ServiceAddressGeocoder} continues to apply
     * unchanged — including "an address edit invalidates its coordinates".
     *
     * <p>One method rather than four setters, for the reason {@link #applyDefaultGeocode} gives:
     * these fields are one fact, and {@code ck_users_default_geocode_consistency} refuses a row
     * that holds half of it.
     */
    public void applySelectedPlace(com.pronto.maps.SelectedPlace place, Instant resolvedAt,
                                    String addressHash) {
        applyDefaultGeocode(place.coordinates().latitude(), place.coordinates().longitude(),
                com.pronto.maps.GeocodeStatus.RESOLVED.name(), resolvedAt, addressHash);
        this.defaultPlaceId = place.placeId();
        this.defaultFormattedAddress = place.formattedAddress();
    }

    public void clearDefaultGeocode() {
        applyDefaultGeocode(null, null, null, null, null);
        this.defaultServiceCityId = null;
        // The selected place describes the address that was just replaced, so it goes with the
        // coordinates. Leaving it behind would mean a freshly-typed address inheriting the
        // previous one's proof of selection -- which is precisely the "edit invalidates the
        // selection" rule, enforced in the entity rather than trusted to each edit path.
        this.defaultPlaceId = null;
        this.defaultFormattedAddress = null;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public boolean isPhoneVerified() {
        return phoneVerified;
    }

    public void setPhoneVerified(boolean phoneVerified) {
        this.phoneVerified = phoneVerified;
    }

    /**
     * True only when both contact channels are confirmed. The single question every
     * marketplace-mutation gate asks — see {@code auth.security.ContactVerificationGuard}.
     */
    public boolean isFullyVerified() {
        return emailVerified && phoneVerified;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
