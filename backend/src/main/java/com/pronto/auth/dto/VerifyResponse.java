package com.pronto.auth.dto;

public record VerifyResponse(Long userId, boolean emailVerified) {
}
