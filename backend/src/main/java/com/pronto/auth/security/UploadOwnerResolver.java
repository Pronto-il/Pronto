package com.pronto.auth.security;

import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.common.security.AuthenticatedUser;
import com.pronto.common.security.UploadOwner;
import org.springframework.stereotype.Component;

/**
 * "Given a verified JWT principal and/or a guest-session header, whose images are these?" — the
 * single implementation of that question, for the same reason {@link JwtPrincipalResolver} is the
 * single implementation of its own.
 *
 * <p>Two routes ask it ({@code POST /api/storage/images} and
 * {@code POST /api/storage/images/presigned-urls}), plus the two {@code /api/issues} routes that
 * carry {@code imageKeys}. A second copy of "principal, else verified guest token, else refuse"
 * is exactly the kind of duplication where one copy later forgets to verify the token.
 *
 * <p><b>Neither input is trusted as given.</b> {@code principal} has already been through
 * {@link JwtPrincipalResolver} (signature, expiry, and the per-request revocation check) by the
 * time Spring Security hands it over; the header is verified here, by
 * {@link GuestSessionTokenService#resolveGuestId}, and an unverifiable one is treated as absent
 * rather than as an error — a stale token in a returning visitor's {@code localStorage} is an
 * ordinary occurrence, not an attack, and the outcome they need is "you are not a guest with a
 * namespace", not a failed request.
 */
@Component
public class UploadOwnerResolver {

    private final GuestSessionTokenService guestSessionTokenService;

    public UploadOwnerResolver(GuestSessionTokenService guestSessionTokenService) {
        this.guestSessionTokenService = guestSessionTokenService;
    }

    /**
     * Resolves whatever identities the request actually proved. Both may be present (a guest who
     * registered mid-flow), one may be, or neither.
     */
    public UploadOwner resolve(AuthenticatedUser principal, String guestSessionToken) {
        Long customerId = principal == null ? null : principal.id();
        String guestId = guestSessionTokenService.resolveGuestId(guestSessionToken).orElse(null);
        return new UploadOwner(customerId, guestId);
    }

    /**
     * As {@link #resolve}, but refuses an anonymous caller outright. This is the check that keeps
     * {@code POST /api/storage/images} from being an open upload endpoint now that
     * {@code auth.config.SecurityConfig} no longer 401s it at the filter layer: authorization did
     * not disappear, it moved here, exactly as backend MS9 moved image-<em>read</em> authorization
     * from the filter chain to URL-issuance time.
     *
     * @throws ApiException {@code 401 UNAUTHORIZED} when the caller presented neither a valid JWT
     *                       nor a valid guest-session token.
     */
    public UploadOwner requireIdentified(AuthenticatedUser principal, String guestSessionToken) {
        UploadOwner owner = resolve(principal, guestSessionToken);
        if (owner.isAnonymous()) {
            throw new ApiException(ErrorCode.UNAUTHORIZED,
                    "Missing, invalid, or expired authentication token.");
        }
        return owner;
    }
}
