package com.example.rag.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "rag.search.enabled=true",
        "rag.search.projection-delay-ms=3600000"
})
@AutoConfigureMockMvc
class Phase6aConfigurationIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    @Test
    void configurationUsesTheMigratedPublicationAndModelHealthIsDisabledByDefault() throws Exception {
        mockMvc.perform(get("/api/v1/admin/retrieval/configuration").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentPublication.profileVersion")
                        .value("phase5-bm25-v1"))
                .andExpect(jsonPath("$.indexConfigs[0].version")
                        .value("phase5-bm25-v1"))
                .andExpect(jsonPath("$.indexConfigs[1].version")
                        .value("phase6-hybrid-qwen3-v1"))
                .andExpect(jsonPath("$.indexConfigs[1].vectorDimensions").value(1024))
                .andExpect(jsonPath("$.indexConfigs[1].embeddingProviderKey")
                        .value("openai-compatible"))
                .andExpect(jsonPath("$.indexConfigs[1].embeddingInputFormatVersion")
                        .value("raw-text-v1"))
                .andExpect(jsonPath("$.indexConfigs[1].embeddingNormalizationVersion")
                        .value("none-v1"))
                .andExpect(jsonPath("$.indexConfigs[1].distance").value("COSINE"))
                .andExpect(jsonPath("$.profiles[0].mode").value("BM25"))
                .andExpect(jsonPath("$.goldenBaseline.datasetVersion")
                        .value("retrieval-golden-v2"))
                .andExpect(jsonPath("$.goldenBaseline.caseCount")
                        .value(greaterThanOrEqualTo(40)));

        mockMvc.perform(get("/api/v1/admin/model-services/health").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services[0].type").value("EMBEDDING"))
                .andExpect(jsonPath("$.services[0].status").value("DISABLED"))
                .andExpect(jsonPath("$.services[0].dimensions").value(1024))
                .andExpect(jsonPath("$.services[1].type").value("RERANK"))
                .andExpect(jsonPath("$.services[1].status").value("DISABLED"));
    }

    @Test
    void profileCreationIsAppendOnlyAndDoesNotChangeTheCurrentPublication() throws Exception {
        String version = "test-profile-" + UUID.randomUUID();
        String request = json.writeValueAsString(Map.of(
                "version", version,
                "mode", "HYBRID",
                "defaultPageSize", 20,
                "maxPageSize", 50,
                "bm25TopK", 50,
                "vectorTopK", 50,
                "rrfRankConstant", 60,
                "rerankTopK", 30,
                "evidenceTopK", 8,
                "parentTokenBudget", 6000
        ));

        mockMvc.perform(post("/api/v1/admin/retrieval/profiles")
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(version))
                .andExpect(jsonPath("$.mode").value("HYBRID"));

        mockMvc.perform(post("/api/v1/admin/retrieval/profiles")
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RETRIEVAL_PROFILE_VERSION_EXISTS"));

        assertThat(jdbc.queryForObject(
                "SELECT profile_version FROM retrieval_publications WHERE singleton_id = 1",
                String.class
        )).isEqualTo("phase5-bm25-v1");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE retrieval_profiles SET bm25_top_k = 40 WHERE version = ?",
                version
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM retrieval_profiles WHERE version = ?",
                version
        )).isInstanceOf(DataAccessException.class);
    }

    @Test
    void invalidProfileRelationshipsAreRejectedBeforeDatabaseWrite() throws Exception {
        String request = json.writeValueAsString(Map.of(
                "version", "invalid-profile-" + UUID.randomUUID(),
                "mode", "BM25",
                "defaultPageSize", 50,
                "maxPageSize", 20,
                "bm25TopK", 10,
                "vectorTopK", 10,
                "rrfRankConstant", 60,
                "rerankTopK", 30,
                "evidenceTopK", 20,
                "parentTokenBudget", 6000
        ));

        mockMvc.perform(post("/api/v1/admin/retrieval/profiles")
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RETRIEVAL_PROFILE_INVALID"));
    }

    @Test
    void embeddingCacheStatsAndAuditedClearUseTheAdminBoundary() throws Exception {
        String provider = "openai-compatible";
        String model = "cache-test-" + UUID.randomUUID();
        String revision = "revision-1";
        String inputHash = EmbeddingCacheService.sha256("cache test input");
        var encoded = EmbeddingCacheService.encode(
                java.util.List.of(0.1d, 0.2d, 0.3d),
                3
        );
        jdbc.update(
                """
                INSERT INTO embedding_artifacts (
                    id, provider_key, model_id, model_revision, purpose,
                    dimensions, input_format_version, normalization_version,
                    content_hash, input_hash, vector_bytes, vector_checksum, byte_size
                ) VALUES (?, ?, ?, ?, 'CHILD_INDEX', 3, 'raw-text-v1',
                          'none-v1', ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                provider,
                model,
                revision,
                inputHash,
                inputHash,
                encoded.bytes(),
                encoded.checksum(),
                encoded.bytes().length
        );

        mockMvc.perform(get("/api/v1/admin/embedding-cache/stats").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query.maxEntries").value(2048))
                .andExpect(jsonPath("$.artifacts.maxBytes").value(2147483648L))
                .andExpect(jsonPath("$.artifacts.entries").value(greaterThanOrEqualTo(1)));
        mockMvc.perform(get("/api/v1/admin/embedding-cache/stats")
                        .with(user("phase6a-user").roles("USER")))
                .andExpect(status().isForbidden());

        String request = json.writeValueAsString(Map.of(
                "providerKey", provider,
                "model", model,
                "revision", revision,
                "confirmation", "CLEAR",
                "reason", "Phase 7A cache API verification"
        ));
        mockMvc.perform(post("/api/v1/admin/embedding-cache/clear")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/embedding-cache/clear")
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedArtifacts").value(1))
                .andExpect(jsonPath("$.freedBytes").value(encoded.bytes().length))
                .andExpect(jsonPath("$.invalidatedQueryEntries").value(0));

        Long eventId = jdbc.queryForObject(
                """
                SELECT id
                FROM embedding_cache_events
                WHERE provider_key = ? AND model_id = ? AND model_revision = ?
                """,
                Long.class,
                provider,
                model,
                revision
        );
        assertThat(eventId).isNotNull();
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE embedding_cache_events SET reason = 'changed' WHERE id = ?",
                eventId
        )).isInstanceOf(DataAccessException.class);
    }

    @Test
    void v8MigratesExistingManifestAndActiveProfileWithoutLosingIdentity() {
        String schema = "phase6a_migration_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate isolated = new JdbcTemplate(dataSource);
        try {
            Flyway.configure()
                    .dataSource(dataSource)
                    .defaultSchema(schema)
                    .schemas(schema)
                    .locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("7"))
                    .load()
                    .migrate();

            UUID manifestId = UUID.randomUUID();
            isolated.update(
                    """
                    INSERT INTO %s.index_manifests (
                        id, index_name, index_alias, schema_version,
                        retrieval_profile_version, status
                    ) VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                    """.formatted(schema),
                    manifestId,
                    "legacy-index",
                    "rag-child-chunks",
                    "phase5-bm25-v1",
                    "phase5-bm25-v1"
            );

            Flyway.configure()
                    .dataSource(dataSource)
                    .defaultSchema(schema)
                    .schemas(schema)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            assertThat(isolated.queryForObject(
                    "SELECT index_config_version FROM " + schema + ".index_manifests WHERE id = ?",
                    String.class,
                    manifestId
            )).isEqualTo("phase5-bm25-v1");
            assertThat(isolated.queryForMap(
                    """
                    SELECT embedding_model, embedding_revision, vector_dimensions,
                           distance, hnsw_m, hnsw_ef_construction
                    FROM %s.index_configs
                    WHERE version = 'phase6-hybrid-qwen3-v1'
                    """.formatted(schema)
            )).containsEntry("embedding_model", "Qwen/Qwen3-Embedding-0.6B")
                    .containsEntry(
                            "embedding_revision",
                            "97b0c614be4d77ee51c0cef4e5f07c00f9eb65b3"
                    )
                    .containsEntry("vector_dimensions", 1024)
                    .containsEntry("distance", "COSINE")
                    .containsEntry("hnsw_m", 16)
                    .containsEntry("hnsw_ef_construction", 128);
            assertThat(isolated.queryForObject(
                    "SELECT profile_version FROM " + schema
                            + ".retrieval_publications WHERE singleton_id = 1",
                    String.class
            )).isEqualTo("phase5-bm25-v1");
            assertThat(isolated.queryForObject(
                    """
                    SELECT count(*)
                    FROM information_schema.columns
                    WHERE table_schema = ?
                      AND table_name = 'index_manifests'
                      AND column_name IN ('schema_version', 'retrieval_profile_version')
                    """,
                    Integer.class,
                    schema
            )).isZero();
            assertThat(isolated.queryForObject(
                    """
                    SELECT count(*)
                    FROM information_schema.columns
                    WHERE table_schema = ?
                      AND table_name = 'retrieval_profiles'
                      AND column_name = 'active'
                    """,
                    Integer.class,
                    schema
            )).isZero();
            assertThatThrownBy(() -> isolated.update(
                    "DELETE FROM " + schema + ".index_configs WHERE version = 'phase5-bm25-v1'"
            )).isInstanceOf(DataAccessException.class);
        } finally {
            isolated.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private static SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor admin() {
        return user("phase6a-admin").roles("ADMIN");
    }
}
