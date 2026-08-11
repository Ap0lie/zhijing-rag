package com.example.rag.chat;

import com.example.rag.chat.ChatHistoryWindowService.HistoryWindow;
import com.example.rag.chat.ChatModelProvider.GraphEvidence;
import com.example.rag.chat.ChatModelProvider.ModelEvidence;
import com.example.rag.chat.ChatModelProvider.ModelAnswer;
import com.example.rag.chat.ChatModelProvider.ModelHistoryMessage;
import com.example.rag.chat.ChatModelProvider.ModelMemory;
import com.example.rag.chat.ChatModelProvider.PreparedPrompt;
import com.example.rag.chat.ChatWorkflow.EvidenceItem;
import com.example.rag.memory.MemoryPackService.MemoryPack;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@ConditionalOnProperty(
        prefix = "rag.chat",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
class PromptContextPlanner {

    private static final String COUNTER_VERSION =
            "conservative-utf8-request-v2";

    private final ChatModelProvider model;
    private final ChatProperties properties;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Autowired
    PromptContextPlanner(
            ChatModelProvider model,
            ChatProperties properties,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper
    ) {
        this.model = model;
        this.properties = properties;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    PromptContextPlanner(
            ChatModelProvider model,
            ChatProperties properties,
            ObjectMapper objectMapper
    ) {
        this(model, properties, null, objectMapper);
    }

    PromptPlan plan(
            UUID ownerUserId,
            UUID sessionId,
            UUID runId,
            String question,
            List<EvidenceItem> sourceEvidence,
            HistoryWindow history,
            MemoryPack memory
    ) {
        int cap = inputCap(properties.getLlm().getMaxOutputTokens());
        if (cap < 1) {
            throw exhausted();
        }

        List<EvidenceItem> evidence = new ArrayList<>(sourceEvidence);
        List<ModelHistoryMessage> historyMessages =
                new ArrayList<>(history.messages());
        List<ModelMemory> memories = new ArrayList<>(
                memory.injected().stream().map(item -> new ModelMemory(
                        item.memoryId(), item.memoryType(), item.memoryKey(),
                        item.content()
                )).toList()
        );
        List<String> reasons = new ArrayList<>();

        while (!memories.isEmpty()
                && count(question, evidence, historyMessages, memories) > cap) {
            memories.removeLast();
            addReason(reasons, "PROMPT_MEMORY_TRIMMED");
        }
        for (int index = evidence.size() - 1;
             index >= 0
                     && count(question, evidence, historyMessages, memories)
                     > cap;
             index--) {
            EvidenceItem item = evidence.get(index);
            if (item.modelEvidence().parentText() != null) {
                evidence.set(index, withParent(item, null));
                addReason(reasons, "PROMPT_PARENT_TRIMMED");
            }
        }
        for (int index = evidence.size() - 1;
             index >= 0
                     && count(question, evidence, historyMessages, memories)
                     > cap;
             index--) {
            EvidenceItem item = evidence.get(index);
            if (!item.modelEvidence().graphContext().isEmpty()) {
                evidence.set(index, withGraph(item, List.of()));
                addReason(reasons, "PROMPT_GRAPH_AUXILIARY_TRIMMED");
            }
        }
        if (count(question, evidence, historyMessages, memories) > cap) {
            int summaryIndex = summaryIndex(historyMessages);
            if (summaryIndex >= 0) {
                historyMessages.remove(summaryIndex);
                addReason(reasons, "PROMPT_SUMMARY_TRIMMED");
            }
        }
        while (historyMessages.size() > 2
                && count(question, evidence, historyMessages, memories) > cap) {
            int index = summaryIndex(historyMessages) == 0 ? 1 : 0;
            historyMessages.remove(index);
            addReason(reasons, "PROMPT_OLD_HISTORY_TRIMMED");
        }
        while (evidence.size() > 1
                && count(question, evidence, historyMessages, memories) > cap) {
            evidence.remove(removableEvidenceIndex(evidence));
            addReason(reasons, "PROMPT_CHILD_EVIDENCE_TRIMMED");
        }

        int total = count(question, evidence, historyMessages, memories);
        if (evidence.isEmpty() || total > cap) {
            throw exhausted();
        }
        List<ModelEvidence> modelEvidence = evidence.stream()
                .map(EvidenceItem::modelEvidence)
                .toList();
        String planHash = hash(Map.of(
                "question", question,
                "evidence", modelEvidence,
                "history", historyMessages,
                "memories", memories,
                "cap", cap,
                "counterVersion", COUNTER_VERSION
        ));
        PreparedPrompt prompt = new PreparedPrompt(
                question, modelEvidence, historyMessages, memories,
                cap, total, COUNTER_VERSION, planHash, reasons
        );
        persist(
                ownerUserId, sessionId, runId, history.summaryId(),
                "ANSWER", 0, prompt
        );
        Set<UUID> memoryIds = memories.stream()
                .map(ModelMemory::memoryId)
                .collect(java.util.stream.Collectors.toCollection(
                        LinkedHashSet::new
                ));
        return new PromptPlan(
                prompt, List.copyOf(evidence), Set.copyOf(memoryIds)
        );
    }

    RewritePlan planRewrite(
            UUID owner,
            UUID session,
            UUID run,
            String question,
            HistoryWindow history
    ) {
        int cap = inputCap(256);
        List<ModelHistoryMessage> selected = new ArrayList<>(
                history.messages()
        );
        List<String> reasons = new ArrayList<>();
        int total = model.countRewriteRequest(question, selected);
        int summaryIndex = summaryIndex(selected);
        if (summaryIndex >= 0 && total > cap) {
            selected.remove(summaryIndex);
            addReason(reasons, "REWRITE_SUMMARY_TRIMMED");
            total = model.countRewriteRequest(question, selected);
        }
        while (selected.size() > 1 && total > cap) {
            selected.removeFirst();
            addReason(reasons, "REWRITE_OLD_HISTORY_TRIMMED");
            total = model.countRewriteRequest(question, selected);
        }
        if (selected.isEmpty() || cap < 1 || total > cap) {
            return new RewritePlan(
                    List.of(), false,
                    "CONTEXT_REWRITE_BUDGET_EXHAUSTED"
            );
        }
        String planHash = hash(Map.of(
                "question", question,
                "history", selected,
                "cap", cap,
                "counterVersion", COUNTER_VERSION,
                "trimReasons", reasons
        ));
        persistRewrite(
                owner, session, run, history.summaryId(), question,
                selected, cap, total, planHash, reasons
        );
        return new RewritePlan(List.copyOf(selected), true, null);
    }

    PreparedPrompt planMapCall(
            PreparedPrompt base,
            ModelEvidence evidence,
            int callIndex
    ) {
        List<ModelEvidence> selected = List.of(evidence);
        int total = model.countAnswerRequest(
                base.question(), selected, base.history(), base.memories()
        );
        if (total > base.inputTokenCap()) {
            throw exhausted();
        }
        return derived(
                base, selected, total, "MAP", callIndex,
                List.of()
        );
    }

    ReducePromptPlan planReduceCall(
            PreparedPrompt base,
            List<ModelEvidence> evidence,
            List<ModelAnswer> mapAnswers
    ) {
        List<ModelAnswer> drafts = new ArrayList<>(mapAnswers);
        List<String> reasons = new ArrayList<>();
        int total = reduceCount(base, evidence, drafts);
        while (!drafts.isEmpty() && total > base.inputTokenCap()) {
            drafts.removeLast();
            addReason(reasons, "PROMPT_REDUCE_DRAFT_TRIMMED");
            total = reduceCount(base, evidence, drafts);
        }
        if (drafts.isEmpty() || total > base.inputTokenCap()) {
            return null;
        }
        return new ReducePromptPlan(
                derived(base, evidence, total, "REDUCE", 0, reasons),
                List.copyOf(drafts)
        );
    }

    void recordCall(
            UUID owner,
            UUID session,
            UUID run,
            UUID summary,
            String stage,
            int callIndex,
            PreparedPrompt prompt
    ) {
        if (!Set.of("ANSWER", "MAP", "REDUCE").contains(stage)) {
            throw new IllegalArgumentException("Unsupported prompt stage");
        }
        persist(owner, session, run, summary, stage, callIndex, prompt);
    }

    private PreparedPrompt derived(
            PreparedPrompt base,
            List<ModelEvidence> evidence,
            int total,
            String stage,
            int callIndex,
            List<String> additionalReasons
    ) {
        List<String> reasons = new ArrayList<>(base.trimReasons());
        additionalReasons.forEach(reason -> addReason(reasons, reason));
        return new PreparedPrompt(
                base.question(), evidence, base.history(), base.memories(),
                base.inputTokenCap(), total, base.counterVersion(),
                hash(Map.of(
                        "basePlanHash", base.planHash(),
                        "stage", stage,
                        "callIndex", callIndex,
                        "evidence", evidence,
                        "inputTokenCount", total,
                        "trimReasons", reasons
                )),
                reasons
        );
    }

    private int reduceCount(
            PreparedPrompt base,
            List<ModelEvidence> evidence,
            List<ModelAnswer> drafts
    ) {
        return model.countReduceRequest(
                base.question(), evidence, drafts,
                base.history(), base.memories()
        );
    }

    private int inputCap(int maxOutputTokens) {
        int contextWindow = properties.getLlm().getContextWindowTokens();
        int reserve = Math.max(
                256, (int) Math.ceil(contextWindow * 0.05)
        );
        return contextWindow - maxOutputTokens - reserve;
    }

    private int count(
            String question,
            List<EvidenceItem> evidence,
            List<ModelHistoryMessage> history,
            List<ModelMemory> memories
    ) {
        return model.countAnswerRequest(
                question,
                evidence.stream().map(EvidenceItem::modelEvidence).toList(),
                history,
                memories
        );
    }

    private void persist(
            UUID owner,
            UUID session,
            UUID run,
            UUID summary,
            String stage,
            int callIndex,
            PreparedPrompt prompt
    ) {
        if (jdbc == null) {
            return;
        }
        int system = model.countAnswerRequest(
                "", List.of(), List.of(), List.of()
        );
        int question = Math.max(0, model.countAnswerRequest(
                prompt.question(), List.of(), List.of(), List.of()
        ) - system);
        int history = estimate(prompt.history());
        int memory = estimate(prompt.memories());
        int evidence = Math.max(
                0,
                prompt.inputTokenCount()
                        - system - question - history - memory
        );
        jdbc.update(
                """
                INSERT INTO chat_run_context_usages (
                    id, owner_user_id, session_id, run_id, stage,
                    call_index, policy_version, summary_id, plan_hash,
                    counter_version, input_token_cap, input_token_count,
                    system_token_count, question_token_count,
                    history_token_count, memory_token_count,
                    evidence_token_count, trim_reasons
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          ?, ?, ?, ?, ?, CAST(? AS JSONB))
                ON CONFLICT (run_id, stage, call_index) DO NOTHING
                """,
                UUID.randomUUID(), owner, session, run, stage, callIndex,
                ContextCompressionService.POLICY_VERSION,
                summary, prompt.planHash(), prompt.counterVersion(),
                prompt.inputTokenCap(), prompt.inputTokenCount(),
                system, question, history, memory, evidence,
                json(prompt.trimReasons())
        );
    }

    private void persistRewrite(
            UUID owner,
            UUID session,
            UUID run,
            UUID summary,
            String currentQuestion,
            List<ModelHistoryMessage> history,
            int cap,
            int total,
            String planHash,
            List<String> reasons
    ) {
        if (jdbc == null) {
            return;
        }
        int system = model.countRewriteRequest("", List.of());
        int question = Math.max(
                0,
                model.countRewriteRequest(currentQuestion, List.of()) - system
        );
        int historyTokens = Math.max(0, total - system - question);
        jdbc.update(
                """
                INSERT INTO chat_run_context_usages (
                    id, owner_user_id, session_id, run_id, stage,
                    call_index, policy_version, summary_id, plan_hash,
                    counter_version, input_token_cap, input_token_count,
                    system_token_count, question_token_count,
                    history_token_count, memory_token_count,
                    evidence_token_count, trim_reasons
                ) VALUES (?, ?, ?, ?, 'REWRITE', 0, ?, ?, ?, ?, ?, ?,
                          ?, ?, ?, 0, 0, CAST(? AS JSONB))
                ON CONFLICT (run_id, stage, call_index) DO NOTHING
                """,
                UUID.randomUUID(), owner, session, run,
                ContextCompressionService.POLICY_VERSION,
                summary, planHash, COUNTER_VERSION, cap, total,
                system, question, historyTokens, json(reasons)
        );
    }

    private int estimate(Object value) {
        try {
            int bytes = objectMapper.writeValueAsBytes(value).length;
            return Math.max(0, (bytes + 3) / 4);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Unable to count prompt partition", exception
            );
        }
    }

    private EvidenceItem withParent(EvidenceItem item, String parent) {
        ModelEvidence current = item.modelEvidence();
        return new EvidenceItem(
                item.id(), item.hit(), item.context(), item.citationSpans(),
                new ModelEvidence(
                        current.citationId(), current.documentTitle(),
                        current.revisionNumber(), current.headingPath(),
                        current.startPage(), current.endPage(),
                        current.childText(), parent, current.graphContext(),
                        current.documentFormat(), current.sourceLabel()
                )
        );
    }

    private EvidenceItem withGraph(
            EvidenceItem item,
            List<GraphEvidence> graph
    ) {
        ModelEvidence current = item.modelEvidence();
        return new EvidenceItem(
                item.id(), item.hit(), item.context(), item.citationSpans(),
                new ModelEvidence(
                        current.citationId(), current.documentTitle(),
                        current.revisionNumber(), current.headingPath(),
                        current.startPage(), current.endPage(),
                        current.childText(), current.parentText(), graph,
                        current.documentFormat(), current.sourceLabel()
                )
        );
    }

    private int summaryIndex(List<ModelHistoryMessage> history) {
        for (int index = 0; index < history.size(); index++) {
            if ("summary".equals(history.get(index).role())) {
                return index;
            }
        }
        return -1;
    }

    private int removableEvidenceIndex(List<EvidenceItem> evidence) {
        Map<String, Integer> slotCounts = new java.util.LinkedHashMap<>();
        evidence.stream()
                .filter(item -> item.hit() != null
                        && item.hit().evidence() != null)
                .flatMap(item -> item.hit().evidence().querySlots().stream())
                .forEach(slot -> slotCounts.merge(slot, 1, Integer::sum));
        for (int index = evidence.size() - 1; index >= 0; index--) {
            EvidenceItem item = evidence.get(index);
            List<String> slots = item.hit() == null
                    || item.hit().evidence() == null
                    ? List.of()
                    : item.hit().evidence().querySlots();
            if (slots.isEmpty() || slots.stream().allMatch(
                    slot -> slotCounts.getOrDefault(slot, 0) > 1
            )) {
                return index;
            }
        }
        return evidence.size() - 1;
    }

    private static void addReason(List<String> reasons, String reason) {
        if (!reasons.contains(reason)) {
            reasons.add(reason);
        }
    }

    private ChatWorkflowException exhausted() {
        return new ChatWorkflowException(
                "EVIDENCE_CONTEXT_BUDGET_EXHAUSTED",
                "系统提示、当前问题与核心证据超过模型上下文预算"
        );
    }

    private String hash(Object value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            objectMapper.writeValueAsBytes(value)
                    )
            );
        } catch (NoSuchAlgorithmException | JsonProcessingException exception) {
            throw new IllegalStateException("Unable to hash prompt plan", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Unable to serialize prompt plan", exception
            );
        }
    }

    record PromptPlan(
            PreparedPrompt prompt,
            List<EvidenceItem> evidence,
            Set<UUID> memoryIds
    ) {
    }

    record RewritePlan(
            List<ModelHistoryMessage> history,
            boolean callModel,
            String reasonCode
    ) {
    }

    record ReducePromptPlan(
            PreparedPrompt prompt,
            List<ModelAnswer> mapAnswers
    ) {
    }
}
