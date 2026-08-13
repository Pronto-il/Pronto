package com.pronto.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pronto.common.dto.ErrorBody;
import com.pronto.common.dto.ErrorResponse;
import com.pronto.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

/**
 * Writes the standard error envelope (see {@code docs/architecture/api-contract.md} §1)
 * for requests rejected by Spring Security before ever reaching a controller — i.e.
 * missing/invalid JWT on an auth-required endpoint. Always {@code 401 UNAUTHORIZED}, since
 * {@code GlobalExceptionHandler} (a {@code @RestControllerAdvice}) never sees exceptions
 * thrown from the security filter chain.
 */
@Component
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public JsonAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                          AuthenticationException authException) throws IOException {
        response.setStatus(ErrorCode.UNAUTHORIZED.getHttpStatus().value());
        response.setContentType("application/json;charset=UTF-8");
        ErrorResponse body = new ErrorResponse(Instant.now(), request.getRequestURI(),
                new ErrorBody(ErrorCode.UNAUTHORIZED.name(),
                        "Missing, invalid, or expired authentication token.", null));
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
