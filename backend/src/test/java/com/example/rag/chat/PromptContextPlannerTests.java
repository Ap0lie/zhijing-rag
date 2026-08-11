package com.example.rag.chat;

import com.example.rag.chat.ChatHistoryWindowService.HistoryWindow;
import com.example.rag.chat.ChatModelProvider.GraphEvidence;
import com.example.rag.chat.ChatModelProvider.ModelAnswer;
import com.example.rag.chat.ChatModelProvider.ModelEvidence;
import com.example.rag.chat.ChatModelProvider.ModelHistoryMessage;
import com.example.rag.chat.ChatModelProvider.ModelMemory;
import com.example.rag.chat.ChatModelProvider.ModelSegment;
import com.example.rag.chat.ChatModelProvider.PreparedPrompt;
import com.example.rag.chat.ChatWorkflow.EvidenceItem;
import com.example.rag.memory.MemoryPackService.MemoryPack;
import com.example.rag.memory.MemoryPackService.Selection;
import com.example.rag.search.SearchContracts.EvidenceContext;
import com.example.rag.search.SearchContracts.SearchHit;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptContextPlannerTests {

    @Test
    void trimsInFixedOrderAndKeepsCoreEvidenceWithinCap() {
        ChatProperties properties = new ChatProperties();
        properties.getLlm().setContextWindowTokens(1_024);
        properties.getLlm().setMaxOutputTokens(100);
        CountingModel model = new CountingModel();
        PromptContextPlanner planner = new PromptContextPlanner(
                model, properties, new ObjectMapper()
        );
        HistoryWindow history = new HistoryWindow(
                null, ContextCompressionService.POLICY_VERSION,
                List.of(
                        new ModelHistoryMessage("summary", "s".repeat(220)),
                        new ModelHistoryMessage("user", "u".repeat(100))
                ),
                List.of(), "a".repeat(64),
                ContextCompressionService.COUNTER_VERSION,
                320, List.of(), UUID.randomUUID(), 8, 220, 1,
                "USED", null
        );
        UUID memoryId = UUID.randomUUID();
        MemoryPack memory = new MemoryPack(
                true,
                List.of(new Selection(
                        memoryId, "USER_PREFERENCE", "style",
                        "m".repeat(650), 1.0, 650, List.of("USER"),
                        List.of(), "INJECTED", null, "b".repeat(64)
                )),
                650, 650, "test", null
        );
        List<EvidenceItem> evidence = List.of(
                evidence("one"), evidence("two")
        );

        PromptContextPlanner.PromptPlan plan = planner.plan(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "question", evidence, history, memory
        );

        assertThat(plan.prompt().inputTokenCap()).isEqualTo(668);
        assertThat(plan.prompt().inputTokenCount()).isLessThanOrEqualTo(668);
        assertThat(plan.evidence()).hasSize(2);
        assertThat(plan.memoryIds()).isEmpty();
        assertThat(plan.prompt().history())
                .extracting(ModelHistoryMessage::role)
                .doesNotContain("summary")
                .containsExactly("user");
        assertThat(plan.prompt().evidence())
                .allSatisfy(item -> {
                    assertThat(item.parentText()).isNull();
                    assertThat(item.graphContext()).isEmpty();
                    assertThat(item.childText()).isNotBlank();
                });
        assertThat(plan.prompt().trimReasons()).containsExactly(
                "PROMPT_MEMORY_TRIMMED",
                "PROMPT_PARENT_TRIMMED",
                "PROMPT_GRAPH_AUXILIARY_TRIMMED",
                "PROMPT_SUMMARY_TRIMMED"
        );
        assertThat(plan.prompt().planHash()).hasSize(64);
    }

    @Test
    void refusesBeforeCallingModelWhenCoreEvidenceCannotFit() {
        ChatProperties properties = new ChatProperties();
        properties.getLlm().setContextWindowTokens(1_024);
        properties.getLlm().setMaxOutputTokens(800);
        PromptContextPlanner planner = new PromptContextPlanner(
                new CountingModel(), properties, new ObjectMapper()
        );

        assertThatThrownBy(() -> planner.plan(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "question", List.of(evidence("core")),
                HistoryWindow.off(), MemoryPack.off()
        )).isInstanceOfSatisfying(
                ChatWorkflowException.class,
                exception -> assertThat(exception.code())
                        .isEqualTo("EVIDENCE_CONTEXT_BUDGET_EXHAUSTED")
        );
    }

    @Test
    void plansEveryDeepGlobalCallAndTrimsReduceDraftsToTheSameCap() {
        ChatProperties properties = new ChatProperties();
        CountingModel model = new CountingModel();
        PromptContextPlanner planner = new PromptContextPlanner(
                model, properties, new ObjectMapper()
        );
        ModelEvidence evidence = evidence("global").modelEvidence();
        PreparedPrompt base = new PreparedPrompt(
                "question", List.of(evidence), List.of(), List.of(),
                700, model.countAnswerRequest(
                "question", List.of(evidence), List.of(), List.of()
        ), ContextCompressionService.COUNTER_VERSION,
                "base-plan", List.of()
        );
        ModelAnswer first = new ModelAnswer(
                List.of(new ModelSegment(
                        "a".repeat(10), List.of(evidence.citationId())
                )), null
        );
        ModelAnswer second = new ModelAnswer(
                List.of(new ModelSegment(
                        "b".repeat(10), List.of(evidence.citationId())
                )), null
        );

        PreparedPrompt map = planner.planMapCall(base, evidence, 0);
        PromptContextPlanner.ReducePromptPlan reduce =
                planner.planReduceCall(
                        base, List.of(evidence), List.of(first, second)
                );

        assertThat(map.inputTokenCount()).isLessThanOrEqualTo(700);
        assertThat(map.planHash()).hasSize(64);
        assertThat(reduce).isNotNull();
        assertThat(reduce.mapAnswers()).containsExactly(first);
        assertThat(reduce.prompt().inputTokenCount())
                .isLessThanOrEqualTo(700);
        assertThat(reduce.prompt().trimReasons())
                .contains("PROMPT_REDUCE_DRAFT_TRIMMED");
    }

    @Test
    void finalChildTrimPreservesAtLeastOneCandidatePerQuerySlotWhenPossible() {
        ChatProperties properties = new ChatProperties();
        properties.getLlm().setContextWindowTokens(1_024);
        properties.getLlm().setMaxOutputTokens(100);
        PromptContextPlanner planner = new PromptContextPlanner(
                new CountingModel(), properties, new ObjectMapper()
        );

        PromptContextPlanner.PromptPlan plan = planner.plan(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "question",
                List.of(
                        slottedEvidence("slot-a", "1:1"),
                        slottedEvidence("slot-b-primary", "1:2"),
                        slottedEvidence("slot-b-extra", "1:2")
                ),
                HistoryWindow.off(), MemoryPack.off()
        );

        assertThat(plan.evidence())
                .extracting(item -> item.modelEvidence().documentTitle())
                .containsExactly("slot-a", "slot-b-primary");
        assertThat(plan.prompt().trimReasons())
                .contains("PROMPT_CHILD_EVIDENCE_TRIMMED");
    }

    @Test
    void rewritePlanUsesTheSerializedRequestBudgetAndDropsSummaryFirst() {
        ChatProperties properties = new ChatProperties();
        properties.getLlm().setContextWindowTokens(1_024);
        CountingModel model = new CountingModel();
        PromptContextPlanner planner = new PromptContextPlanner(
                model, properties, new ObjectMapper()
        );
        HistoryWindow history = new HistoryWindow(
                null, ContextCompressionService.POLICY_VERSION,
                List.of(
                        new ModelHistoryMessage("summary", "s".repeat(500)),
                        new ModelHistoryMessage("user", "u".repeat(20))
                ),
                List.of(), "a".repeat(64),
                ContextCompressionService.COUNTER_VERSION,
                520, List.of(), UUID.randomUUID(), 8, 500, 1,
                "USED", null
        );

        PromptContextPlanner.RewritePlan plan = planner.planRewrite(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "follow up", history
        );

        assertThat(plan.callModel()).isTrue();
        assertThat(plan.history())
                .extracting(ModelHistoryMessage::role)
                .containsExactly("user");
    }

    @Test
    void rewritePlanSkipsTheModelWhenImmutableInputCannotFit() {
        ChatProperties properties = new ChatProperties();
        properties.getLlm().setContextWindowTokens(1_024);
        PromptContextPlanner planner = new PromptContextPlanner(
                new CountingModel(), properties, new ObjectMapper()
        );
        HistoryWindow history = new HistoryWindow(
                null, ContextCompressionService.POLICY_VERSION,
                List.of(new ModelHistoryMessage(
                        "user", "u".repeat(900)
                )),
                List.of(), "a".repeat(64),
                ContextCompressionService.COUNTER_VERSION,
                900, List.of(), null, 0, 0, 0,
                "NOT_NEEDED", null
        );

        PromptContextPlanner.RewritePlan plan = planner.planRewrite(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "follow up", history
        );

        assertThat(plan.callModel()).isFalse();
        assertThat(plan.reasonCode())
                .isEqualTo("CONTEXT_REWRITE_BUDGET_EXHAUSTED");
    }

    private EvidenceItem evidence(String title) {
        UUID citationId = UUID.randomUUID();
        return new EvidenceItem(
                citationId, null, null, Map.of(),
                new ModelEvidence(
                        citationId, title, 1, List.of("section"),
                        1, 1, "c".repeat(80), "p".repeat(300),
                        List.of(new GraphEvidence(
                                citationId, 1, "RELATED_TO",
                                "g".repeat(100)
                        )),
                        "PDF", "page 1"
                )
        );
    }

    private EvidenceItem slottedEvidence(String title, String slot) {
        UUID citationId = UUID.randomUUID();
        EvidenceContext context = new EvidenceContext(
                1, 1.0, 1.0, "c".repeat(180), 180,
                null, List.of(), List.of(), List.of(slot)
        );
        SearchHit hit = new SearchHit(
                UUID.randomUUID(), UUID.randomUUID(), title,
                UUID.randomUUID(), 1, List.of("section"),
                1, 1, title, context
        );
        return new EvidenceItem(
                citationId, hit, null, Map.of(),
                new ModelEvidence(
                        citationId, title, 1, List.of("section"),
                        1, 1, "c".repeat(180), null,
                        List.of(), "PDF", "page 1"
                )
        );
    }

    private static final class CountingModel implements ChatModelProvider {

        @Override
        public ModelAnswer answer(
                String question,
                List<ModelEvidence> evidence
        ) {
            throw new AssertionError("planner must not invoke the model");
        }

        @Override
        public int countAnswerRequest(
                String question,
                List<ModelEvidence> evidence,
                List<ModelHistoryMessage> history,
                List<ModelMemory> memories
        ) {
            int count = 200 + length(question);
            for (ModelEvidence item : evidence) {
                count += length(item.childText())
                        + length(item.parentText());
                count += item.graphContext().stream()
                        .mapToInt(graph -> length(graph.evidenceText()))
                        .sum();
            }
            count += history.stream()
                    .mapToInt(item -> length(item.content()))
                    .sum();
            count += memories.stream()
                    .mapToInt(item -> length(item.content()))
                    .sum();
            return count;
        }

        @Override
        public int countReduceRequest(
                String question,
                List<ModelEvidence> evidence,
                List<ModelAnswer> mapAnswers,
                List<ModelHistoryMessage> history,
                List<ModelMemory> memories
        ) {
            return countAnswerRequest(
                    question, evidence, history, memories
            ) + mapAnswers.stream()
                    .flatMap(answer -> answer.segments().stream())
                    .mapToInt(segment -> length(segment.text()))
                    .sum();
        }

        @Override
        public int countRewriteRequest(
                String question,
                List<ModelHistoryMessage> history
        ) {
            return 200 + length(question) + history.stream()
                    .mapToInt(item -> length(item.content()))
                    .sum();
        }

        private static int length(String value) {
            return value == null ? 0 : value.length();
        }
    }
}
