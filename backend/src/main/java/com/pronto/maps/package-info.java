/**
 * <b>Geography.</b> Every coordinate, every real driving distance, every real arrival time, and
 * every rule about whether a position may be trusted.
 *
 * <p>Introduced by Production MS2 to replace a placeholder that answered a geographic question
 * with a string comparison: before this package existed, "how far away is this professional" was
 * decided by comparing their registered city to the customer's, and returned 8 km if the strings
 * matched and 35 km if they did not. Every ETA in the platform was one of four fixed numbers.
 *
 * <h2>What lives here</h2>
 *
 * <ul>
 *   <li><b>Value types</b> — {@link com.pronto.maps.GeoCoordinates} (validated on construction,
 *       so an out-of-range position cannot exist), {@link com.pronto.maps.PostalAddress} (the
 *       building-locating fields plus the change-detecting digest that makes geocode reuse safe),
 *       {@link com.pronto.maps.RouteResult}/{@link com.pronto.maps.GeocodeResult} (either figures
 *       <em>or</em> a reason, never both and never neither).</li>
 *   <li><b>Provider seams</b> — {@link com.pronto.maps.GeocodingProvider} and
 *       {@link com.pronto.maps.RoutingProvider}, with a Google implementation and an offline
 *       deterministic one, following the same swappable-abstraction pattern
 *       {@code storage.client.StorageClient} and {@code auth.email.EmailSender} established.</li>
 *   <li><b>Local geometry</b> — {@link com.pronto.maps.GeoDistance}, Haversine, used for
 *       proximity questions only and never for a customer-facing distance figure.</li>
 *   <li><b>Policy</b> — {@code config.LocationProperties} (what counts as a trustworthy fix, how
 *       close counts as arrived) and {@code config.MapsProperties} (which vendor, which key,
 *       which budget). Deliberately two objects: one is about Pronto, the other about a supplier.</li>
 *   <li><b>Orchestration</b> — {@code service.ServiceAddressGeocoder} (when an address is
 *       resolved and when it is reused) and {@code service.ArrivalVerifier} (the one geofence
 *       rule, shared by the Standard and SOS flows).</li>
 * </ul>
 *
 * <h2>The rule the whole package exists to enforce</h2>
 *
 * <b>No code path may produce a distance or an arrival time that did not come from a real
 * route.</b> It is enforced by type rather than by discipline: an unavailable
 * {@code RouteResult}/{@code EtaResult} cannot carry figures, so a caller that wants a number
 * must handle the possibility that there is not one.
 *
 * <h2>What this package does not own</h2>
 *
 * The {@code professional_locations} table and its entity live in {@code professionals} — the
 * position belongs to the professional, and this package supplies the rules for judging it. The
 * {@code service_cities}/{@code service_regions} catalogue lives in {@code locations}, which is
 * reference data rather than geometry. {@code matching} still owns
 * {@code DistanceEtaStrategy}, the seam every consumer calls; it is now implemented on top of
 * this package rather than on a string comparison.
 *
 * <p>See {@code README.md} in this package for the provider decision, the privacy posture, the
 * call-budget analysis and the anti-spoofing limitation.
 */
package com.pronto.maps;
