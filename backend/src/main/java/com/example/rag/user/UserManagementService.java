package com.example.rag.user;

import com.example.rag.common.ApiException;
import com.example.rag.governance.GovernanceEventService;
import com.example.rag.graph.GraphRebuildRequestService;
import com.example.rag.persistence.DocumentAclEntryRepository;
import com.example.rag.persistence.DocumentEntity;
import com.example.rag.persistence.DocumentRepository;
import com.example.rag.persistence.UserEntity;
import com.example.rag.persistence.UserRepository;
import com.example.rag.persistence.UserRole;
import com.example.rag.security.PlatformUserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class UserManagementService {

    private final UserRepository users;
    private final DocumentRepository documents;
    private final DocumentAclEntryRepository aclEntries;
    private final PasswordEncoder passwordEncoder;
    private final SessionRegistry sessions;
    private final UserAccessGovernanceService access;
    private final GovernanceEventService events;
    private final GraphRebuildRequestService graphRebuilds;

    public UserManagementService(
            UserRepository users,
            DocumentRepository documents,
            DocumentAclEntryRepository aclEntries,
            PasswordEncoder passwordEncoder,
            SessionRegistry sessions,
            UserAccessGovernanceService access,
            GovernanceEventService events,
            GraphRebuildRequestService graphRebuilds
    ) {
        this.users = users;
        this.documents = documents;
        this.aclEntries = aclEntries;
        this.passwordEncoder = passwordEncoder;
        this.sessions = sessions;
        this.access = access;
        this.events = events;
        this.graphRebuilds = graphRebuilds;
    }

    @Transactional(readOnly = true)
    public List<UserResponse> listUsers() {
        List<UserEntity> all = users.findAllByOrderByUsernameAsc();
        Map<UUID, UserAccessContracts.AccessSummary> summaries = access.summaries(all);
        return all.stream()
                .map(user -> UserResponse.from(user, summaries.get(user.getId())))
                .toList();
    }

    @Transactional
    public UserResponse createUser(
            String rawUsername,
            String password,
            UserRole role,
            String reason,
            PlatformUserPrincipal actor
    ) {
        String username = AccountPolicy.normalizeUsername(rawUsername);
        AccountPolicy.validatePassword(password);
        if (users.existsByUsername(username)) {
            throw new ApiException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "用户名已存在");
        }

        var user = users.saveAndFlush(new UserEntity(username, passwordEncoder.encode(password), role));
        events.append(
                "ACCESS", "USER_CREATED", actor, "USER", user.getId().toString(), username,
                Map.of(), Map.of("role", role, "enabled", true),
                reasonOrDefault(reason, "管理员创建本地用户")
        );
        return access.user(user.getId()).user();
    }

    @Transactional
    public UserResponse updateUser(
            UUID id,
            UserRole role,
            Boolean enabled,
            Long expectedSecurityVersion,
            String confirmation,
            String reason,
            PlatformUserPrincipal actor
    ) {
        if (role == null && enabled == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_UPDATE", "至少需要修改一个字段");
        }

        UserEntity user = findUser(id);
        requireFactVersion(user, expectedSecurityVersion);
        requireConfirmation(confirmation, "UPDATE_USER");
        UserRole nextRole = role == null ? user.getRole() : role;
        boolean nextEnabled = enabled == null ? user.isEnabled() : enabled;
        protectLastAdmin(user, nextRole, nextEnabled);
        boolean securityChanged = nextRole != user.getRole() || nextEnabled != user.isEnabled();
        Map<String, Object> before = Map.of(
                "role", user.getRole(), "enabled", user.isEnabled(),
                "securityVersion", user.getSecurityVersion()
        );

        if (isAclEligible(user.getRole(), user.isEnabled()) && !isAclEligible(nextRole, nextEnabled)) {
            revokeDocumentGrants(user.getId());
        }

        user.changeSecurity(nextRole, nextEnabled);
        if (securityChanged) {
            users.flush();
            expireSessions(user.getId());
            events.append(
                    "ACCESS", "USER_SECURITY_CHANGED", actor,
                    "USER", user.getId().toString(), user.getUsername(),
                    before,
                    Map.of(
                            "role", user.getRole(), "enabled", user.isEnabled(),
                            "securityVersion", user.getSecurityVersion()
                    ),
                    reasonOrDefault(reason, "管理员更新用户安全状态")
            );
        }
        return access.user(user.getId()).user();
    }

    @Transactional
    public void resetPassword(
            UUID id,
            String password,
            Long expectedSecurityVersion,
            String confirmation,
            String reason,
            PlatformUserPrincipal actor
    ) {
        AccountPolicy.validatePassword(password);
        UserEntity user = findUser(id);
        requireFactVersion(user, expectedSecurityVersion);
        requireConfirmation(confirmation, "RESET_USER_PASSWORD");
        long previousVersion = user.getSecurityVersion();
        user.replacePasswordHash(passwordEncoder.encode(password));
        users.flush();
        expireSessions(user.getId());
        events.append(
                "ACCESS", "USER_PASSWORD_RESET", actor,
                "USER", user.getId().toString(), user.getUsername(),
                Map.of("securityVersion", previousVersion),
                Map.of("securityVersion", user.getSecurityVersion(), "sessionsExpired", true),
                reasonOrDefault(reason, "管理员重置本地用户密码")
        );
    }

    private UserEntity findUser(UUID id) {
        return users.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在"));
    }

    private void protectLastAdmin(UserEntity user, UserRole nextRole, boolean nextEnabled) {
        boolean removesActiveAdmin = user.getRole() == UserRole.ADMIN
                && user.isEnabled()
                && (nextRole != UserRole.ADMIN || !nextEnabled);
        if (removesActiveAdmin && users.countByRoleAndEnabledTrue(UserRole.ADMIN) <= 1) {
            throw new ApiException(HttpStatus.CONFLICT, "LAST_ADMIN", "不能禁用或降级最后一个启用的管理员");
        }
    }

    private void revokeDocumentGrants(UUID userId) {
        List<UUID> documentIds = aclEntries.findDocumentIdsByUserId(userId);
        if (documentIds.isEmpty()) {
            return;
        }
        List<DocumentEntity> changed = documents.findAllByIdForUpdate(documentIds);
        changed.forEach(DocumentEntity::markAclChanged);
        aclEntries.deleteAllByUserId(userId);
        aclEntries.flush();
        documents.flush();
        changed.forEach(document -> graphRebuilds.aclChanged(document.getId()));
    }

    private static boolean isAclEligible(UserRole role, boolean enabled) {
        return enabled && role == UserRole.USER;
    }

    private void expireSessions(UUID userId) {
        sessions.getAllPrincipals().stream()
                .filter(PlatformUserPrincipal.class::isInstance)
                .map(PlatformUserPrincipal.class::cast)
                .filter(principal -> principal.id().equals(userId))
                .flatMap(principal -> sessions.getAllSessions(principal, false).stream())
                .forEach(session -> session.expireNow());
    }

    private static void requireFactVersion(UserEntity user, Long expected) {
        if (expected != null && expected != user.getSecurityVersion()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "USER_SECURITY_VERSION_CONFLICT",
                    "用户安全状态已变化，请刷新后重试"
            );
        }
    }

    private static void requireConfirmation(String confirmation, String expected) {
        if (confirmation != null && !expected.equals(confirmation)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CONFIRMATION_INVALID", "确认字段不正确");
        }
    }

    private static String reasonOrDefault(String reason, String fallback) {
        return reason == null || reason.isBlank()
                ? fallback
                : GovernanceEventService.normalizeReason(reason);
    }
}
