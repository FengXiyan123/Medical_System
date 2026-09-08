package com.feng.medical.user;
import com.feng.medical.audit.AuditService;
import com.feng.medical.security.UserRole;
import java.util.List;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
@Service public class AdminUserService {
 private final AdminUserRepository users; private final PasswordEncoder passwords; private final AuditService audit;
 public AdminUserService(AdminUserRepository users,PasswordEncoder passwords,AuditService audit){this.users=users;this.passwords=passwords;this.audit=audit;}
 public List<AdminUserView> list(){return users.list();}
 public AdminUserView create(UUID actor,String username,String displayName,String password,UserRole role){UUID id=UUID.randomUUID();users.create(id,username,displayName,passwords.encode(password),role);audit.record(actor,"USER_CREATED","USER",id,null,"{\"username\":\""+username+"\",\"role\":\""+role+"\"}");return new AdminUserView(id,username,displayName,role,true,0);}
 public void setActive(UUID actor,UUID id,boolean active){if(!users.setActive(id,active))throw new IllegalArgumentException("用户不存在");audit.record(actor,active?"USER_ENABLED":"USER_DISABLED","USER",id,null,"{}");}
 public void resetPassword(UUID actor,UUID id,String password){if(!users.resetPassword(id,passwords.encode(password)))throw new IllegalArgumentException("用户不存在");audit.record(actor,"USER_PASSWORD_RESET","USER",id,null,"{\"password\":\"supplied\"}");}
}
