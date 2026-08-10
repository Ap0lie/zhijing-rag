package com.example.rag.chat;

import com.example.rag.persistence.UserEntity;
import com.example.rag.persistence.UserRepository;
import com.example.rag.security.PlatformUserPrincipal;
import org.springframework.stereotype.Component;

@Component
class ChatUserGuard {

    private final UserRepository users;

    ChatUserGuard(UserRepository users) {
        this.users = users;
    }

    void requireCurrent(PlatformUserPrincipal principal) {
        UserEntity current = users.findById(principal.id())
                .orElseThrow(ChatUserGuard::changed);
        if (!current.isEnabled()
                || current.getRole() != principal.role()
                || !current.getPasswordHash().equals(principal.getPassword())) {
            throw changed();
        }
    }

    private static ChatWorkflowException changed() {
        return new ChatWorkflowException(
                "ACCOUNT_CHANGED",
                "账户权限已变化，请重新登录"
        );
    }
}
