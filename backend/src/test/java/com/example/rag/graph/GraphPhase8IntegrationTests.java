package com.example.rag.graph;

import com.example.rag.persistence.UserEntity;
import com.example.rag.persistence.UserRepository;
import com.example.rag.persistence.UserRole;
import com.example.rag.security.PlatformUserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "rag.graph.enabled=true",
        "rag.graph.worker-enabled=false",
        "rag.graph.extraction.enabled=false"
})
@AutoConfigureMockMvc
class GraphPhase8IntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;

    private UserEntity admin;

    @AfterEach
    void deleteAdmin() {
        if (admin != null) {
            users.deleteById(admin.getId());
        }
    }

    @Test
    void v14CreatesFrozenEvidenceAnchoredGraphSchema() {
        Integer migration = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM flyway_schema_history
                WHERE version IN ('13', '14') AND success
                """,
                Integer.class
        );
        assertThat(migration).isEqualTo(2);

        assertThat(jdbc.queryForList(
                """
                SELECT tablename
                FROM pg_tables
                WHERE schemaname = current_schema()
                  AND tablename IN (
                    'graph_manifests',
                    'graph_entities',
                    'graph_generation_sources',
                    'graph_entity_alias_evidence',
                    'graph_entity_mentions',
                    'graph_relationships',
                    'graph_relationship_evidence',
                    'graph_communities',
                    'graph_community_claims'
                  )
                """,
                String.class
        )).hasSize(9);

        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM pg_indexes
                WHERE schemaname = current_schema()
                  AND indexname IN (
                    'ix_graph_entity_mentions_document',
                    'ix_graph_entities_name_prefix',
                    'ix_graph_aliases_name_prefix',
                    'ix_graph_relationship_evidence_document',
                    'ix_graph_adjacency_traverse'
                  )
                """,
                Integer.class
        )).isEqualTo(5);
    }

    @Test
    void graphConfigIsAppendOnlyAndRequiresCurrentDatabaseAdmin() throws Exception {
        PlatformUserPrincipal principal = admin();
        String version = "graph-test-" + UUID.randomUUID();
        String request = json.writeValueAsString(Map.of(
                "version", version,
                "extractionModel", "local-test-model",
                "extractionRevision", "revision-1",
                "confirmation", "CREATE",
                "reason", "Phase 8 integration verification"
        ));

        mockMvc.perform(post("/api/v1/admin/graph/configs")
                        .with(user(principal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(version))
                .andExpect(jsonPath("$.runtimeCompatible").value(false));

        mockMvc.perform(get("/api/v1/admin/graph")
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.extraction.enabled").value(false));

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE graph_configs SET reason = 'changed' WHERE version = ?",
                version
        )).isInstanceOf(DataAccessException.class);

        jdbc.update(
                "UPDATE users SET role = 'USER' WHERE id = ?",
                principal.id()
        );
        mockMvc.perform(get("/api/v1/admin/graph")
                        .with(user(principal)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code")
                        .value("GRAPH_ADMIN_REQUIRED"));
    }

    @Test
    void publishEndpointDoesNotAcceptRollbackConfirmation() throws Exception {
        PlatformUserPrincipal principal = admin();
        mockMvc.perform(post("/api/v1/admin/graph/publications")
                        .with(user(principal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "graphGeneration", 999999,
                                "confirmation", "ROLLBACK",
                                "reason", "wrong endpoint confirmation"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("GRAPH_CONFIRMATION_INVALID"));
    }

    private PlatformUserPrincipal admin() {
        admin = users.saveAndFlush(new UserEntity(
                "graph-admin-" + UUID.randomUUID(),
                "graph-test-hash-" + UUID.randomUUID(),
                UserRole.ADMIN
        ));
        return PlatformUserPrincipal.from(admin);
    }
}
