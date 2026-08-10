package com.example.rag.chat;

import com.example.rag.chat.ChatModelProvider.ModelHistoryMessage;
import com.example.rag.chat.ChatPersistenceContracts.ChatMessage;
import com.example.rag.chat.ChatPersistenceContracts.MessageRole;
import com.example.rag.chat.ChatPersistenceContracts.RunHistorySnapshot;
import com.example.rag.chat.ChatPersistenceContracts.RunStatus;
import com.example.rag.chat.ChatPersistenceContracts.StartedRun;
import com.example.rag.chat.ChatPersistenceRepository.HistoryEntry;
import com.example.rag.chat.QueryIntelligenceContracts.ProfileView;
import com.example.rag.security.PlatformUserPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(
        prefix = "rag.chat",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
class ChatHistoryWindowService {

    private final ChatPersistenceRepository repository;
    private final QueryIntelligenceProfileService profiles;
    private final AnswerSourceService answerSources;
    private final ObjectMapper objectMapper;

    ChatHistoryWindowService(
            ChatPersistenceRepository repository,
            QueryIntelligenceProfileService profiles,
            AnswerSourceService answerSources,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.profiles = profiles;
        this.answerSources = answerSources;
        this.objectMapper = objectMapper;
    }

    HistoryWindow build(
            PlatformUserPrincipal user,
            StartedRun started
    ) {
        String version = started.run().queryIntelligenceProfileVersion();
        if (version == null) {
            return HistoryWindow.off();
        }
        ProfileView profile = profiles.find(version);
        List<String> trimReasons = new ArrayList<>();
        if (!profile.enabled()) {
            trimReasons.add("QUERY_PROFILE_DISABLED");
            return snapshot(started, profile, List.of(), trimReasons);
        }

        int beforeSequence = started.requestMessage().sequenceNumber();
        int candidateLimit = Math.min(
                48, profile.historyMessageLimit() * 4
        );
        List<HistoryEntry> recent = repository.recentHistory(
                user.id(),
                started.run().sessionId(),
                beforeSequence,
                candidateLimit
        );
        if (recent.size() == candidateLimit) {
            addReason(trimReasons, "HISTORY_CANDIDATE_LIMIT");
        }
        Map<UUID, AnswerSourceService.RunSources> sourcesByRun =
                answerSources.load(
                        user,
                        recent.stream()
                                .filter(entry -> entry.runId() != null
                                        && entry.runStatus()
                                        == RunStatus.COMPLETED)
                                .map(HistoryEntry::runId)
                                .distinct()
                                .toList()
                );
        List<HistoryCandidate> eligible = new ArrayList<>();
        for (HistoryEntry entry : recent) {
            ChatMessage message = entry.message();
            if (message.role() == MessageRole.USER) {
                eligible.add(candidate(message));
                continue;
            }
            if (message.role() != MessageRole.ASSISTANT) {
                continue;
            }
            if (entry.runId() == null
                    || entry.runStatus() != RunStatus.COMPLETED
                    && entry.runStatus() != RunStatus.REFUSED) {
                continue;
            }
            if (entry.runStatus() == RunStatus.COMPLETED) {
                AnswerSourceService.RunSources sourceStatus =
                        sourcesByRun.getOrDefault(
                                entry.runId(),
                                AnswerSourceService.RunSources.invalid()
                        );
                if (!sourceStatus.current()) {
                    addReason(
                            trimReasons,
                            "ASSISTANT_CITATION_OR_MEMORY_REVOKED"
                    );
                    continue;
                }
                if (sourceStatus.usedMemory()) {
                    addReason(trimReasons, "ASSISTANT_MEMORY_EXCLUDED");
                    continue;
                }
            }
            eligible.add(candidate(message));
        }

        int limit = profile.historyMessageLimit();
        while (eligible.size() > limit) {
            eligible.removeFirst();
            addReason(trimReasons, "HISTORY_MESSAGE_LIMIT");
        }
        int tokenBudget = profile.effectiveHistoryTokenBudget();
        int tokens = eligible.stream()
                .mapToInt(HistoryCandidate::tokens)
                .sum();
        while (!eligible.isEmpty() && tokens > tokenBudget) {
            tokens -= eligible.removeFirst().tokens();
            addReason(trimReasons, "HISTORY_TOKEN_BUDGET");
        }
        if (!eligible.isEmpty()
                && eligible.getFirst().message().role()
                == MessageRole.ASSISTANT) {
            eligible.removeFirst();
            addReason(trimReasons, "HISTORY_ORPHAN_ASSISTANT");
        }
        return snapshot(started, profile, eligible, trimReasons);
    }

    private HistoryWindow snapshot(
            StartedRun started,
            ProfileView profile,
            List<HistoryCandidate> candidates,
            List<String> trimReasons
    ) {
        List<UUID> ids = candidates.stream()
                .map(candidate -> candidate.message().id())
                .toList();
        List<ModelHistoryMessage> messages = candidates.stream()
                .map(candidate -> new ModelHistoryMessage(
                        candidate.message().role().name().toLowerCase(),
                        candidate.message().content()
                ))
                .toList();
        int tokens = candidates.stream()
                .mapToInt(HistoryCandidate::tokens)
                .sum();
        String hash = hash(Map.of(
                "profileVersion", profile.version(),
                "counterVersion", profile.tokenCounterVersion(),
                "messages", candidates.stream()
                        .map(candidate -> Map.of(
                                "id", candidate.message().id(),
                                "role", candidate.message().role(),
                                "content", candidate.message().content()
                        ))
                        .toList()
        ));
        RunHistorySnapshot persisted = new RunHistorySnapshot(
                json(ids),
                hash,
                profile.tokenCounterVersion(),
                tokens,
                json(trimReasons)
        );
        repository.recordHistorySnapshot(
                started.run().ownerUserId(),
                started.run().id(),
                persisted
        );
        return new HistoryWindow(
                profile.version(),
                List.copyOf(messages),
                List.copyOf(ids),
                hash,
                profile.tokenCounterVersion(),
                tokens,
                List.copyOf(trimReasons)
        );
    }

    private HistoryCandidate candidate(ChatMessage message) {
        try {
            byte[] serialized = objectMapper.writeValueAsBytes(Map.of(
                    "role", message.role().name().toLowerCase(),
                    "content", message.content()
            ));
            return new HistoryCandidate(
                    message,
                    Math.addExact(serialized.length, 16)
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Unable to count history message", exception
            );
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Unable to serialize history snapshot", exception
            );
        }
    }

    private String hash(Object value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(json(value).getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static void addReason(List<String> reasons, String value) {
        if (!reasons.contains(value)) {
            reasons.add(value);
        }
    }

    record HistoryWindow(
            String profileVersion,
            List<ModelHistoryMessage> messages,
            List<UUID> messageIds,
            String snapshotHash,
            String counterVersion,
            int tokenCount,
            List<String> trimReasons
    ) {
        static HistoryWindow off() {
            return new HistoryWindow(
                    null, List.of(), List.of(), null,
                    null, 0, List.of()
            );
        }
    }

    private record HistoryCandidate(ChatMessage message, int tokens) {
    }
}
