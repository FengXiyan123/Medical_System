package com.feng.medical.user;
import com.feng.medical.security.UserRole;
import java.util.List;
import java.util.UUID;
public interface AdminUserRepository { List<AdminUserView> list(); void create(UUID id,String username,String displayName,String passwordHash,UserRole role); boolean setActive(UUID id,boolean active); boolean resetPassword(UUID id,String passwordHash); }
