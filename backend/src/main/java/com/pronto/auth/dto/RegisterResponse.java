package com.pronto.auth.dto;

import com.pronto.users.entity.UserRole;

public record RegisterResponse(Long userId, UserRole role, String email, boolean emailVerified) {
}
