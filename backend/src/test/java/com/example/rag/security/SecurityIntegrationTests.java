package com.example.rag.security;

import com.example.rag.persistence.DocumentAclEntryEntity;
import com.example.rag.persistence.DocumentAclEntryRepository;
import com.example.rag.persistence.DocumentEntity;
import com.example.rag.persistence.DocumentRepository;
import com.example.rag.persistence.DocumentRevisionEntity;
import com.example.rag.persistence.DocumentRevisionRepository;
import com.example.rag.persistence.DocumentVisibility;
import com.example.rag.persistence.RevisionStatus;
import com.example.rag.persistence.UserEntity;
import com.example.rag.persistence.UserRepository;
import com.example.rag.persistence.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SecurityIntegrationTests {

    private static final String PASSWORD = "local-pass-123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserRepository users;

    @Autowired
    private DocumentRepository documents;

    @Autowired
    private DocumentRevisionRepository revisions;

    @Autowired
    private DocumentAclEntryRepository aclEntries;

    @Test
    void loginChangesSessionIdAndLogoutEndsTheSession() throws Exception {
        createUser("session-admin", UserRole.ADMIN, true);

        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        CsrfSession initialCsrf = csrf(null);
        String initialSessionId = initialCsrf.session().getId();
        AuthenticatedSession authenticated = login(
                "session-admin", PASSWORD, initialCsrf, 200
        );

        assertThat(authenticated.session().getId()).isNotEqualTo(initialSessionId);
        mockMvc.perform(get("/api/v1/auth/me").session(authenticated.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("session-admin"))
                .andExpect(jsonPath("$.role").value("ADMIN"));

        mockMvc.perform(post("/api/v1/auth/logout")
                        .session(authenticated.session())
                        .header(authenticated.csrf().headerName(), authenticated.csrf().token()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginRejectsMissingAndForgedCsrfTokens() throws Exception {
        createUser("csrf-user", UserRole.USER, true);

        mockMvc.perform(post("/api/v1/auth/login")
                        .param("username", "csrf-user")
                        .param("password", PASSWORD))
                .andExpect(status().isForbidden());

        CsrfSession csrf = csrf(null);
        mockMvc.perform(post("/api/v1/auth/login")
                        .session(csrf.session())
                        .header(csrf.headerName(), "forged-token")
                        .param("username", "csrf-user")
                        .param("password", PASSWORD))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidCredentialsDoNotRevealAccountState() throws Exception {
        createUser("disabled-user", UserRole.USER, false);

        String unknownBody = failedLoginBody("missing-user", PASSWORD);
        String wrongPasswordBody = failedLoginBody("disabled-user", "wrong-password");
        String disabledBody = failedLoginBody("disabled-user", PASSWORD);

        assertThat(wrongPasswordBody).isEqualTo(unknownBody);
        assertThat(disabledBody).isEqualTo(unknownBody);
    }

    @Test
    void userManagementRequiresAdminAndNeverExposesPlaintextPasswords() throws Exception {
        createUser("management-admin", UserRole.ADMIN, true);
        createUser("management-user", UserRole.USER, true);

        AuthenticatedSession userSession = login("management-user", PASSWORD);
        mockMvc.perform(get("/api/v1/admin/users").session(userSession.session()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/evaluations/datasets")
                        .session(userSession.session()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/evaluations/multiformat-release")
                        .session(userSession.session()))
                .andExpect(status().isForbidden());

        AuthenticatedSession adminSession = login("management-admin", PASSWORD);
        String newPassword = "created-pass-123";
        MvcResult created = mockMvc.perform(post("/api/v1/admin/users")
                        .session(adminSession.session())
                        .header(adminSession.csrf().headerName(), adminSession.csrf().token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"new-user","password":"created-pass-123","role":"USER"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("new-user"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn();

        UUID newUserId = UUID.fromString(readJson(created).get("id").asText());
        UserEntity stored = users.findById(newUserId).orElseThrow();
        assertThat(stored.getPasswordHash()).isNotEqualTo(newPassword);
        assertThat(passwordEncoder.matches(newPassword, stored.getPasswordHash())).isTrue();

        mockMvc.perform(patch("/api/v1/admin/users/{id}", newUserId)
                        .session(adminSession.session())
                        .header(adminSession.csrf().headerName(), adminSession.csrf().token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\",\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));

        mockMvc.perform(post("/api/v1/admin/users/{id}/reset-password", newUserId)
                        .session(adminSession.session())
                        .header(adminSession.csrf().headerName(), adminSession.csrf().token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"replacement-pass-123\"}"))
                .andExpect(status().isNoContent());

        login("new-user", newPassword, csrf(null), 401);
        login("new-user", "replacement-pass-123", csrf(null), 200);
    }

    @Test
    void changingRoleExpiresEveryExistingSessionAndNewLoginUsesTheNewRole() throws Exception {
        UserEntity admin = createUser("role-admin", UserRole.ADMIN, true);
        UserEntity target = createUser("role-target", UserRole.USER, true);
        DocumentEntity restrictedDocument = documents.saveAndFlush(
                new DocumentEntity(admin, "Role-restricted handbook", DocumentVisibility.RESTRICTED)
        );
        createPublishedRevision(restrictedDocument, "role-restricted.pdf");
        aclEntries.saveAndFlush(new DocumentAclEntryEntity(restrictedDocument, target));
        AuthenticatedSession firstTargetSession = login(target.getUsername(), PASSWORD);
        AuthenticatedSession secondTargetSession = login(target.getUsername(), PASSWORD);
        AuthenticatedSession adminSession = login(admin.getUsername(), PASSWORD);

        mockMvc.perform(patch("/api/v1/admin/users/{id}", target.getId())
                        .session(adminSession.session())
                        .header(adminSession.csrf().headerName(), adminSession.csrf().token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));

        assertSessionExpired(firstTargetSession);
        assertSessionExpired(secondTargetSession);
        assertThat(aclEntries.existsByDocumentIdAndUserId(restrictedDocument.getId(), target.getId())).isFalse();
        assertThat(documents.findById(restrictedDocument.getId()).orElseThrow().getAclVersion()).isEqualTo(2);

        AuthenticatedSession promotedSession = login(target.getUsername(), PASSWORD);
        mockMvc.perform(get("/api/v1/admin/users")
                        .session(promotedSession.session()))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/admin/users/{id}", target.getId())
                        .session(adminSession.session())
                        .header(adminSession.csrf().headerName(), adminSession.csrf().token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"USER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("USER"));

        assertSessionExpired(promotedSession);
        mockMvc.perform(get("/api/v1/documents/{id}", restrictedDocument.getId())
                        .session(login(target.getUsername(), PASSWORD).session()))
                .andExpect(status().isNotFound());
        assertThat(documents.findById(restrictedDocument.getId()).orElseThrow().getAclVersion()).isEqualTo(2);
    }

    @Test
    void disablingUserExpiresExistingSessionAndPreventsNewLogin() throws Exception {
        UserEntity admin = createUser("disable-admin", UserRole.ADMIN, true);
        UserEntity target = createUser("disable-target", UserRole.USER, true);
        DocumentEntity restrictedDocument = documents.saveAndFlush(
                new DocumentEntity(admin, "Disabled-user handbook", DocumentVisibility.RESTRICTED)
        );
        createPublishedRevision(restrictedDocument, "disabled-user.pdf");
        aclEntries.saveAndFlush(new DocumentAclEntryEntity(restrictedDocument, target));
        AuthenticatedSession targetSession = login(target.getUsername(), PASSWORD);
        AuthenticatedSession adminSession = login(admin.getUsername(), PASSWORD);

        mockMvc.perform(patch("/api/v1/admin/users/{id}", target.getId())
                        .session(adminSession.session())
                        .header(adminSession.csrf().headerName(), adminSession.csrf().token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        assertSessionExpired(targetSession);
        login(target.getUsername(), PASSWORD, csrf(null), 401);
        assertThat(aclEntries.existsByDocumentIdAndUserId(restrictedDocument.getId(), target.getId())).isFalse();
        assertThat(documents.findById(restrictedDocument.getId()).orElseThrow().getAclVersion()).isEqualTo(2);

        mockMvc.perform(patch("/api/v1/admin/users/{id}", target.getId())
                        .session(adminSession.session())
                        .header(adminSession.csrf().headerName(), adminSession.csrf().token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        mockMvc.perform(get("/api/v1/documents/{id}", restrictedDocument.getId())
                        .session(login(target.getUsername(), PASSWORD).session()))
                .andExpect(status().isNotFound());
        assertThat(documents.findById(restrictedDocument.getId()).orElseThrow().getAclVersion()).isEqualTo(2);
    }

    @Test
    void resettingPasswordExpiresExistingSessionAndRequiresTheNewPassword() throws Exception {
        UserEntity admin = createUser("password-admin", UserRole.ADMIN, true);
        UserEntity target = createUser("password-target", UserRole.USER, true);
        AuthenticatedSession targetSession = login(target.getUsername(), PASSWORD);
        AuthenticatedSession adminSession = login(admin.getUsername(), PASSWORD);
        String replacementPassword = "replacement-pass-456";

        mockMvc.perform(post("/api/v1/admin/users/{id}/reset-password", target.getId())
                        .session(adminSession.session())
                        .header(adminSession.csrf().headerName(), adminSession.csrf().token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"replacement-pass-456\"}"))
                .andExpect(status().isNoContent());

        assertSessionExpired(targetSession);
        login(target.getUsername(), PASSWORD, csrf(null), 401);
        login(target.getUsername(), replacementPassword, csrf(null), 200);
    }

    @Test
    void lastEnabledAdminCannotBeDisabledOrDemoted() throws Exception {
        users.findAll().stream()
                .filter(user -> user.getRole() == UserRole.ADMIN && user.isEnabled())
                .forEach(user -> user.setEnabled(false));
        UserEntity admin = createUser("only-admin", UserRole.ADMIN, true);
        AuthenticatedSession session = login("only-admin", PASSWORD);

        mockMvc.perform(patch("/api/v1/admin/users/{id}", admin.getId())
                        .session(session.session())
                        .header(session.csrf().headerName(), session.csrf().token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_ADMIN"));

        mockMvc.perform(get("/api/v1/auth/me").session(session.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("only-admin"));
    }

    @Test
    void documentAclFiltersListsAndHidesRestrictedDocumentExistence() throws Exception {
        UserEntity admin = createUser("acl-admin", UserRole.ADMIN, true);
        UserEntity owner = createUser("acl-owner", UserRole.USER, true);
        UserEntity grantee = createUser("acl-grantee", UserRole.USER, true);
        createUser("acl-outsider", UserRole.USER, true);

        DocumentEntity publicDocument = documents.saveAndFlush(
                new DocumentEntity(owner, "Public handbook", DocumentVisibility.ALL_USERS)
        );
        DocumentEntity restrictedDocument = documents.saveAndFlush(
                new DocumentEntity(owner, "Restricted handbook", DocumentVisibility.RESTRICTED)
        );
        createPublishedRevision(publicDocument, "public-handbook.pdf");
        createPublishedRevision(restrictedDocument, "restricted-handbook.pdf");
        aclEntries.saveAndFlush(new DocumentAclEntryEntity(restrictedDocument, grantee));

        assertCanSeeDocument(login(admin.getUsername(), PASSWORD), restrictedDocument.getId());
        assertCanSeeDocument(login(owner.getUsername(), PASSWORD), restrictedDocument.getId());
        assertCanSeeDocument(login(grantee.getUsername(), PASSWORD), restrictedDocument.getId());

        AuthenticatedSession outsider = login("acl-outsider", PASSWORD);
        mockMvc.perform(get("/api/v1/documents").session(outsider.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(publicDocument.getId().toString()))
                .andExpect(jsonPath("$.items[1]").doesNotExist());
        mockMvc.perform(get("/api/v1/documents/{id}", restrictedDocument.getId())
                        .session(outsider.session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/documents/{id}", publicDocument.getId()))
                .andExpect(status().isUnauthorized());
    }

    private void assertCanSeeDocument(AuthenticatedSession session, UUID documentId) throws Exception {
        mockMvc.perform(get("/api/v1/documents/{id}", documentId).session(session.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.document.id").value(documentId.toString()));
    }

    private void createPublishedRevision(DocumentEntity document, String filename) {
        DocumentRevisionEntity revision = new DocumentRevisionEntity(
                document,
                1,
                "a".repeat(64),
                "test/" + document.getId() + "/" + filename,
                RevisionStatus.UPLOADED,
                filename,
                1,
                "application/pdf",
                null
        );
        revision.markProcessing();
        revision.markReady("test-parser");
        revisions.saveAndFlush(revision);
        document.publishRevision(revision);
        documents.saveAndFlush(document);
    }

    private void assertSessionExpired(AuthenticatedSession session) throws Exception {
        mockMvc.perform(get("/api/v1/auth/me").session(session.session()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
    }

    private UserEntity createUser(String username, UserRole role, boolean enabled) {
        var user = new UserEntity(username, passwordEncoder.encode(PASSWORD), role);
        user.setEnabled(enabled);
        return users.saveAndFlush(user);
    }

    private AuthenticatedSession login(String username, String password) throws Exception {
        return login(username, password, csrf(null), 200);
    }

    private AuthenticatedSession login(
            String username,
            String password,
            CsrfSession csrf,
            int expectedStatus
    ) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .session(csrf.session())
                        .header(csrf.headerName(), csrf.token())
                        .param("username", username)
                        .param("password", password))
                .andExpect(status().is(expectedStatus))
                .andReturn();

        if (expectedStatus != 200) {
            return new AuthenticatedSession(csrf.session(), csrf);
        }
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        return new AuthenticatedSession(session, csrf(session));
    }

    private String failedLoginBody(String username, String password) throws Exception {
        CsrfSession csrf = csrf(null);
        return mockMvc.perform(post("/api/v1/auth/login")
                        .session(csrf.session())
                        .header(csrf.headerName(), csrf.token())
                        .param("username", username)
                        .param("password", password))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private CsrfSession csrf(MockHttpSession existingSession) throws Exception {
        var request = get("/api/v1/auth/csrf");
        if (existingSession != null) {
            request.session(existingSession);
        }
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = readJson(result);
        return new CsrfSession(
                (MockHttpSession) result.getRequest().getSession(false),
                body.get("token").asText(),
                body.get("headerName").asText()
        );
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private record CsrfSession(MockHttpSession session, String token, String headerName) {
    }

    private record AuthenticatedSession(MockHttpSession session, CsrfSession csrf) {
    }
}
