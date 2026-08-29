package com.pronto.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * Enforces a required {@code role} on every request matching the route pattern(s) it's
 * registered against, in {@code DispatcherServlet}'s {@code preHandle} phase — i.e. before
 * Spring resolves {@code @Valid} request bodies or {@code @RequestParam}/multipart parts for
 * the matched handler method.
 *
 * <p><b>Bug this fixes:</b> {@code issues}/{@code storage}'s controllers originally called
 * {@link RoleGuard#requireRole} as the first line of the controller method *body*. But Spring
 * resolves argument binding/validation for the matched handler method — throwing
 * {@code MethodArgumentNotValidException} ({@code @Valid}) or
 * {@code MissingServletRequestPartException} (a missing multipart part) — strictly *before*
 * the method body ever runs. A request that was both wrong-role and had a malformed
 * body/missing part therefore surfaced as {@code 400 VALIDATION_ERROR} instead of the
 * contract-mandated {@code 403 FORBIDDEN} (see
 * {@code docs/architecture/api-contract-issues.md} §1's error-taxonomy precedence — the role
 * check must win). Registering this interceptor on the same routes closes that gap: a
 * {@code HandlerInterceptor}'s {@code preHandle} always runs before argument resolution,
 * regardless of which handler method on the route matched.
 *
 * <p>Reads the same {@link AuthenticatedUser} principal {@link RoleGuard} reads, from
 * {@code SecurityContextHolder} (populated earlier in the filter chain by
 * {@code auth.security.JwtAuthenticationFilter}) — not a parallel auth mechanism, just
 * enforced one phase earlier. Throwing {@link com.pronto.common.exception.ApiException} from
 * {@code preHandle} is caught by the exact same {@code GlobalExceptionHandler} every other
 * error in the app goes through: {@code DispatcherServlet} runs interceptor
 * {@code preHandle} calls inside the same try/catch that wraps handler invocation, so an
 * exception thrown here reaches {@code processHandlerException} (and therefore
 * {@code @RestControllerAdvice}) exactly like one thrown from a controller method body would.
 *
 * <p>Deliberately not {@code @PreAuthorize}/method security, and not a rule added to
 * {@code SecurityConfig}'s {@code authorizeHttpRequests} chain — either would require
 * changes to {@code auth.config.SecurityConfig}, out of bounds for the task that introduced
 * this class (see {@link RoleGuard}'s javadoc for the fuller rationale, unchanged: it still
 * applies to why this isn't declarative Spring Security).
 *
 * <p>Stateless and reusable — takes the required role as a constructor argument so each
 * owning domain package can register its own instance for its own routes (this class lives
 * in {@code common} purely as generic infrastructure; it has no built-in knowledge of which
 * routes require which role — see {@code issues.config.IssuesWebConfig} /
 * {@code storage.config.StorageWebConfig} for the registrations).
 *
 * <p><b>Optional HTTP-method scoping (added for {@code reviews}/Milestone 8-ish "profile,
 * reviews, favorites, matching" design).</b> Spring's {@code addPathPatterns} matches on URL
 * pattern only, not HTTP method — insufficient for a route like {@code POST /api/reviews}
 * (must be {@code CUSTOMER}-only) sharing an identical literal path with
 * {@code GET /api/reviews} (must stay either-role/ungated). The varargs
 * {@link #RoleRequiredInterceptor(String, String...)} constructor lets a registration opt
 * into checking only specific HTTP methods on a matched path, leaving any other method on
 * that same path untouched by this interceptor instance. The original single-arg
 * constructor is unchanged and still applies to every method on its registered path(s) —
 * every pre-existing registration in this codebase keeps that exact behavior.
 */
public class RoleRequiredInterceptor implements HandlerInterceptor {

    private final String requiredRole;
    private final Set<String> httpMethods;
    private final boolean allowAnonymous;

    /** Applies to every HTTP method on the registered path pattern(s) — original behavior. */
    public RoleRequiredInterceptor(String requiredRole) {
        this(requiredRole, new String[0]);
    }

    /**
     * Applies only to requests whose HTTP method is one of {@code httpMethods} (e.g.
     * {@code "POST"}, {@code "PUT"}, {@code "DELETE"}); any other method on the same
     * registered path is left ungated by this instance. Passing no methods reproduces the
     * single-arg constructor's "applies to every method" behavior.
     */
    public RoleRequiredInterceptor(String requiredRole, String... httpMethods) {
        this(requiredRole, false, httpMethods);
    }

    /**
     * <b>Opt-in: "if you are a signed-in user you must be {@code requiredRole}; being nobody is
     * somebody else's question."</b> With {@code allowAnonymous = true} a request carrying no
     * authenticated principal passes {@code preHandle} untouched, while a request carrying the
     * wrong role is refused exactly as before.
     *
     * <p>Introduced for {@code POST /api/storage/images} once guests could upload issue photos. The
     * route has two legitimate kinds of caller — a {@code CUSTOMER} JWT, or a signed guest-session
     * token — and only the first is a role question. The role gate must stay in {@code preHandle}
     * (that is the whole reason this class exists: it has to beat multipart argument resolution, or
     * a professional posting no {@code file} part gets {@code 400} instead of {@code 403}), so the
     * abstention has to be expressible here rather than by dropping the registration.
     *
     * <p><b>This does not open anything.</b> An anonymous request that this instance waves through
     * still has to satisfy {@code auth.security.UploadOwnerResolver#requireIdentified} in the
     * handler, which refuses a caller who proved no identity at all with {@code 401}. It is the
     * same "route-level gate abstains, the layer that can actually answer authorizes" split
     * {@code issues.config.IssuesWebConfig} already uses for its either-role
     * {@code GET /api/issues/{id}}. Every other registration in this codebase uses the constructors
     * above and is unchanged.
     */
    public RoleRequiredInterceptor(String requiredRole, boolean allowAnonymous, String... httpMethods) {
        this.requiredRole = requiredRole;
        this.allowAnonymous = allowAnonymous;
        this.httpMethods = Set.of(httpMethods);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!httpMethods.isEmpty() && !httpMethods.contains(request.getMethod())) {
            return true;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedUser principal =
                authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser authenticatedUser
                        ? authenticatedUser
                        : null;
        if (principal == null && allowAnonymous) {
            return true;
        }
        RoleGuard.requireRole(principal, requiredRole);
        return true;
    }
}
