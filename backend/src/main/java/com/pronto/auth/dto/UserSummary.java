package com.pronto.auth.dto;

import com.pronto.users.entity.UserRole;

public record UserSummary(Long id, String fullName, String email, UserRole role) {
}
