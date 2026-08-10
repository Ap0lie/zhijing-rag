package com.example.rag.user;

import com.example.rag.persistence.UserRole;
import com.example.rag.security.PlatformUserPrincipal;
import com.example.rag.user.UserAccessContracts.DocumentGrantPage;
import com.example.rag.user.UserAccessContracts.DocumentGrantUpdateRequest;
import com.example.rag.user.UserAccessContracts.DocumentGrantUpdateResult;
import com.example.rag.user.UserAccessContracts.UserAccessView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final UserManagementService service;
    private final UserAccessGovernanceService access;

    public AdminUserController(UserManagementService service, UserAccessGovernanceService access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping
    List<UserResponse> list() {
        return service.listUsers();
    }

    @PostMapping
    ResponseEntity<UserResponse> create(
            @Valid @RequestBody CreateUserRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal actor
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createUser(
                        request.username(), request.password(), request.role(), request.reason(), actor
                ));
    }

    @PatchMapping("/{id}")
    UserResponse update(
            @PathVariable UUID id,
            @RequestBody UpdateUserRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal actor
    ) {
        return service.updateUser(
                id, request.role(), request.enabled(), request.expectedSecurityVersion(),
                request.confirmation(), request.reason(), actor
        );
    }

    @PostMapping("/{id}/reset-password")
    ResponseEntity<Void> resetPassword(
            @PathVariable UUID id,
            @Valid @RequestBody ResetPasswordRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal actor
    ) {
        service.resetPassword(
                id, request.password(), request.expectedSecurityVersion(),
                request.confirmation(), request.reason(), actor
        );
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/access")
    UserAccessView access(@PathVariable UUID id) {
        return access.user(id);
    }

    @GetMapping("/{id}/document-grants")
    DocumentGrantPage grants(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return access.grants(id, query, page, size);
    }

    @PostMapping("/{id}/document-grants")
    DocumentGrantUpdateResult updateGrants(
            @PathVariable UUID id,
            @Valid @RequestBody DocumentGrantUpdateRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal actor
    ) {
        return access.updateGrants(id, request, actor);
    }

    public record CreateUserRequest(
            @NotBlank(message = "请输入用户名")
            @Pattern(
                    regexp = "^[a-zA-Z0-9][a-zA-Z0-9._-]{2,49}$",
                    message = "用户名需为 3-50 位字母、数字、点、下划线或连字符"
            )
            String username,
            @NotBlank(message = "请输入初始密码") String password,
            @NotNull(message = "请选择角色") UserRole role,
            String reason
    ) {
    }

    public record UpdateUserRequest(
            UserRole role,
            Boolean enabled,
            Long expectedSecurityVersion,
            String confirmation,
            String reason
    ) {
    }

    public record ResetPasswordRequest(
            @NotBlank(message = "请输入新密码") String password,
            Long expectedSecurityVersion,
            String confirmation,
            String reason
    ) {
    }
}
