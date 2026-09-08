package com.feng.medical.user;
import com.feng.medical.security.UserRole;
import java.util.UUID;
public record AdminUserView(UUID id, String username, String displayName, UserRole role, boolean active, int authVersion) { }
