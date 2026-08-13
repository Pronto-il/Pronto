package com.pronto.common.exception;

import com.pronto.common.dto.ErrorBody;
import com.pronto.common.dto.ErrorResponse;
import com.pronto.common.dto.FieldError;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.time.Instant;
import java.util.List;

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
