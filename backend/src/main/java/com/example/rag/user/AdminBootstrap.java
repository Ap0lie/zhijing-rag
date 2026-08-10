package com.example.rag.user;

import com.example.rag.persistence.UserEntity;
import com.example.rag.persistence.UserRepository;
import com.example.rag.persistence.UserRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Component
public class AdminBootstrap implements ApplicationRunner {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String configuredUsername;
    private final String configuredPassword;

    public AdminBootstrap(
            UserRepository users,
            PasswordEncoder passwordEncoder,
            @Value("${rag.bootstrap-admin.username:}") String configuredUsername,
            @Value("${rag.bootstrap-admin.password:}") String configuredPassword
    ) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.configuredUsername = configuredUsername;
        this.configuredPassword = configuredPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        boolean hasUsername = StringUtils.hasText(configuredUsername);
        boolean hasPassword = StringUtils.hasText(configuredPassword);
        if (!hasUsername && !hasPassword) {
            return;
        }
        if (!hasUsername || !hasPassword) {
            throw new IllegalStateException("Both bootstrap admin environment variables must be configured");
        }

        String username = AccountPolicy.normalizeUsername(configuredUsername);
        AccountPolicy.validatePassword(configuredPassword);
        users.findByUsername(username).ifPresentOrElse(existing -> {
            if (existing.getRole() != UserRole.ADMIN) {
                throw new IllegalStateException("Bootstrap admin username belongs to a non-admin account");
            }
        }, () -> users.save(new UserEntity(
                username,
                passwordEncoder.encode(configuredPassword),
                UserRole.ADMIN
        )));
    }
}
