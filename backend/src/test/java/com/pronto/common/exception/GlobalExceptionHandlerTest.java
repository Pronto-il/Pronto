package com.pronto.common.exception;

import com.pronto.common.dto.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GlobalExceptionHandler}'s framework-level failure mappings. Exercised directly
 * against the handler rather than through a {@code MockMvc}/{@code @WebMvcTest} slice, which
 * this codebase's test suite does not use anywhere (same reasoning as
 * {@code auth.dto.DefaultAddressRequestTest}).
 *
 * <p>Regression guard for three bugs found while tracing the login flow — wrong verb (405),
 * wrong {@code Content-Type} (415) and unknown endpoint (404) each used to fall through to
 * the {@code Exception} catch-all and return {@code 500 INTERNAL_ERROR}.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void wrongHttpMethodReturns405WithMethodNotAllowedCode() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        var ex = new HttpRequestMethodNotSupportedException("GET", Set.of("POST"));

        ResponseEntity<ErrorResponse> result = handler.handleMethodNotSupported(ex, request, response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().error().code()).isEqualTo(ErrorCode.METHOD_NOT_ALLOWED.name());
        assertThat(result.getBody().error().code()).isNotEqualTo(ErrorCode.INTERNAL_ERROR.name());
        assertThat(result.getBody().path()).isEqualTo("/api/auth/login");
        assertThat(result.getBody().error().message()).contains("GET").contains("POST");
        assertThat(response.getHeader(HttpHeaders.ALLOW)).isEqualTo("POST");
    }

    @Test
    void allowHeaderIsOmittedWhenSupportedMethodsAreUnknown() {
        MockHttpServletRequest request = new MockHttpServletRequest("TRACE", "/api/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        var ex = new HttpRequestMethodNotSupportedException("TRACE");

        ResponseEntity<ErrorResponse> result = handler.handleMethodNotSupported(ex, request, response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeader(HttpHeaders.ALLOW)).isNull();
    }

    @Test
    void unsupportedContentTypeReturns415WithAcceptHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        var ex = new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<ErrorResponse> result = handler.handleMediaTypeNotSupported(ex, request, response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().error().code()).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE.name());
        assertThat(result.getBody().error().code()).isNotEqualTo(ErrorCode.INTERNAL_ERROR.name());
        assertThat(result.getBody().error().message())
                .isEqualTo("Content-Type 'text/plain' is not supported for this endpoint. Supported: application/json.");
        assertThat(response.getHeader(HttpHeaders.ACCEPT)).isEqualTo("application/json");
    }

    /** A stack trace or internal class name must never reach the client. */
    @Test
    void unsupportedContentTypeMessageLeaksNoInternals() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        var ex = new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<ErrorResponse> result = handler.handleMediaTypeNotSupported(ex, request, response);

        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().error().message())
                .doesNotContain("org.springframework").doesNotContain("Exception").doesNotContain("com.pronto");
        assertThat(result.getBody().error().details()).isNull();
    }

    @Test
    void unknownEndpointReturns404NotFound() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/this-route-does-not-exist");
        var ex = new NoResourceFoundException(HttpMethod.GET, "/api/this-route-does-not-exist");

        ResponseEntity<ErrorResponse> result = handler.handleNoResourceFound(ex, request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().error().code()).isEqualTo(ErrorCode.NOT_FOUND.name());
        assertThat(result.getBody().error().code()).isNotEqualTo(ErrorCode.INTERNAL_ERROR.name());
        assertThat(result.getBody().error().message()).isEqualTo("The requested endpoint does not exist.");
        assertThat(result.getBody().path()).isEqualTo("/api/this-route-does-not-exist");
    }

    /**
     * The 404 message must not echo the requested path back into the body — this handler is
     * reachable by unauthenticated probing of the public {@code /api/auth/**} space.
     */
    @Test
    void unknownEndpointMessageDoesNotEchoTheRequestedPath() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/<script>alert(1)</script>");
        var ex = new NoResourceFoundException(HttpMethod.GET, "/api/<script>alert(1)</script>");

        ResponseEntity<ErrorResponse> result = handler.handleNoResourceFound(ex, request);

        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().error().message()).doesNotContain("script");
    }

    /** A genuine unexpected failure must still be a 500 — the catch-all is not weakened. */
    @Test
    void genuineUnexpectedExceptionStillReturns500InternalError() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");

        ResponseEntity<ErrorResponse> result =
                handler.handleUnexpected(new IllegalStateException("boom"), request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().error().code()).isEqualTo(ErrorCode.INTERNAL_ERROR.name());
        assertThat(result.getBody().error().message()).isEqualTo("An unexpected error occurred.");
        assertThat(result.getBody().error().message()).doesNotContain("boom");
    }

    /**
     * The new handlers are registered for distinct exception types, so deliberate
     * service-layer failures — the authentication ones above all — must be untouched.
     */
    @Test
    void deliberateApiExceptionsKeepTheirOwnStatusAndCode() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");

        for (ErrorCode code : Set.of(ErrorCode.INVALID_CREDENTIALS, ErrorCode.EMAIL_NOT_VERIFIED,
                ErrorCode.ACCOUNT_LOCKED, ErrorCode.RATE_LIMITED)) {
            ResponseEntity<ErrorResponse> result =
                    handler.handleApiException(new ApiException(code, "boom"), request);

            assertThat(result.getStatusCode()).isEqualTo(code.getHttpStatus());
            assertThat(result.getBody()).isNotNull();
            assertThat(result.getBody().error().code()).isEqualTo(code.name());
        }
    }

    @Test
    void errorCodeStatusesForAuthFailuresAreUnchanged() {
        assertThat(ErrorCode.INVALID_CREDENTIALS.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ErrorCode.EMAIL_NOT_VERIFIED.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ErrorCode.ACCOUNT_LOCKED.getHttpStatus()).isEqualTo(HttpStatus.LOCKED);
        assertThat(ErrorCode.RATE_LIMITED.getHttpStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(ErrorCode.METHOD_NOT_ALLOWED.getHttpStatus()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(ErrorCode.UNSUPPORTED_MEDIA_TYPE.getHttpStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(ErrorCode.NOT_FOUND.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * Domain code raising {@code ApiException(NOT_FOUND, ...)} for a missing entity must keep
     * its own message — the unmatched-route handler reuses the code, not the copy.
     */
    @Test
    void domainNotFoundKeepsItsOwnMessage() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders/999");

        ResponseEntity<ErrorResponse> result =
                handler.handleApiException(new ApiException(ErrorCode.NOT_FOUND, "Order not found."), request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().error().code()).isEqualTo(ErrorCode.NOT_FOUND.name());
        assertThat(result.getBody().error().message()).isEqualTo("Order not found.");
    }

    @Test
    void httpMethodSetIsResolvedFromTheSupportedMethodNames() {
        var ex = new HttpRequestMethodNotSupportedException("GET", Set.of("POST", "PUT"));
        assertThat(ex.getSupportedHttpMethods()).containsExactlyInAnyOrder(HttpMethod.POST, HttpMethod.PUT);
    }
}
