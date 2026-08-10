package com.example.rag.chat;

import com.example.rag.chat.ChatPersistenceContracts.AnswerStrategy;
import com.example.rag.chat.ChatPersistenceContracts.Citation;
import com.example.rag.chat.ChatPersistenceContracts.RunStatus;
import com.example.rag.chat.ChatPersistenceContracts.StartRunCommand;
import com.example.rag.chat.ChatPersistenceContracts.StartedRun;
import com.example.rag.security.PlatformUserPrincipal;
import com.example.rag.search.SearchContracts.GraphMode;
import com.example.rag.search.SearchService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(
        prefix = "rag.chat",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ChatEvaluationGateway {

    private final ChatPersistenceRepository repository;
    private final ChatWorkflow workflow;

    ChatEvaluationGateway(
            ChatPersistenceRepository repository,
            ChatWorkflow workflow
    ) {
        this.repository = repository;
        this.workflow = workflow;
    }

    public EvaluationAnswer evaluate(
            UUID evaluationRunId,
            UUID evaluationCaseId,
            String question,
            String language,
            GraphMode graphMode,
            SearchService.EvaluationTarget target,
            PlatformUserPrincipal user,
            boolean modelTimeoutFault
    ) {
        return evaluate(
                evaluationRunId, evaluationCaseId, question, language,
                graphMode, target, user, modelTimeoutFault, null
        );
    }

    public EvaluationAnswer evaluate(
            UUID evaluationRunId,
            UUID evaluationCaseId,
            String question,
            String language,
            GraphMode graphMode,
            SearchService.EvaluationTarget target,
            PlatformUserPrincipal user,
            boolean modelTimeoutFault,
            UUID evaluationDocumentId
    ) {
        var session = repository.createEvaluationSession(
                user.id(),
                "[EVAL] " + evaluationCaseId,
                evaluationRunId,
                evaluationCaseId
        );
        return executeTurn(
                evaluationRunId,
                evaluationCaseId,
                session.id(),
                1,
                question,
                language,
                graphMode,
                target,
                user,
                modelTimeoutFault,
                evaluationDocumentId
        );
    }

    public List<EvaluationAnswer> evaluateTurns(
            UUID evaluationRunId,
            UUID evaluationCaseId,
            List<String> turns,
            String language,
            GraphMode graphMode,
            SearchService.EvaluationTarget target,
            PlatformUserPrincipal user
    ) {
        if (turns == null || turns.size() < 2 || turns.size() > 4) {
            throw new IllegalArgumentException(
                    "MULTI_TURN requires 2 to 4 turns"
            );
        }
        var session = repository.createEvaluationSession(
                user.id(),
                "[EVAL-MULTI] " + evaluationCaseId,
                evaluationRunId,
                evaluationCaseId
        );
        java.util.ArrayList<EvaluationAnswer> answers =
                new java.util.ArrayList<>(turns.size());
        for (int index = 0; index < turns.size(); index++) {
            answers.add(executeTurn(
                    evaluationRunId,
                    evaluationCaseId,
                    session.id(),
                    index + 1,
                    turns.get(index),
                    language,
                    graphMode,
                    target,
                    user,
                    false,
                    null
            ));
        }
        return List.copyOf(answers);
    }

    private EvaluationAnswer executeTurn(
            UUID evaluationRunId,
            UUID evaluationCaseId,
            UUID sessionId,
            int turn,
            String question,
            String language,
            GraphMode graphMode,
            SearchService.EvaluationTarget target,
            PlatformUserPrincipal user,
            boolean modelTimeoutFault,
            UUID evaluationDocumentId
    ) {
        StartedRun started = repository.startRun(
                user.id(),
                sessionId,
                new StartRunCommand(
                        question,
                        language,
                        ChatWorkflow.ORCHESTRATION_VERSION,
                        evaluationTraceId(
                                evaluationRunId, evaluationCaseId, turn
                        ),
                        graphMode.name(),
                        AnswerStrategy.STANDARD.name(),
                        target == null
                                ? null : target.queryProfileVersion()
                )
        ).orElseThrow(() -> new IllegalStateException(
                "Unable to create evaluation ChatRun"
        ));
        try {
            ChatWorkflow.PersistedOutcome outcome = workflow.execute(
                    new ChatWorkflow.RunInput(
                            user,
                            started,
                            question,
                            language,
                            target,
                            modelTimeoutFault,
                            evaluationDocumentId
                    )
            );
            return new EvaluationAnswer(
                    outcome.runId(),
                    outcome.status(),
                    outcome.refusalCode(),
                    outcome.graphModeRequested(),
                    outcome.graphModeUsed(),
                    outcome.graphDegraded(),
                    outcome.graphDegradationCode(),
                    outcome.mapCallCount(),
                    outcome.reduceCallCount(),
                    outcome.historyMessageCount(),
                    outcome.queryProfileVersion(),
                    outcome.plannerCallCount(),
                    outcome.retrievalCallCount(),
                    outcome.rerankCallCount(),
                    outcome.queryDegraded(),
                    outcome.queryDegradationCode(),
                    outcome.routeSelectedMode(),
                    outcome.routerCallCount(),
                    outcome.routeReasonCode(),
                    outcome.routeDegraded(),
                    outcome.routeDegradationCode(),
                    outcome.memoryInjectedCount(),
                    outcome.memoryUsedCount(),
                    outcome.memoryTokenCount(),
                    outcome.directAnswer(),
                    outcome.directAnswerCitationIds(),
                    outcome.content(),
                    outcome.citations().stream()
                            .map(ChatEvaluationGateway::citation)
                            .toList()
            );
        } catch (RuntimeException exception) {
            String code = exception instanceof ChatWorkflowException workflowError
                    ? workflowError.code()
                    : "EVALUATION_CHAT_FAILED";
            repository.failRun(
                    user.id(),
                    started.run().id(),
                    RunStatus.FAILED,
                    code,
                    "Evaluation Chat execution failed"
            );
            throw exception;
        }
    }

    static String evaluationTraceId(
            UUID evaluationRunId,
            UUID evaluationCaseId,
            int turn
    ) {
        String source = evaluationRunId + ":" + evaluationCaseId
                + ":" + turn;
        return "eval-" + UUID.nameUUIDFromBytes(
                source.getBytes(StandardCharsets.UTF_8)
        );
    }

    public ModelFaultVerification verifyModelTimeout(
            UUID evaluationDrillId,
            String question,
            String language,
            SearchService.EvaluationTarget target,
            PlatformUserPrincipal user
    ) {
        var session = repository.createEvaluationDrillSession(
                user.id(),
                "[EVAL-DRILL] " + evaluationDrillId,
                evaluationDrillId
        );
        StartedRun started = repository.startRun(
                user.id(),
                session.id(),
                new StartRunCommand(
                        question,
                        language,
                        "phase10-stategraph-v1",
                        "drill-" + evaluationDrillId,
                        GraphMode.HYBRID.name(),
                        AnswerStrategy.STANDARD.name()
                )
        ).orElseThrow();
        try {
            workflow.execute(new ChatWorkflow.RunInput(
                    user, started, question, language, target, true
            ));
            return new ModelFaultVerification(false, null);
        } catch (RuntimeException exception) {
            String code = exception instanceof ChatWorkflowException workflowError
                    ? workflowError.code()
                    : "EVALUATION_CHAT_FAILED";
            repository.failRun(
                    user.id(), started.run().id(), RunStatus.FAILED,
                    code, "Evaluation model timeout drill"
            );
            return new ModelFaultVerification(
                    "EVALUATION_MODEL_TIMEOUT".equals(code),
                    code
            );
        }
    }

    private static CitationReference citation(Citation citation) {
        return new CitationReference(
                citation.id(),
                citation.documentId(),
                citation.revisionId(),
                citation.childChunkId(),
                citation.sourceSpanId()
        );
    }

    public record EvaluationAnswer(
            UUID chatRunId,
            RunStatus status,
            String refusalCode,
            String graphModeRequested,
            String graphModeUsed,
            boolean graphDegraded,
            String graphDegradationCode,
            int mapCallCount,
            int reduceCallCount,
            int historyMessageCount,
            String queryProfileVersion,
            int plannerCallCount,
            int retrievalCallCount,
            int rerankCallCount,
            boolean queryDegraded,
            String queryDegradationCode,
            String routeSelectedMode,
            int routerCallCount,
            String routeReasonCode,
            boolean routeDegraded,
            String routeDegradationCode,
            int memoryInjectedCount,
            int memoryUsedCount,
            int memoryTokenCount,
            String directAnswer,
            List<UUID> directAnswerCitationIds,
            String answerContent,
            List<CitationReference> citations
    ) {
    }

    public record CitationReference(
            UUID id,
            UUID documentId,
            UUID revisionId,
            UUID childChunkId,
            UUID sourceSpanId
    ) {
    }

    public record ModelFaultVerification(
            boolean requestScopedAbort,
            String errorCode
    ) {
    }
}
