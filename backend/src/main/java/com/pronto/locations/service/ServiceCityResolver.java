package com.pronto.locations.service;

import com.pronto.locations.entity.ServiceCity;
import com.pronto.locations.repository.ServiceCityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * <b>The bridge from a customer's address text to our own canonical {@code service_cities} row.</b>
 *
 * <h2>Why this has to exist</h2>
 *
 * <p>Professional coverage is expressed in canonical ids — {@code professionals.base_city_id} and
 * {@code professional_service_cities.city_id}, both FKs into a closed, seeded catalogue. A
 * customer's service address is not: {@code users.default_city}, {@code orders.service_city} and
 * {@code sos_requests.service_city} are all free text, because the address is also a thing a
 * professional has to read to find the door, and Google is what resolves it.
 *
 * <p>So the two sides of "does this professional serve this customer's city" are stored in two
 * different vocabularies, and something has to translate. Doing that translation in the WHERE
 * clause — comparing the customer's typed city against a professional's city string — is exactly
 * what {@code matching.ServiceLocation}'s Javadoc records as the defect MS2 removed from routing.
 * This class is the deliberate, single, testable place that translation happens instead, and it
 * always translates <b>into</b> a canonical id, never the other way.
 *
 * <h2>How the match is made</h2>
 *
 * <p>Against {@code name_he}, which carries {@code ux_service_cities_name_he} and is therefore
 * unique by construction. Both sides are reduced to the same canonical form first — Unicode NFC,
 * Hebrew geresh/gershayim and ASCII quotes stripped, case-folded, hyphens treated as spaces,
 * whitespace collapsed — which is the normalization {@code V44}'s backfill applied to
 * professionals' free text, restated in Java because the customer side is resolved per request
 * rather than once in a migration. That alone makes {@code "תל-אביב"} and {@code "תל אביב"} the
 * same place.
 *
 * <p>Then <b>trailing hyphen components are dropped, longest form first</b>. Israeli
 * municipalities that merged carry both names joined by a hyphen: Google answers
 * {@code "תל אביב-יפו"} for a Tel Aviv address and the catalogue's row is {@code "תל אביב"}. So
 * {@code "תל אביב-יפו"} is tried whole, then {@code "תל אביב"}. Longest-first means the most
 * specific catalogue entry always wins when both exist.
 *
 * <p><b>Only hyphen components are ever dropped — never space-separated words.</b> That is the
 * property that keeps this from becoming a prefix or substring match: {@code "קרית גת"} is a
 * single component and can never degrade to {@code "קרית"}, so a customer in one town is never
 * quietly placed in another whose name it happens to begin with.
 *
 * <p>Anything that does not match is {@link Optional#empty()}, which callers must treat as "we do
 * not cover this place", never as "no filter".
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p>No fuzzy matching, no edit distance, no prefix matching, and no region-level fallback. A
 * customer whose city cannot be resolved gets an honest empty result rather than a guess, because
 * the failure mode of guessing is dispatching a professional several hours away — which is the bug
 * this whole change exists to fix, reintroduced with extra steps.
 *
 * <p><b>Known limitation, worth stating.</b> The catalogue holds ~107 localities and Israel has an
 * order of magnitude more. A customer in a small moshav will type its name, it will not resolve,
 * and they will see an empty listing even though a nearby professional would happily serve them.
 * That is a <em>coverage-data</em> gap, not a matching bug, and the fix is to seed more rows (or
 * add a {@code service_city_aliases} table keyed on Google place ids) — not to relax this
 * resolver. Recorded here so the next reader does not "fix" it by loosening the match.
 */
@Service
public class ServiceCityResolver {

    private static final Logger log = LoggerFactory.getLogger(ServiceCityResolver.class);

    /** Hebrew geresh/gershayim and the ASCII quotes people substitute for them. */
    private static final String QUOTE_CHARACTERS = "[׳״'\"`]";

    private final ServiceCityRepository serviceCityRepository;

    public ServiceCityResolver(ServiceCityRepository serviceCityRepository) {
        this.serviceCityRepository = serviceCityRepository;
    }

    /**
     * The canonical city for a customer-supplied city name, or empty when this platform's
     * catalogue does not contain it.
     *
     * <p>Reads the whole table (~107 rows of seeded reference data) and matches in memory rather
     * than pushing normalization into JPQL, which has no portable {@code REPLACE}. At this volume
     * the query is cheaper than the routing call the same request already makes, and keeping the
     * rule in Java is what makes it unit-testable without a database.
     */
    @Transactional(readOnly = true)
    public Optional<ServiceCity> resolve(String cityName) {
        String normalized = normalize(cityName);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }

        List<ServiceCity> catalogue = serviceCityRepository.findAll();

        List<String> components = Arrays.stream(normalized.split("-"))
                .map(ServiceCityResolver::collapseWhitespace)
                .filter(component -> !component.isEmpty())
                .toList();

        // Longest form first: "תל אביב יפו", then "תל אביב". A catalogue that carries the full
        // merged name gets it; one that carries only the surviving half gets that.
        for (int length = components.size(); length >= 1; length--) {
            String candidate = String.join(" ", components.subList(0, length));
            Optional<ServiceCity> match = catalogue.stream()
                    .filter(city -> canonical(city.getNameHe()).equals(candidate))
                    .findFirst();
            if (match.isPresent()) {
                return match;
            }
        }

        // Logged at DEBUG, not WARN: an unresolvable city is an ordinary consequence of a
        // catalogue that does not cover every Israeli locality, not an error. The city name is
        // included because it is the only way to learn which rows the catalogue is missing, and it
        // is a place name rather than personal data -- no street, no house number, no account.
        log.debug("locations.city.unresolved city=\"{}\"", normalized);
        return Optional.empty();
    }

    /** {@link #resolve} projected to the id, which is what every coverage query actually binds. */
    @Transactional(readOnly = true)
    public Optional<Long> resolveId(String cityName) {
        return resolve(cityName).map(ServiceCity::getId);
    }

    /**
     * Case-folded, quote-stripped, whitespace-collapsed NFC form.
     *
     * <p>NFC first because Hebrew text arriving from a browser may carry decomposed points, and
     * two strings that render identically must not fail to match over an encoding choice nobody
     * made deliberately.
     */
    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String folded = Normalizer.normalize(raw, Normalizer.Form.NFC)
                .replaceAll(QUOTE_CHARACTERS, "")
                .toLowerCase(Locale.ROOT);
        return collapseWhitespace(folded);
    }

    /**
     * {@link #normalize} with hyphens flattened to spaces — the form both sides are compared in.
     *
     * <p>Applied to the catalogue name as well as the customer's text, so {@code "תל-אביב"} and
     * {@code "תל אביב"} are one place regardless of which side spelled it which way.
     */
    static String canonical(String raw) {
        return collapseWhitespace(normalize(raw).replace('-', ' '));
    }

    private static String collapseWhitespace(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }
}
