package com.pronto.auth.service;

import com.pronto.auth.dto.AvailabilityRequest;
import com.pronto.auth.dto.AvailabilityResponse;
import com.pronto.auth.dto.ContactField;
import com.pronto.auth.dto.RegisterRequest;
import com.pronto.common.dto.FieldError;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.users.repository.UserRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * "Would registration accept this email address / phone number?", answered on its own so a
 * registration form can say so under the field instead of on the final screen.
 *
 * <h2>Why this endpoint did not exist before, and what changed</h2>
 *
 * <p>The rest of this package works hard to avoid becoming an account-existence oracle:
 * {@code AuthService#requestPasswordReset} answers identically whether or not the account exists,
 * {@code AuthAccountWriter#verifyPassword} burns a BCrypt hash on the unknown-identifier branch so
 * response time does not betray it, and {@code EmailNormalizer#mask} derives its masked value from
 * what was submitted rather than from a stored row. A "does this email exist" endpoint is, read
 * plainly, the opposite of all of that, and the omission was not an accident.
 *
 * <p><b>What makes it acceptable is that the oracle already exists, one door along.</b>
 * {@code POST /api/auth/register} answers {@code 409 DUPLICATE_EMAIL} and {@code 409
 * DUPLICATE_PHONE} as distinct codes — it has to, because a registration form cannot highlight the
 * right field otherwise — so anyone willing to send a registration attempt can already ask this
 * exact question and get this exact answer. This endpoint does not create the disclosure; it makes
 * the same disclosure cheaper. So the entire security question is <b>how much cheaper</b>, and that
 * is a rate-limiting question, answered in {@code auth.config.AuthWebConfig}:
 * {@code /api/auth/register} allows 10 probes per client per 10 minutes and this route allows 20
 * per 10 — twice the bandwidth, on an endpoint that costs the platform a single indexed lookup
 * instead of a BCrypt hash, an insert and an email. Enumerating a meaningful fraction of an email
 * namespace at 20 guesses per 10 minutes per source address is not a viable attack, and the source
 * address is resolved through {@code ClientIpResolver} rather than taken from a spoofable header.
 *
 * <p><b>Registration keeps its own duplicate checks, and they remain the authoritative ones.</b>
 * Nothing here is consulted by {@code AuthAccountWriter#createAccount}, which still performs its
 * pre-insert existence checks and still relies on {@code ux_users_email}/{@code ux_users_phone} to
 * settle the race those checks cannot. This answer is advisory by construction: it is true when it
 * is given and can be false a millisecond later, and a client that treated it as permission would
 * simply get the 409 it was trying to avoid.
 *
 * <h2>What this endpoint refuses to disclose</h2>
 *
 * <p>The response is {@code {field, available}} and nothing else — see
 * {@link AvailabilityResponse}. In particular {@code available = false} does not distinguish an
 * active account from one that never finished verifying from one that has been soft deleted,
 * because {@link #isTaken} asks exactly the question {@code createAccount} asks
 * ({@code existsByEmail}/{@code existsByPhone}, no {@code deleted_at} filter) and no more. Two
 * consequences, both intended: the answer cannot drift from what registration would actually do,
 * and it carries no account state.
 *
 * <p>Nothing here logs the submitted value.
 */
@Service
public class ContactAvailabilityService {

    /** The field name every error from this endpoint is reported against — the request has exactly
     *  one value in it, and the client already knows which of its own inputs it asked about. */
    private static final String VALUE_FIELD = "value";

    private final UserRepository userRepository;
    private final PhoneNumberNormalizer phoneNumberNormalizer;
    private final Validator validator;

    public ContactAvailabilityService(UserRepository userRepository,
                                       PhoneNumberNormalizer phoneNumberNormalizer,
                                       Validator validator) {
        this.userRepository = userRepository;
        this.phoneNumberNormalizer = phoneNumberNormalizer;
        this.validator = validator;
    }

    /**
     * @throws ApiException {@code VALIDATION_ERROR} when the value is not a well-formed address or
     *                      number at all. A malformed value has no availability — answering
     *                      {@code available = true} for {@code "not an email"} would invite a
     *                      client to march the user on to a registration that is about to fail.
     */
    @Transactional(readOnly = true)
    public AvailabilityResponse check(AvailabilityRequest request) {
        return new AvailabilityResponse(request.field(), !isTaken(request));
    }

    private boolean isTaken(AvailabilityRequest request) {
        if (request.field() == ContactField.PHONE) {
            // The normalizer is both the shape rule and the canonicalizer, and it throws
            // VALIDATION_ERROR naming the field when the number is unparseable, unassignable or
            // not a mobile line -- the same three refusals registration produces, from the same
            // call. That is the point of routing through it rather than re-testing the number
            // here: "is this a usable Israeli mobile number" is a question about a numbering plan,
            // and a second answer to it would go stale independently of the first.
            String phone = phoneNumberNormalizer.normalize(request.value(), VALUE_FIELD);
            return userRepository.existsByPhone(phone);
        }

        String email = EmailNormalizer.normalize(request.value());
        requireWellFormedEmail(email);
        return userRepository.existsByEmail(email);
    }

    /**
     * Applies {@code RegisterRequest.email}'s own constraints to the submitted value.
     *
     * <p>Validated against that record's declared metadata rather than against a copy of it here,
     * for the reason {@link PhoneNumberNormalizer} exists on the phone side: a second, hand-written
     * email rule in this class would be a rule that can disagree with the one registration
     * enforces, and the disagreement would present as "the form said it was fine and then the
     * server rejected it" — precisely the failure this whole endpoint was added to remove.
     */
    private void requireWellFormedEmail(String email) {
        Set<ConstraintViolation<RegisterRequest>> violations =
                validator.validateValue(RegisterRequest.class, "email", email);
        if (!violations.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                    List.of(new FieldError(VALUE_FIELD, "is not a valid email address")));
        }
    }
}
