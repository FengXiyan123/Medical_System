package com.feng.medical.user;

import com.feng.medical.security.UserRole;
import java.util.UUID;

public record UserAccount(UUID id, String username, String passwordHash, UserRole role, boolean active, int authVersion) {}
