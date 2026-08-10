package com.example.rag.user;

import com.example.rag.persistence.UserRepository;
import com.example.rag.persistence.UserRole;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "rag.bootstrap-admin.username=bootstrap-admin",
        "rag.bootstrap-admin.password=bootstrap-pass-123"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminBootstrapIntegrationTests {

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AdminBootstrap bootstrap;

    @Test
    void bootstrapCreatesOneHashedAdminAndIsIdempotent() throws Exception {
        var initial = users.findByUsername("bootstrap-admin").orElseThrow();
        String initialHash = initial.getPasswordHash();

        assertThat(initial.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(initialHash).isNotEqualTo("bootstrap-pass-123");
        assertThat(passwordEncoder.matches("bootstrap-pass-123", initialHash)).isTrue();

        bootstrap.run(null);

        var repeated = users.findByUsername("bootstrap-admin").orElseThrow();
        assertThat(repeated.getPasswordHash()).isEqualTo(initialHash);
        assertThat(users.findAll().stream()
                .filter(user -> user.getUsername().equals("bootstrap-admin")))
                .hasSize(1);
    }

    @AfterAll
    void removeBootstrapTestAccount() {
        users.findByUsername("bootstrap-admin").ifPresent(users::delete);
    }
}
