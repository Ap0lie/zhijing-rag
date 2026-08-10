package com.example.rag.user;

import com.example.rag.common.ApiException;
import com.example.rag.governance.AdminAuditService;
import com.example.rag.governance.GovernanceContracts.OperationImpactRequest;
import com.example.rag.governance.OperationImpactService;
import com.example.rag.persistence.DocumentEntity;
import com.example.rag.persistence.DocumentRepository;
import com.example.rag.persistence.DocumentRevisionEntity;
import com.example.rag.persistence.DocumentRevisionRepository;
import com.example.rag.persistence.DocumentVisibility;
import com.example.rag.persistence.RevisionStatus;
import com.example.rag.persistence.UserEntity;
import com.example.rag.persistence.UserRepository;
import com.example.rag.persistence.UserRole;
import com.example.rag.security.PlatformUserPrincipal;
import com.example.rag.user.UserAccessContracts.DocumentGrantChange;
import com.example.rag.user.UserAccessContracts.DocumentGrantUpdateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "rag.evaluation.worker-enabled=false",
        "rag.chat.llm.enabled=false"
})
@AutoConfigureMockMvc
@Transactional
class Phase19BGovernanceIntegrationTests {

    @Autowired private UserAccessGovernanceService access;
    @Autowired private OperationImpactService impacts;
    @Autowired private AdminAuditService audit;
    @Autowired private UserRepository users;
    @Autowired private DocumentRepository documents;
    @Autowired private DocumentRevisionRepository revisions;
    @Autowired private MockMvc mockMvc;

    @Test
    void grantsAreVersionedIdempotentAuditedAndImmediatelyReflected() {
        UserEntity admin = users.saveAndFlush(new UserEntity("phase19b-admin", "hash", UserRole.ADMIN));
        UserEntity owner = users.saveAndFlush(new UserEntity("phase19b-owner", "hash", UserRole.USER));
        UserEntity target = users.saveAndFlush(new UserEntity("phase19b-target", "hash", UserRole.USER));
        DocumentEntity publicDocument = document(owner, "Phase 19B public", DocumentVisibility.ALL_USERS);
        DocumentEntity restrictedDocument = document(owner, "Phase 19B restricted", DocumentVisibility.RESTRICTED);
        PlatformUserPrincipal actor = PlatformUserPrincipal.from(admin);

        var initial = access.user(target.getId());
        assertThat(initial.access().publicDocuments()).isEqualTo(1);
        assertThat(initial.access().explicitGrants()).isZero();
        assertThat(initial.access().totalDocuments()).isEqualTo(1);

        var page = access.grants(target.getId(), "restricted", 0, 20);
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.documentId()).isEqualTo(restrictedDocument.getId());
            assertThat(item.accessSource()).isEqualTo("NONE");
            assertThat(item.editable()).isTrue();
        });

        String key = "phase19b:" + UUID.randomUUID();
        var request = new DocumentGrantUpdateRequest(
                List.of(new DocumentGrantChange(
                        restrictedDocument.getId(), true, restrictedDocument.getAclVersion()
                )),
                key,
                "批准用户读取受限测试文档",
                "UPDATE_DOCUMENT_GRANTS",
                target.getSecurityVersion()
        );
        var updated = access.updateGrants(target.getId(), request, actor);
        assertThat(updated.replayed()).isFalse();
        assertThat(updated.user().access().explicitGrants()).isEqualTo(1);
        assertThat(updated.user().access().totalDocuments()).isEqualTo(2);

        var replayed = access.updateGrants(target.getId(), request, actor);
        assertThat(replayed.replayed()).isTrue();
        assertThat(replayed.changedDocumentIds()).isEmpty();

        var stale = new DocumentGrantUpdateRequest(
                List.of(new DocumentGrantChange(restrictedDocument.getId(), false, 1)),
                "phase19b:" + UUID.randomUUID(),
                "尝试使用过期权限版本撤权",
                "UPDATE_DOCUMENT_GRANTS",
                target.getSecurityVersion()
        );
        assertThatThrownBy(() -> access.updateGrants(target.getId(), stale, actor))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("ACL_VERSION_CONFLICT"));

        var events = audit.page("ACCESS", null, "phase19b-admin", target.getId().toString(),
                null, null, null, 20);
        assertThat(events.items()).anySatisfy(event -> {
            assertThat(event.action()).isEqualTo("DOCUMENT_GRANTS_CHANGED");
            assertThat(event.objectId()).isEqualTo(target.getId().toString());
            assertThat(event.reason()).doesNotContain("hash");
        });

        var impact = impacts.preflight(new OperationImpactRequest(
                "DOCUMENT_GRANT_BATCH", target.getId().toString(), Map.of("changeCount", 1)
        ));
        assertThat(impact.confirmation()).isEqualTo("UPDATE_DOCUMENT_GRANTS");
        assertThat(impact.notAffected()).anyMatch(value -> value.contains("公共文档"));
        assertThat(publicDocument.getVisibility()).isEqualTo(DocumentVisibility.ALL_USERS);
    }

    @Test
    void ordinaryUsersCannotReadGovernanceApis() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-events").with(user("reader").roles("USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/users/{id}/access", UUID.randomUUID())
                        .with(user("reader").roles("USER")))
                .andExpect(status().isForbidden());
    }

    private DocumentEntity document(UserEntity owner, String title, DocumentVisibility visibility) {
        DocumentEntity document = documents.saveAndFlush(new DocumentEntity(owner, title, visibility));
        DocumentRevisionEntity revision = new DocumentRevisionEntity(
                document, 1, "a".repeat(64), "phase19b/" + UUID.randomUUID(),
                RevisionStatus.UPLOADED, "phase19b.pdf", 1, "application/pdf", null
        );
        revision.markProcessing();
        revision.markReady("phase19b-parser");
        revisions.saveAndFlush(revision);
        document.publishRevision(revision);
        return documents.saveAndFlush(document);
    }
}
