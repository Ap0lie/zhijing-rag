package com.example.rag.chat;

import com.example.rag.chat.ChatModelProvider.ModelAnswer;
import com.example.rag.chat.ChatModelProvider.ModelEvidence;
import com.example.rag.chat.ChatModelProvider.ModelSegment;
import com.example.rag.chat.ChatModelProvider.PreparedPrompt;
import com.example.rag.chat.ChatPersistenceContracts.ChatMessage;
import com.example.rag.chat.ChatPersistenceContracts.ChatRun;
import com.example.rag.chat.ChatPersistenceContracts.ChatSession;
import com.example.rag.chat.ChatPersistenceContracts.Citation;
import com.example.rag.chat.ChatPersistenceContracts.CitationDraft;
import com.example.rag.memory.MemoryPackService;
import com.example.rag.chat.ChatPersistenceContracts.MessageRole;
import com.example.rag.chat.ChatPersistenceContracts.MessageStatus;
import com.example.rag.chat.ChatPersistenceContracts.RunCompletion;
import com.example.rag.chat.ChatPersistenceContracts.RunStatus;
import com.example.rag.chat.ChatPersistenceContracts.SessionStatus;
import com.example.rag.chat.ChatPersistenceContracts.StartedRun;
import com.example.rag.document.SourceLocatorResponse;
import com.example.rag.search.ChunkContextService;
import com.example.rag.search.SearchContracts.ChunkContext;
import com.example.rag.search.SearchContracts.ChunkView;
import com.example.rag.search.SearchContracts.EvidenceContext;
import com.example.rag.search.SearchContracts.GraphPathView;
import com.example.rag.search.SearchContracts.SearchHit;
import com.example.rag.search.SearchContracts.SearchPage;
import com.example.rag.search.SearchContracts.SourceSpanView;
import com.example.rag.search.SearchService;
import com.example.rag.security.PlatformUserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatWorkflowTests {

    @Test
    void schedulesCompressionOnlyForOnlineSessions() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID onlineSessionId = UUID.randomUUID();
        UUID evaluationSessionId = UUID.randomUUID();
        Instant now = Instant.now();

        PlatformUserPrincipal user = mock(PlatformUserPrincipal.class);
        when(user.id()).thenReturn(ownerId);
        ChatPersistenceRepository repository =
                mock(ChatPersistenceRepository.class);
        ContextCompressionService compression =
                mock(ContextCompressionService.class);
        TransactionTemplate transactions = executingTransactions();
        when(repository.finishRun(any(), any(), any())).thenReturn(true);
        when(repository.findSession(ownerId, onlineSessionId)).thenReturn(
                Optional.of(new ChatSession(
                        onlineSessionId,
                        ownerId,
                        "Online",
                        SessionStatus.ACTIVE,
                        0,
                        now,
                        now
                ))
        );
        when(repository.findSession(ownerId, evaluationSessionId))
                .thenReturn(Optional.empty());

        ChatWorkflow workflow = new ChatWorkflow(
                mock(SearchService.class),
                mock(ChunkContextService.class),
                mock(ChatModelProvider.class),
                repository,
                historyWindows(),
                mock(QueryIntelligenceProfileService.class),
                mock(QueryRoutingService.class),
                memoryPacks(),
                mock(PromptContextPlanner.class),
                compression,
                transactions,
                new ChatProperties(),
                mock(ChatUserGuard.class),
                new ObjectMapper().findAndRegisterModules()
        );
        RunCompletion completion = mock(RunCompletion.class);
        StartedRun online = startedRun(
                ownerId, onlineSessionId, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), now
        );
        StartedRun evaluation = startedRun(
                ownerId, evaluationSessionId, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), now
        );

        assertThat(workflow.finishAndScheduleCompression(
                new ChatWorkflow.RunInput(
                        user, online, "Online question", "en"
                ),
                completion
        )).isTrue();
        assertThat(workflow.finishAndScheduleCompression(
                new ChatWorkflow.RunInput(
                        user, evaluation, "Evaluation question", "en"
                ),
                completion
        )).isTrue();

        verify(compression).prepare(user, onlineSessionId);
        verify(compression, never()).prepare(user, evaluationSessionId);
        verify(repository).finishRun(ownerId, online.run().id(), completion);
        verify(repository).finishRun(
                ownerId, evaluation.run().id(), completion
        );
    }

    @Test
    void spreadsheetCitationPrefersThePreciseCellRange() {
        UUID documentId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        SourceSpanView heading = spreadsheetSpan(
                0, "工作表 销售汇总"
        );
        SourceSpanView range = spreadsheetSpan(
                1, "销售汇总!A1:F1,A2:F5"
        );
        ChunkView child = new ChunkView(
                childId,
                "CHILD",
                0,
                "S-003 的利润为 55,000",
                List.of("销售汇总"),
                null,
                null,
                20,
                "XLSX",
                range.sourceLocator(),
                range.sourceLabel()
        );
        ChunkContext context = new ChunkContext(
                documentId,
                "销售汇总",
                revisionId,
                1,
                child,
                null,
                List.of(heading, range),
                "XLSX"
        );

        assertThat(ChatWorkflow.citationAnchor(context)).isEqualTo(range);
        assertThat(ChatWorkflow.citationSpan(
                context, "工作表是什么？", List.of("工作表销售汇总")
        )).isEqualTo(range);
    }

    @Test
    void englishCitationSelectionUsesTheSupportingBodySpan() {
        String title = "Reference";
        String body = "Alice won seven games.";
        String childText = title + "\n" + body;
        SourceSpanView titleSpan = span(
                0, 1, 0, title.length(), "d"
        );
        SourceSpanView bodySpan = span(
                1, 1, title.length() + 1, childText.length(), "e"
        );
        ChunkContext context = context(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                title, 1, 1, List.of(titleSpan, bodySpan), childText
        );

        SourceSpanView selected = ChatWorkflow.citationSpan(
                context,
                "How many games did Alice win?",
                List.of("Alice won seven games.")
        );

        assertThat(selected).isEqualTo(bodySpan);
    }

    @Test
    void citationSelectionUsesStableAnchorWhenThereIsNoOverlap() {
        String childText = "Title\nBody";
        SourceSpanView first = span(0, 1, 0, 5, "f");
        SourceSpanView second = span(1, 1, 6, 10, "g");
        ChunkContext context = context(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Title", 1, 1, List.of(second, first), childText
        );

        assertThat(ChatWorkflow.citationSpan(
                context, "unrelated question", List.of("unknown answer")
        )).isEqualTo(first);
        assertThat(ChatWorkflow.citationSpan(
                context, "unrelated question", List.of("unknown answer")
        )).isEqualTo(first);
    }

    @Test
    void invalidMixedCitationSegmentCannotInfluenceTopLevelAnchorText() {
        UUID topLevel = UUID.randomUUID();
        UUID graphCitation = UUID.randomUUID();
        UUID invalid = UUID.randomUUID();

        ModelSegment invalidSegment = ChatWorkflow.normalizeSegment(
                new ModelSegment(
                        "poison title text",
                        List.of(topLevel, invalid)
                ),
                Set.of(topLevel, graphCitation),
                Set.of()
        );
        ModelSegment validSegment = ChatWorkflow.normalizeSegment(
                new ModelSegment(
                        "valid supporting body",
                        List.of(topLevel, graphCitation)
                ),
                Set.of(topLevel, graphCitation),
                Set.of()
        );

        assertThat(invalidSegment).isNull();
        assertThat(validSegment).isNotNull();
        Map<UUID, List<String>> citedTexts = ChatWorkflow.citedTexts(
                List.of(validSegment),
                Set.of(topLevel)
        );

        assertThat(citedTexts).containsOnlyKeys(topLevel);
        assertThat(citedTexts.get(topLevel))
                .containsExactly("valid supporting body");
    }

    @Test
    void segmentRejectedForUnauthorizedMemoryCannotSelectCitationSpan()
            throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        UUID unauthorizedMemoryId = UUID.randomUUID();
        Instant now = Instant.now();
        StartedRun started = startedRun(
                ownerId, sessionId, UUID.randomUUID(),
                UUID.randomUUID(), runId, now
        );
        PlatformUserPrincipal user = mock(PlatformUserPrincipal.class);
        when(user.id()).thenReturn(ownerId);
        when(user.isEnabled()).thenReturn(true);

        String poisonText = "poison alpha beta gamma delta";
        String validText = "safe";
        String childText = poisonText + "\n" + validText;
        SourceSpanView poisonSpan = span(
                0, 1, 0, poisonText.length(), "j"
        );
        SourceSpanView validSpan = span(
                1, 1, poisonText.length() + 1, childText.length(), "k"
        );
        ChunkContext context = context(
                documentId, revisionId, childId, "Reference", 1, 1,
                List.of(poisonSpan, validSpan), childText
        );
        SearchService search = mock(SearchService.class);
        when(search.search(any(), any(), any(), any())).thenReturn(
                new SearchPage(
                        List.of(hit(context)), 0, 8, 1, 1, 1,
                        "hybrid-v1", 1, "HYBRID", "HYBRID",
                        false, null, "EXACT"
                )
        );
        ChunkContextService chunks = mock(ChunkContextService.class);
        when(chunks.get(childId, user)).thenReturn(context);
        ChatModelProvider model = mock(ChatModelProvider.class);
        when(model.answer(any(PreparedPrompt.class))).thenAnswer(invocation -> {
            UUID citationId = ((PreparedPrompt) invocation.getArgument(0))
                    .evidence().getFirst().citationId();
            return new ModelAnswer(
                    List.of(
                            new ModelSegment(
                                    poisonText,
                                    List.of(citationId),
                                    List.of(unauthorizedMemoryId)
                            ),
                            new ModelSegment(
                                    validText,
                                    List.of(citationId),
                                    List.of()
                            )
                    ),
                    null
            );
        });
        ChatPersistenceRepository repository =
                mock(ChatPersistenceRepository.class);
        when(repository.findRun(ownerId, runId))
                .thenReturn(Optional.of(started.run()));
        when(repository.saveCitationWhitelist(eq(ownerId), eq(runId), any()))
                .thenReturn(List.of());
        when(repository.finishRun(any(), any(), any())).thenReturn(true);
        ChatWorkflow workflow = new ChatWorkflow(
                search,
                chunks,
                model,
                repository,
                historyWindows(),
                mock(QueryIntelligenceProfileService.class),
                mock(QueryRoutingService.class),
                memoryPacks(),
                new ChatProperties(),
                mock(ChatUserGuard.class),
                new ObjectMapper().findAndRegisterModules()
        );

        ChatWorkflow.PersistedOutcome outcome = workflow.execute(
                new ChatWorkflow.RunInput(
                        user, started, "unrelated question", "en"
                )
        );

        assertThat(outcome.content()).isEqualTo("safe[1]");
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<List> draftsCaptor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveCitationWhitelist(
                eq(ownerId), eq(runId), draftsCaptor.capture()
        );
        @SuppressWarnings("unchecked")
        List<CitationDraft> drafts = draftsCaptor.getValue();
        assertThat(drafts).singleElement().satisfies(draft ->
                assertThat(draft.sourceSpanId()).isEqualTo(validSpan.id())
        );
    }

    @Test
    void executesTheReleasedGraphWithSerializableStateAndNoEvidenceRefusal()
            throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID responseId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();

        PlatformUserPrincipal user = mock(PlatformUserPrincipal.class);
        when(user.id()).thenReturn(ownerId);
        when(user.isEnabled()).thenReturn(true);

        ChatMessage request = new ChatMessage(
                requestId,
                ownerId,
                sessionId,
                1,
                MessageRole.USER,
                "没有答案的问题",
                "zh",
                MessageStatus.COMPLETED,
                null,
                now,
                now
        );
        ChatMessage response = new ChatMessage(
                responseId,
                ownerId,
                sessionId,
                2,
                MessageRole.ASSISTANT,
                "",
                "zh",
                MessageStatus.STREAMING,
                null,
                now,
                now
        );
        ChatRun run = new ChatRun(
                runId,
                ownerId,
                sessionId,
                requestId,
                responseId,
                ChatWorkflow.ORCHESTRATION_VERSION,
                "没有答案的问题",
                "[]",
                "{}",
                null,
                null,
                null,
                "[]",
                "[]",
                "[]",
                UUID.randomUUID().toString(),
                RunStatus.RUNNING,
                null,
                null,
                now,
                now,
                null,
                now
        );
        StartedRun started = new StartedRun(request, response, run);

        SearchService search = mock(SearchService.class);
        when(search.search(any(), any(), any(), any())).thenReturn(new SearchPage(
                List.of(),
                0,
                8,
                0,
                0,
                1,
                "bm25-v1",
                1,
                "BM25",
                "BM25",
                false,
                null,
                "EXACT"
        ));
        ChatPersistenceRepository repository =
                mock(ChatPersistenceRepository.class);
        when(repository.findRun(ownerId, runId)).thenReturn(Optional.of(run));
        when(repository.finishRun(any(), any(), any())).thenReturn(true);
        ChatModelProvider model = mock(ChatModelProvider.class);

        ChatWorkflow workflow = new ChatWorkflow(
                search,
                mock(ChunkContextService.class),
                model,
                repository,
                historyWindows(),
                mock(QueryIntelligenceProfileService.class),
                mock(QueryRoutingService.class),
                memoryPacks(),
                new ChatProperties(),
                mock(ChatUserGuard.class),
                new ObjectMapper().findAndRegisterModules()
        );

        ChatWorkflow.PersistedOutcome outcome = workflow.execute(
                new ChatWorkflow.RunInput(
                        user,
                        started,
                        "没有答案的问题",
                        "zh"
                )
        );

        assertThat(outcome.status()).isEqualTo(RunStatus.REFUSED);
        assertThat(outcome.refusalCode()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(outcome.content()).contains("没有找到足够");
        assertThat(outcome.citations()).isEmpty();
        verify(model, never()).answer(any(PreparedPrompt.class));
        verify(repository).finishRun(any(), any(), any());
    }

    @Test
    void confirmedPersonalMemoryCannotBypassEvidenceRequirement()
            throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID memoryId = UUID.randomUUID();
        Instant now = Instant.now();
        StartedRun started = startedRun(
                ownerId,
                sessionId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                runId,
                now
        );
        PlatformUserPrincipal user = mock(PlatformUserPrincipal.class);
        when(user.id()).thenReturn(ownerId);
        when(user.isEnabled()).thenReturn(true);

        SearchService search = mock(SearchService.class);
        when(search.search(any(), any(), any(), any())).thenReturn(
                new SearchPage(
                        List.of(), 0, 8, 0, 0, 1,
                        "bm25-v1", 1, "BM25", "BM25",
                        false, null, "EXACT"
                )
        );
        ChatPersistenceRepository repository =
                mock(ChatPersistenceRepository.class);
        when(repository.findRun(ownerId, runId))
                .thenReturn(Optional.of(started.run()));
        when(repository.finishRun(any(), any(), any())).thenReturn(true);

        MemoryPackService memoryPacks = mock(MemoryPackService.class);
        var selection = new MemoryPackService.Selection(
                memoryId,
                "USER_PREFERENCE",
                "回答语言",
                "默认使用简体中文",
                0.9,
                42,
                List.of(),
                List.of(),
                "INJECTED",
                null,
                "a".repeat(64)
        );
        var pack = new MemoryPackService.MemoryPack(
                true,
                List.of(selection),
                42,
                MemoryPackService.MAX_TOKENS,
                MemoryPackService.TOKEN_COUNTER_VERSION,
                null
        );
        when(memoryPacks.recall(
                any(),
                any(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyInt()
        )).thenReturn(pack);
        when(memoryPacks.runUsages(any(), eq(runId))).thenReturn(List.of(
                new MemoryPackService.RunMemoryUsageView(
                        runId,
                        memoryId,
                        "USER_PREFERENCE",
                        "INJECTED",
                        0.9,
                        42,
                        MemoryPackService.MAX_TOKENS,
                        MemoryPackService.TOKEN_COUNTER_VERSION,
                        false,
                        List.of(),
                        true,
                        "回答语言",
                        "默认使用简体中文",
                        null,
                        now
                )
        ));

        ChatModelProvider model = mock(ChatModelProvider.class);
        ChatWorkflow workflow = new ChatWorkflow(
                search,
                mock(ChunkContextService.class),
                model,
                repository,
                historyWindows(),
                mock(QueryIntelligenceProfileService.class),
                mock(QueryRoutingService.class),
                memoryPacks,
                new ChatProperties(),
                mock(ChatUserGuard.class),
                new ObjectMapper().findAndRegisterModules()
        );

        ChatWorkflow.PersistedOutcome outcome = workflow.execute(
                new ChatWorkflow.RunInput(
                        user, started, "我偏好什么回答语言？", "zh"
                )
        );

        assertThat(outcome.status()).isEqualTo(RunStatus.REFUSED);
        assertThat(outcome.citations()).isEmpty();
        assertThat(outcome.memoryUsages()).singleElement()
                .extracting(
                        MemoryPackService.RunMemoryUsageView::usageStatus
                )
                .isEqualTo("INJECTED");
        verify(model, never()).answer(any(PreparedPrompt.class));
        verify(memoryPacks).saveRunUsages(
                eq(ownerId), eq(runId), eq(pack),
                eq(Set.of()), eq(Set.of())
        );
    }

    @Test
    void modelRefusalAfterEvidenceUsesAReasonDistinctFromMissingEvidence()
            throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();
        StartedRun started = startedRun(
                ownerId,
                sessionId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                runId,
                now
        );
        PlatformUserPrincipal user = mock(PlatformUserPrincipal.class);
        when(user.id()).thenReturn(ownerId);
        when(user.isEnabled()).thenReturn(true);

        UUID documentId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        SourceSpanView sourceSpan = span(0, 1, "model-refusal");
        ChunkContext context = context(
                documentId,
                revisionId,
                childId,
                "候选证据",
                1,
                1,
                List.of(sourceSpan)
        );
        SearchService search = mock(SearchService.class);
        when(search.search(any(), any(), any(), any())).thenReturn(new SearchPage(
                List.of(hit(context)),
                0,
                8,
                1,
                1,
                1,
                "hybrid-v1",
                1,
                "HYBRID",
                "HYBRID",
                false,
                null,
                "EXACT"
        ));
        ChunkContextService chunks = mock(ChunkContextService.class);
        when(chunks.get(childId, user)).thenReturn(context);
        ChatModelProvider model = mock(ChatModelProvider.class);
        when(model.answer(any(PreparedPrompt.class))).thenReturn(
                new ModelAnswer(List.of(), "MODEL_DECLINED")
        );
        ChatPersistenceRepository repository = mock(ChatPersistenceRepository.class);
        when(repository.findRun(ownerId, runId)).thenReturn(Optional.of(started.run()));
        when(repository.finishRun(any(), any(), any())).thenReturn(true);

        ChatWorkflow workflow = new ChatWorkflow(
                search,
                chunks,
                model,
                repository,
                historyWindows(),
                mock(QueryIntelligenceProfileService.class),
                mock(QueryRoutingService.class),
                memoryPacks(),
                new ChatProperties(),
                mock(ChatUserGuard.class),
                new ObjectMapper().findAndRegisterModules()
        );
        ChatWorkflow.PersistedOutcome outcome = workflow.execute(
                new ChatWorkflow.RunInput(user, started, "候选证据能否回答？", "zh")
        );

        assertThat(outcome.status()).isEqualTo(RunStatus.REFUSED);
        assertThat(outcome.refusalCode()).isEqualTo("MODEL_REFUSED");
        assertThat(outcome.content()).contains("候选来源", "未能形成");
    }

    @Test
    void selectedBodySpanIsRejectedWhenItIsNoLongerCurrent()
            throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        Instant now = Instant.now();
        StartedRun started = startedRun(
                ownerId, sessionId, UUID.randomUUID(),
                UUID.randomUUID(), runId, now
        );
        PlatformUserPrincipal user = mock(PlatformUserPrincipal.class);
        when(user.id()).thenReturn(ownerId);
        when(user.isEnabled()).thenReturn(true);

        String childText = "标题\n正文支持结论";
        SourceSpanView titleSpan = span(0, 1, 0, 2, "h");
        SourceSpanView bodySpan = span(
                1, 1, 3, childText.length(), "i"
        );
        ChunkContext initial = context(
                documentId, revisionId, childId, "标题", 1, 1,
                List.of(titleSpan, bodySpan), childText
        );
        ChunkContext afterWithdrawal = context(
                documentId, revisionId, childId, "标题", 1, 1,
                List.of(titleSpan), childText
        );
        SearchService search = mock(SearchService.class);
        when(search.search(any(), any(), any(), any())).thenReturn(
                new SearchPage(
                        List.of(hit(initial)), 0, 8, 1, 1, 1,
                        "hybrid-v1", 1, "HYBRID", "HYBRID",
                        false, null, "EXACT"
                )
        );
        ChunkContextService chunks = mock(ChunkContextService.class);
        when(chunks.get(childId, user))
                .thenReturn(initial, afterWithdrawal);
        ChatModelProvider model = mock(ChatModelProvider.class);
        when(model.answer(any(PreparedPrompt.class))).thenAnswer(invocation -> {
            PreparedPrompt prompt = invocation.getArgument(0);
            return new ModelAnswer(
                    List.of(new ModelSegment(
                            "正文支持结论。",
                            List.of(prompt.evidence().getFirst().citationId())
                    )),
                    null
            );
        });
        ChatPersistenceRepository repository =
                mock(ChatPersistenceRepository.class);
        when(repository.findRun(ownerId, runId))
                .thenReturn(Optional.of(started.run()));
        when(repository.finishRun(any(), any(), any())).thenReturn(true);
        ChatWorkflow workflow = new ChatWorkflow(
                search,
                chunks,
                model,
                repository,
                historyWindows(),
                mock(QueryIntelligenceProfileService.class),
                mock(QueryRoutingService.class),
                memoryPacks(),
                new ChatProperties(),
                mock(ChatUserGuard.class),
                new ObjectMapper().findAndRegisterModules()
        );

        ChatWorkflow.PersistedOutcome outcome = workflow.execute(
                new ChatWorkflow.RunInput(
                        user, started, "原文如何说明正文结论？", "zh"
                )
        );

        assertThat(outcome.status()).isEqualTo(RunStatus.REFUSED);
        assertThat(outcome.refusalCode()).isEqualTo("UNSUPPORTED_ANSWER");
        verify(repository, never()).saveCitationWhitelist(
                any(), any(), any()
        );
    }

    @Test
    void numbersCitationsAndPersistsOnlyUsedSourceSpans()
            throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID responseId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();

        PlatformUserPrincipal user = mock(PlatformUserPrincipal.class);
        when(user.id()).thenReturn(ownerId);
        when(user.isEnabled()).thenReturn(true);
        StartedRun started = startedRun(
                ownerId,
                sessionId,
                requestId,
                responseId,
                runId,
                now
        );

        UUID documentA = UUID.randomUUID();
        UUID revisionA = UUID.randomUUID();
        UUID childA = UUID.randomUUID();
        SourceSpanView spanA1 = span(0, 2, 0, 3, "a");
        SourceSpanView spanA2 = span(1, 3, 4, 9, "b");
        ChunkContext contextA = context(
                documentA,
                revisionA,
                childA,
                "甲文档",
                2,
                3,
                List.of(spanA2, spanA1),
                "甲文档\n甲乙结论。"
        );

        UUID documentB = UUID.randomUUID();
        UUID revisionB = UUID.randomUUID();
        UUID childB = UUID.randomUUID();
        SourceSpanView spanB = span(0, 5, "c");
        ChunkContext contextB = context(
                documentB,
                revisionB,
                childB,
                "乙文档",
                5,
                5,
                List.of(spanB)
        );

        SearchService search = mock(SearchService.class);
        when(search.search(any(), any(), any(), any())).thenReturn(new SearchPage(
                List.of(hit(contextA), hit(contextB)),
                0,
                8,
                2,
                1,
                4,
                "hybrid-v1",
                3,
                "HYBRID",
                "HYBRID",
                false,
                null,
                "EXACT"
        ));
        ChunkContextService chunks = mock(ChunkContextService.class);
        when(chunks.get(childA, user)).thenReturn(contextA);
        when(chunks.get(childB, user)).thenReturn(contextB);

        ChatModelProvider model = mock(ChatModelProvider.class);
        when(model.answer(any(PreparedPrompt.class))).thenAnswer(invocation -> {
            PreparedPrompt prompt = invocation.getArgument(0);
            List<ModelEvidence> evidence = prompt.evidence();
            assertThat(prompt.inputTokenCount())
                    .isLessThanOrEqualTo(prompt.inputTokenCap());
            return new ModelAnswer(
                    List.of(
                            new ModelSegment(
                                    "乙结论。",
                                    List.of(evidence.get(1).citationId())
                            ),
                            new ModelSegment(
                                    "甲乙结论。",
                                    List.of(
                                            evidence.get(0).citationId(),
                                            evidence.get(1).citationId()
                                    )
                            )
                    ),
                    null,
                    "B",
                    List.of(evidence.get(1).citationId())
            );
        });

        ChatPersistenceRepository repository =
                mock(ChatPersistenceRepository.class);
        when(repository.findRun(ownerId, runId))
                .thenReturn(Optional.of(started.run()));
        Map<UUID, SourceSpanView> spans = Map.of(
                spanA1.id(), spanA1,
                spanA2.id(), spanA2,
                spanB.id(), spanB
        );
        when(repository.saveCitationWhitelist(eq(ownerId), eq(runId), any()))
                .thenAnswer(invocation -> {
                    List<CitationDraft> drafts = invocation.getArgument(2);
                    AtomicInteger order = new AtomicInteger();
                    return drafts.stream().map(draft -> {
                        SourceSpanView span = spans.get(draft.sourceSpanId());
                        return new Citation(
                                draft.id(),
                                ownerId,
                                sessionId,
                                runId,
                                responseId,
                                draft.documentId(),
                                draft.revisionId(),
                                draft.childChunkId(),
                                draft.sourceSpanId(),
                                order.getAndIncrement(),
                                span.startPage(),
                                span.endPage(),
                                span.startOffset(),
                                span.endOffset(),
                                span.sourceTextHash(),
                                now
                        );
                    }).toList();
                });
        when(repository.finishRun(any(), any(), any())).thenReturn(true);

        ChatWorkflow workflow = new ChatWorkflow(
                search,
                chunks,
                model,
                repository,
                historyWindows(),
                mock(QueryIntelligenceProfileService.class),
                mock(QueryRoutingService.class),
                memoryPacks(),
                new ChatProperties(),
                mock(ChatUserGuard.class),
                new ObjectMapper().findAndRegisterModules()
        );
        ChatWorkflow.PersistedOutcome outcome = workflow.execute(
                new ChatWorkflow.RunInput(
                        user,
                        started,
                        "请说明甲乙结论",
                        "zh"
                )
        );

        assertThat(outcome.content())
                .isEqualTo("乙结论。[1]甲乙结论。[2][1]");
        assertThat(outcome.segments())
                .extracting(ModelSegment::text)
                .containsExactly("乙结论。[1]", "甲乙结论。[2][1]");
        assertThat(outcome.directAnswer()).isEqualTo("B");
        assertThat(outcome.directAnswerCitationIds())
                .containsExactly(outcome.citations().getFirst().id());
        assertThat(outcome.content()).doesNotContain("B");
        assertThat(String.join("", ChatService.answerDeltas(outcome)))
                .isEqualTo(outcome.content());

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<List> draftsCaptor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveCitationWhitelist(
                eq(ownerId),
                eq(runId),
                draftsCaptor.capture()
        );
        @SuppressWarnings("unchecked")
        List<CitationDraft> drafts = draftsCaptor.getValue();
        assertThat(drafts)
                .extracting(CitationDraft::sourceSpanId)
                .containsExactly(spanB.id(), spanA2.id());

        ArgumentCaptor<RunCompletion> completionCaptor =
                ArgumentCaptor.forClass(RunCompletion.class);
        verify(repository).finishRun(
                eq(ownerId),
                eq(runId),
                completionCaptor.capture()
        );
        verify(repository).recordRetrievalSnapshot(
                eq(ownerId),
                eq(runId),
                any()
        );
        RunCompletion completion = completionCaptor.getValue();
        assertThat(completion.responseContent()).isEqualTo(outcome.content());
        assertThat(completion.finalSourceSpansJson()).isEqualTo(
                "[\"" + spanB.id() + "\",\"" + spanA2.id() + "\"]"
        );

        Citation citationA = outcome.citations().get(1);
        assertThat(ChatService.citationSummary(citationA, contextA))
                .satisfies(summary -> {
                    assertThat(summary.startPage()).isEqualTo(3);
                    assertThat(summary.endPage()).isEqualTo(3);
                    assertThat(summary.label()).contains("[2]", "第 3 页");
                });
    }

    @Test
    void graphClaimUsesItsExactChildSourceSpanCitation() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID responseId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        Instant now = Instant.now();

        PlatformUserPrincipal user = mock(PlatformUserPrincipal.class);
        when(user.id()).thenReturn(ownerId);
        when(user.isEnabled()).thenReturn(true);
        StartedRun started = startedRun(
                ownerId, sessionId, requestId, responseId, runId, now
        );
        SourceSpanView genericSpan = span(0, 2, "g");
        SourceSpanView relationshipSpan = span(1, 3, "r");
        ChunkContext context = context(
                documentId,
                revisionId,
                childId,
                "图谱文档",
                2,
                3,
                List.of(genericSpan, relationshipSpan)
        );
        SearchHit graphHit = new SearchHit(
                childId,
                documentId,
                context.documentTitle(),
                revisionId,
                context.revisionNumber(),
                context.child().headingPath(),
                context.child().startPage(),
                context.child().endPage(),
                context.child().text(),
                new EvidenceContext(
                        1,
                        1.0,
                        1.0,
                        context.child().text(),
                        context.child().tokenCount(),
                        null,
                        List.of(new GraphPathView(
                                1,
                                childId,
                                "DEPENDS_ON",
                                childId,
                                relationshipSpan.id(),
                                documentId,
                                context.documentTitle(),
                                3,
                                3,
                                "甲依赖乙。",
                                20
                        ))
                )
        );
        SearchService search = mock(SearchService.class);
        when(search.search(any(), any(), any(), any())).thenReturn(new SearchPage(
                List.of(graphHit),
                0,
                8,
                1,
                1,
                2,
                "hybrid-v1",
                3,
                "HYBRID",
                "HYBRID",
                false,
                null,
                "CAPPED"
        ));
        ChunkContextService chunks = mock(ChunkContextService.class);
        when(chunks.get(childId, user)).thenReturn(context);
        ChatModelProvider model = mock(ChatModelProvider.class);
        when(model.answer(any(PreparedPrompt.class))).thenAnswer(invocation -> {
            List<ModelEvidence> evidence = ((PreparedPrompt)
                    invocation.getArgument(0)).evidence();
            UUID graphCitation = evidence.getFirst()
                    .graphContext().getFirst().citationId();
            return new ModelAnswer(
                    List.of(new ModelSegment(
                            "甲依赖乙。",
                            List.of(graphCitation)
                    )),
                    null,
                    "图谱文档",
                    List.of(evidence.getFirst().citationId())
            );
        });
        ChatPersistenceRepository repository =
                mock(ChatPersistenceRepository.class);
        when(repository.findRun(ownerId, runId))
                .thenReturn(Optional.of(started.run()));
        when(repository.saveCitationWhitelist(
                eq(ownerId), eq(runId), any()
        )).thenAnswer(invocation -> {
            List<CitationDraft> drafts = invocation.getArgument(2);
            CitationDraft draft = drafts.getFirst();
            return List.of(new Citation(
                    draft.id(),
                    ownerId,
                    sessionId,
                    runId,
                    responseId,
                    documentId,
                    revisionId,
                    childId,
                    draft.sourceSpanId(),
                    0,
                    relationshipSpan.startPage(),
                    relationshipSpan.endPage(),
                    relationshipSpan.startOffset(),
                    relationshipSpan.endOffset(),
                    relationshipSpan.sourceTextHash(),
                    now
            ));
        });
        when(repository.finishRun(any(), any(), any())).thenReturn(true);

        ChatWorkflow workflow = new ChatWorkflow(
                search,
                chunks,
                model,
                repository,
                historyWindows(),
                mock(QueryIntelligenceProfileService.class),
                mock(QueryRoutingService.class),
                memoryPacks(),
                new ChatProperties(),
                mock(ChatUserGuard.class),
                new ObjectMapper().findAndRegisterModules()
        );
        ChatWorkflow.PersistedOutcome outcome = workflow.execute(
                new ChatWorkflow.RunInput(
                user, started, "甲和乙有什么关系？", "zh"
        ));

        assertThat(outcome.directAnswer()).isNull();
        assertThat(outcome.directAnswerCitationIds()).isEmpty();

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<List> draftsCaptor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveCitationWhitelist(
                eq(ownerId), eq(runId), draftsCaptor.capture()
        );
        @SuppressWarnings("unchecked")
        List<CitationDraft> drafts = draftsCaptor.getValue();
        assertThat(drafts).singleElement().satisfies(draft ->
                assertThat(draft.sourceSpanId())
                        .isEqualTo(relationshipSpan.id())
        );
    }

    private static StartedRun startedRun(
            UUID ownerId,
            UUID sessionId,
            UUID requestId,
            UUID responseId,
            UUID runId,
            Instant now
    ) {
        ChatMessage request = new ChatMessage(
                requestId,
                ownerId,
                sessionId,
                1,
                MessageRole.USER,
                "请说明甲乙结论",
                "zh",
                MessageStatus.COMPLETED,
                null,
                now,
                now
        );
        ChatMessage response = new ChatMessage(
                responseId,
                ownerId,
                sessionId,
                2,
                MessageRole.ASSISTANT,
                "",
                "zh",
                MessageStatus.STREAMING,
                null,
                now,
                now
        );
        ChatRun run = new ChatRun(
                runId,
                ownerId,
                sessionId,
                requestId,
                responseId,
                ChatWorkflow.ORCHESTRATION_VERSION,
                "请说明甲乙结论",
                "[]",
                "{}",
                null,
                null,
                null,
                "[]",
                "[]",
                "[]",
                UUID.randomUUID().toString(),
                RunStatus.RUNNING,
                null,
                null,
                now,
                now,
                null,
                now
        );
        return new StartedRun(request, response, run);
    }

    private static SourceSpanView span(int order, int page, String hashSeed) {
        return span(order, page, 0, 9, hashSeed);
    }

    private static SourceSpanView span(
            int order,
            int page,
            int chunkStartOffset,
            int chunkEndOffset,
            String hashSeed
    ) {
        return new SourceSpanView(
                UUID.randomUUID(),
                order,
                page,
                page,
                order * 10,
                order * 10 + 9,
                chunkStartOffset,
                chunkEndOffset,
                hashSeed.repeat(64)
        );
    }

    private static SourceSpanView spreadsheetSpan(
            int order,
            String sourceLabel
    ) {
        UUID sourceUnitId = UUID.randomUUID();
        String hash = order == 0 ? "a".repeat(64) : "b".repeat(64);
        return new SourceSpanView(
                UUID.randomUUID(),
                order,
                null,
                null,
                0,
                9,
                0,
                9,
                hash,
                "XLSX",
                new SourceLocatorResponse(
                        "CELL_RANGE",
                        sourceUnitId,
                        sourceUnitId,
                        0,
                        9,
                        sourceLabel,
                        hash,
                        "spreadsheet-v1",
                        null,
                        null,
                        sourceLabel
                ),
                sourceLabel
        );
    }

    private static ChunkContext context(
            UUID documentId,
            UUID revisionId,
            UUID childId,
            String title,
            int startPage,
            int endPage,
            List<SourceSpanView> sourceSpans
    ) {
        return context(
                documentId, revisionId, childId, title,
                startPage, endPage, sourceSpans, title + "正文"
        );
    }

    private static ChunkContext context(
            UUID documentId,
            UUID revisionId,
            UUID childId,
            String title,
            int startPage,
            int endPage,
            List<SourceSpanView> sourceSpans,
            String childText
    ) {
        return new ChunkContext(
                documentId,
                title,
                revisionId,
                1,
                new ChunkView(
                        childId,
                        "CHILD",
                        0,
                        childText,
                        List.of(title),
                        startPage,
                        endPage,
                        20
                ),
                null,
                sourceSpans
        );
    }

    private static SearchHit hit(ChunkContext context) {
        return new SearchHit(
                context.child().id(),
                context.documentId(),
                context.documentTitle(),
                context.revisionId(),
                context.revisionNumber(),
                context.child().headingPath(),
                context.child().startPage(),
                context.child().endPage(),
                context.child().text(),
                new EvidenceContext(
                        1,
                        1.0,
                        1.0,
                        context.child().text(),
                        context.child().tokenCount(),
                        null
                )
        );
    }

    private static ChatHistoryWindowService historyWindows() {
        ChatHistoryWindowService service =
                mock(ChatHistoryWindowService.class);
        when(service.build(any(), any())).thenReturn(
                ChatHistoryWindowService.HistoryWindow.off()
        );
        return service;
    }

    private static MemoryPackService memoryPacks() {
        MemoryPackService service = mock(MemoryPackService.class);
        when(service.recall(
                any(),
                any(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyInt()
        )).thenReturn(
                MemoryPackService.MemoryPack.off()
        );
        when(service.runUsages(
                any(),
                org.mockito.ArgumentMatchers.any(UUID.class)
        )).thenReturn(List.of());
        return service;
    }

    private static TransactionTemplate executingTransactions() {
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        return transactions;
    }
}
