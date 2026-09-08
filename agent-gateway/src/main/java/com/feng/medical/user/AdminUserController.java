package com.feng.medical.user;
import com.feng.medical.security.AuthenticatedUser;
import com.feng.medical.security.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
@RestController @RequestMapping("/api/admin/users") public class AdminUserController {
 private final AdminUserService users; public AdminUserController(AdminUserService users){this.users=users;}
 @GetMapping public List<AdminUserView> list(){return users.list();}
 @PostMapping @ResponseStatus(HttpStatus.CREATED) public AdminUserView create(@AuthenticationPrincipal AuthenticatedUser actor,@Valid @RequestBody CreateUserRequest r){return users.create(actor.id(),r.username(),r.displayName(),r.password(),r.role());}
 @PostMapping("/{id}/status") public void status(@AuthenticationPrincipal AuthenticatedUser actor,@PathVariable UUID id,@RequestBody StatusRequest r){users.setActive(actor.id(),id,r.active());}
 @PostMapping("/{id}/reset-password") public void reset(@AuthenticationPrincipal AuthenticatedUser actor,@PathVariable UUID id,@Valid @RequestBody ResetPasswordRequest r){users.resetPassword(actor.id(),id,r.password());}
 public record CreateUserRequest(@NotBlank @Size(max=64) String username,@NotBlank @Size(max=128) String displayName,@NotBlank @Size(min=12,max=256) String password,@NotNull UserRole role){}
 public record StatusRequest(boolean active){}
 public record ResetPasswordRequest(@NotBlank @Size(min=12,max=256) String password){}
}
