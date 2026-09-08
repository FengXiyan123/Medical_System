package com.feng.medical.security;

import java.util.UUID;

public record AuthenticatedUser(UUID id, String username, UserRole role, int authVersion) {
}
