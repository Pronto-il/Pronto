package com.pronto.common.exception;

import com.pronto.common.dto.ErrorBody;
import com.pronto.common.dto.ErrorResponse;
import com.pronto.common.dto.FieldError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Global error-response envelope, per {@code docs/architecture/api-contract.md} §1.
 * Applies to every controller in the application (not just {@code auth}/{@code users}) —
 * intended as the shared pattern for all future milestones' endpoints too.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Business-meaningful failures raised deliberately by service-layer code. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex, HttpServletRequest request) {
        return build(ex.getCode(), ex.getCode().getHttpStatus(), ex.getMessage(), ex.getDetails(), request);
    }

    /**
     * Unique-constraint violations that a pre-insert check could not prevent.
     *
     * <p>Production MS1. Registration checks "is this email already taken?" and then inserts, which
     * is a check-then-act: two simultaneous registrations of the same address both pass the check
     * and one loses at the index. The database was already handling that correctly — it is exactly
     * what {@code ux_users_email} is for — but the resulting
     * {@code DataIntegrityViolationException} had no handler, so it fell through to the catch-all
     * below and the loser received {@code 500 INTERNAL_ERROR}. Nothing had gone wrong on the server:
     * the caller simply tried to register an address somebody else was registering at that instant,
     * which is a {@code 409}.
     *
     * <p>The constraint name is what identifies which field lost, and it is read from the exception
     * message because that is where PostgreSQL puts it. Anything unrecognized keeps the generic
     * conflict response rather than guessing at a field — a wrong field name on an error is worse
     * than no field name. This is deliberately NOT a licence to weaken the constraints: the indexes
     * stay total and unique, and this handler only translates the outcome.
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(
            org.springframework.dao.DataIntegrityViolationException ex, HttpServletRequest request) {
        String detail = ex.getMostSpecificCause().getMessage();
        String constraint = detail == null ? "" : detail.toLowerCase();

        if (constraint.contains("ux_users_email")) {
            return build(ErrorCode.DUPLICATE_EMAIL, ErrorCode.DUPLICATE_EMAIL.getHttpStatus(),
                    "Email is already registered.", null, request);
        }
        if (constraint.contains("ux_users_phone")) {
            return build(ErrorCode.DUPLICATE_PHONE, ErrorCode.DUPLICATE_PHONE.getHttpStatus(),
                    "Phone number is already registered.", null, request);
        }

        // Anything else reaching here — a foreign key, a CHECK, a constraint added later without a
        // branch above — is a server-side bug: the service layer should have rejected the input
        // before the statement ran. Reported as 500 with the constraint logged, which is the honest
        // answer, rather than dressed up as a conflict the caller could act on.
        log.error("Unmapped data-integrity violation on {}: {}", request.getRequestURI(), detail, ex);
        return build(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred.", null, request);
    }

    /** {@code @Valid} bean-validation failures on {@code @RequestBody} DTOs. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex,
                                                            HttpServletRequest request) {
        List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return build(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST,
                "Request body failed validation.", fieldErrors, request);
    }

    /** Malformed/unparseable JSON request bodies. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex,
                                                            HttpServletRequest request) {
        return build(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST,
                "Malformed request body.", null, request);
    }

    /**
     * A multipart upload (e.g. {@code POST /api/storage/images}) exceeded
     * {@code spring.servlet.multipart.max-file-size}/{@code max-request-size}. See
     * {@code docs/architecture/api-contract-issues.md} §2.3.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSize(MaxUploadSizeExceededException ex,
                                                               HttpServletRequest request) {
        return build(ErrorCode.IMAGE_TOO_LARGE, ErrorCode.IMAGE_TOO_LARGE.getHttpStatus(),
                "Uploaded file exceeds the maximum allowed size.", null, request);
    }

    /** A required {@code multipart/form-data} part (e.g. {@code file}) was not sent. */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> handleMissingPart(MissingServletRequestPartException ex,
                                                             HttpServletRequest request) {
        return build(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST,
                "Required request part '" + ex.getRequestPartName() + "' is missing.", null, request);
    }

    /**
     * The request path matched a handler, but not for the HTTP method used (e.g.
     * {@code GET /api/auth/login}, which {@code auth.controller.AuthController} maps as
     * {@code POST}-only). Without this handler such a request fell through to
     * {@link #handleUnexpected} and came back as {@code 500 INTERNAL_ERROR}, which claims a
     * server fault for what is purely a caller-side wrong-verb mistake — and buried a
     * routine 4xx in the error log at ERROR level.
     *
     * <p>Sets the {@code Allow} header (required by RFC 9110 §15.5.6 for a 405) from the
     * methods the matched handler does support, via the response object rather than the
     * returned {@code ResponseEntity} — same idiom as {@code auth.security
     * .AuthRateLimitInterceptor}'s {@code Retry-After}, and it leaves {@link #build} shared
     * by every handler here untouched.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                                     HttpServletRequest request,
                                                                     HttpServletResponse response) {
        Set<HttpMethod> supported = ex.getSupportedHttpMethods();
        if (supported != null && !supported.isEmpty()) {
            response.setHeader(HttpHeaders.ALLOW,
                    supported.stream().map(HttpMethod::name).collect(Collectors.joining(", ")));
        }
        String message = "Request method '" + ex.getMethod() + "' is not supported for this endpoint."
                + (supported == null || supported.isEmpty() ? ""
                : " Supported: " + supported.stream().map(HttpMethod::name).collect(Collectors.joining(", ")) + ".");
        return build(ErrorCode.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED.getHttpStatus(),
                message, null, request);
    }

    /**
     * The request body's {@code Content-Type} is not one the matched handler can read (e.g.
     * {@code text/plain} posted to {@code POST /api/auth/login}, which consumes JSON).
     * Previously fell through to {@link #handleUnexpected} as {@code 500 INTERNAL_ERROR}.
     *
     * <p>Sets the {@code Accept} header to the media types the handler does consume, which is
     * what RFC 9110 §15.5.16 and Spring's own {@code ResponseEntityExceptionHandler} do for a
     * 415. The offending type is echoed from {@link HttpMediaTypeNotSupportedException
     * #getContentType()} — the <em>parsed</em> {@code MediaType}, never the raw client header
     * — so a malformed/hostile {@code Content-Type} cannot reach the response verbatim.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex,
                                                                        HttpServletRequest request,
                                                                        HttpServletResponse response) {
        List<MediaType> supported = ex.getSupportedMediaTypes();
        if (!supported.isEmpty()) {
            response.setHeader(HttpHeaders.ACCEPT, MediaType.toString(supported));
        }
        MediaType offending = ex.getContentType();
        String message = (offending == null
                ? "The request's Content-Type is missing or unparseable."
                : "Content-Type '" + offending + "' is not supported for this endpoint.")
                + (supported.isEmpty() ? "" : " Supported: " + MediaType.toString(supported) + ".");
        return build(ErrorCode.UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_MEDIA_TYPE.getHttpStatus(),
                message, null, request);
    }

    /**
     * No handler matched the request path. On Spring Boot 3.2+ this arrives as
     * {@link NoResourceFoundException} rather than {@code NoHandlerFoundException}: with
     * {@code spring.mvc.throw-exception-if-no-handler-found} left at its default, an unmatched
     * path falls through to the default static-resource handler, which throws this when no
     * such resource exists either. That made every unknown endpoint a {@code 500
     * INTERNAL_ERROR} (for an authenticated caller — an unauthenticated one is stopped a layer
     * earlier by {@code auth.security.JsonAuthenticationEntryPoint}, which is unchanged here).
     *
     * <p>The message is deliberately generic and does not echo the path back; the envelope's
     * {@code path} field already carries it, and this handler is reached by unauthenticated
     * probing of the public {@code /api/auth/**} space too.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex,
                                                                  HttpServletRequest request) {
        return build(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.getHttpStatus(),
                "The requested endpoint does not exist.", null, request);
    }

    /** Catch-all safety net — never leak a stack trace to the client. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {}", request.getRequestURI(), ex);
        return build(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred.", null, request);
    }

    private ResponseEntity<ErrorResponse> build(ErrorCode code, HttpStatus status, String message,
                                                 Object details, HttpServletRequest request) {
        ErrorResponse body = new ErrorResponse(Instant.now(), request.getRequestURI(),
                new ErrorBody(code.name(), message, details));
        return ResponseEntity.status(status).body(body);
    }
}
