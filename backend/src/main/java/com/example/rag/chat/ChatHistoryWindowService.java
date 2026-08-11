package com.example.rag.chat;

import com.example.rag.chat.ChatModelProvider.ModelHistoryMessage;
import com.example.rag.chat.ChatPersistenceContracts.RunHistorySnapshot;
import com.example.rag.chat.ChatPersistenceContracts.StartedRun;
import com.example.rag.chat.QueryIntelligenceContracts.ProfileView;
import com.example.rag.chat.ContextCompressionService.HistoryContext;
import com.example.rag.chat.ContextCompressionService.SourceMessage;
import com.example.rag.chat.ContextCompressionService.SummaryArtifact;
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
    private final ContextCompressionService compression;
    private final ObjectMapper objectMapper;

    ChatHistoryWindowService(
            ChatPersistenceRepository repository,
            QueryIntelligenceProfileService profiles,
            ContextCompressionService compression,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.profiles = profiles;
        this.compression = compression;
        this.objectMapper = objectMapper;
    }

    HistoryWindow build(
            PlatformUserPrincipal user,
            StartedRun started
    ) {
        String version = started.run().queryIntelligenceProfileVersion();
        ProfileView profile = version == null ? null : profiles.find(version);
        int limit = profile == null ? 12 : profile.historyMessageLimit();
        int tokenBudget = profile == null
                ? compression.effectiveHistoryBudget()
                : profile.effectiveHistoryTokenBudget();
        try {
            compression.prepare(user, started.run().sessionId());
        } catch (RuntimeException ignored) {
            // Compression is asynchronous and must never block the answer.
        }
        HistoryContext context = compression.historyContext(
                user,
                started.run().sessionId(),
                started.requestMessage().sequenceNumber(),
                limit,
                tokenBudget
        );
        return snapshot(started, profile, context);
    }

    private HistoryWindow snapshot(
            StartedRun started,
            ProfileView profile,
            HistoryContext context
    ) {
        SummaryArtifact summary = context.summary();
        List<SourceMessage> raw = context.rawMessages();
        List<UUID> ids = raw.stream()
                .map(SourceMessage::id)
                .toList();
        List<ModelHistoryMessage> messages = new ArrayList<>();
        if (summary != null) {
            messages.add(new ModelHistoryMessage(
                    "summary", summary.summaryJson()
            ));
        }
        raw.stream().map(source -> new ModelHistoryMessage(
                source.role().name().toLowerCase(), source.content()
        )).forEach(messages::add);
        List<String> trimReasons = context.reasonCode() == null
                ? List.of() : List.of(context.reasonCode());
        String profileVersion = profile == null ? null : profile.version();
        String hash = hash(Map.of(
                "queryProfileVersion", profileVersion == null
                        ? "NONE" : profileVersion,
                "contextPolicyVersion", ContextCompressionService.POLICY_VERSION,
                "counterVersion", ContextCompressionService.COUNTER_VERSION,
                "summaryContentHash", summary == null
                        ? "NONE" : summary.contentHash(),
                "messages", raw.stream()
                        .map(source -> Map.of(
                                "id", source.id(),
                                "role", source.role(),
                                "contentHash", source.contentHash()
                        ))
                        .toList()
        ));
        RunHistorySnapshot persisted = new RunHistorySnapshot(
                json(ids),
                hash,
                ContextCompressionService.COUNTER_VERSION,
                context.tokenCount(),
                json(trimReasons),
                ContextCompressionService.POLICY_VERSION,
                summary == null ? null : summary.id(),
                summary == null ? 0 : summary.tokenCount(),
                summary == null ? 0 : summary.sourceCount(),
                context.status(),
                context.reasonCode()
        );
        repository.recordHistorySnapshot(
                started.run().ownerUserId(),
                started.run().id(),
                persisted
        );
        return new HistoryWindow(
                profileVersion,
                ContextCompressionService.POLICY_VERSION,
                List.copyOf(messages),
                List.copyOf(ids),
                hash,
                ContextCompressionService.COUNTER_VERSION,
                context.tokenCount(),
                List.copyOf(trimReasons),
                summary == null ? null : summary.id(),
                summary == null ? 0 : summary.sourceCount(),
                summary == null ? 0 : summary.tokenCount(),
                raw.size(),
                context.status(),
                context.reasonCode()
        );
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

    record HistoryWindow(
            String profileVersion,
            String compressionPolicyVersion,
            List<ModelHistoryMessage> messages,
            List<UUID> messageIds,
            String snapshotHash,
            String counterVersion,
            int tokenCount,
            List<String> trimReasons,
            UUID summaryId,
            int coveredMessageCount,
            int summaryTokenCount,
            int tailMessageCount,
            String compressionStatus,
            String compressionReasonCode
    ) {
        static HistoryWindow off() {
            return new HistoryWindow(
                    null, null, List.of(), List.of(), null,
                    null, 0, List.of(), null, 0, 0, 0,
                    "NOT_NEEDED", null
            );
        }
    }
}
