package com.feng.medical.user;
import com.feng.medical.security.UserRole;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
@Repository class JdbcAdminUserRepository implements AdminUserRepository {
 private final JdbcClient jdbc; JdbcAdminUserRepository(JdbcClient jdbc){this.jdbc=jdbc;}
 public List<AdminUserView> list(){return jdbc.sql("SELECT id,username,display_name,role,status,auth_version FROM app_user ORDER BY created_at DESC").query((rs,row)->new AdminUserView(UUID.fromString(rs.getString("id")),rs.getString("username"),rs.getString("display_name"),UserRole.valueOf(rs.getString("role")),"ACTIVE".equals(rs.getString("status")),rs.getInt("auth_version"))).list();}
 public void create(UUID id,String username,String displayName,String passwordHash,UserRole role){jdbc.sql("INSERT INTO app_user (id,username,display_name,password_hash,role,status) VALUES (:id,:username,:displayName,:passwordHash,:role,'ACTIVE')").param("id",id.toString()).param("username",username).param("displayName",displayName).param("passwordHash",passwordHash).param("role",role.name()).update();}
 public boolean setActive(UUID id,boolean active){return jdbc.sql("UPDATE app_user SET status=:status,auth_version=auth_version+1 WHERE id=:id").param("id",id.toString()).param("status",active?"ACTIVE":"DISABLED").update()==1;}
 public boolean resetPassword(UUID id,String hash){return jdbc.sql("UPDATE app_user SET password_hash=:hash,auth_version=auth_version+1 WHERE id=:id").param("id",id.toString()).param("hash",hash).update()==1;}
}
