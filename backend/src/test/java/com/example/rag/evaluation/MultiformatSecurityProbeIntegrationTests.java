package com.example.rag.evaluation;

import com.example.rag.pipeline.PipelineWorkerHealthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "rag.pipeline.worker.enabled=false",
        "rag.graph.worker-enabled=false",
        "rag.graph.global-worker-enabled=false",
        "rag.evaluation.worker-enabled=false",
        "rag.memory.suggestion-worker-enabled=false"
})
class MultiformatSecurityProbeIntegrationTests {

    @Autowired
    private MultiformatSecurityProbe probes;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private PipelineWorkerHealthService workerHealth;

    @BeforeEach
    void enableProviders() {
        when(workerHealth.isParserAvailable(any())).thenReturn(true);
        jdbc.update("""
                UPDATE document_runtime_policies
                SET status = 'ENABLED', reason = NULL, changed_by = NULL,
                    updated_at = CURRENT_TIMESTAMP
                """);
    }

    @Test
    void executesEveryNegativeAttackCaseAgainstTheRealValidatorOrParser() {
        var results = probes.definitions().stream()
                .map(definition -> probes.execute(definition.key()))
                .toList();

        assertThat(results).hasSize(8);
        assertThat(results)
                .allSatisfy(result -> {
                    assertThat(result.suiteVersion())
                            .isEqualTo(MultiformatSecurityProbe.SUITE_VERSION);
                    assertThat(result.inputSha256()).hasSize(64);
                    assertThat(result.passed())
                            .as(result.key() + ": " + result.code())
                            .isTrue();
                });
    }
}
