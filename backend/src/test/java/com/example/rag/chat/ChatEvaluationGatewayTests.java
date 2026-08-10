package com.example.rag.chat;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ChatEvaluationGatewayTests {

    @Test
    void evaluationTraceIdIsStableUniqueAndWithinPersistenceLimit() {
        UUID runId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();

        String first = ChatEvaluationGateway.evaluationTraceId(
                runId, caseId, 1
        );

        assertThat(first).hasSizeLessThanOrEqualTo(64);
        assertThat(ChatEvaluationGateway.evaluationTraceId(
                runId, caseId, 1
        )).isEqualTo(first);
        assertThat(ChatEvaluationGateway.evaluationTraceId(
                runId, caseId, 2
        )).isNotEqualTo(first);
        assertThat(ChatEvaluationGateway.evaluationTraceId(
                runId, UUID.randomUUID(), 1
        )).isNotEqualTo(first);
    }
}
