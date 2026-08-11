package com.example.rag.chat;

import com.example.rag.chat.ChatModelProvider.ContextSummaryResult;
import com.example.rag.chat.ChatPersistenceContracts.RunCompletion;
import com.example.rag.chat.ChatPersistenceContracts.RunStatus;
import com.example.rag.chat.ChatPersistenceContracts.StartRunCommand;
import com.example.rag.persistence.UserRepository;
import com.example.rag.security.PlatformUserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "rag.chat.llm.enabled=true",
        "rag.chat.llm.local-endpoint=true",
        "rag.chat.llm.model=compression-test-model",
        "rag.chat.llm.model-revision=compression-test-v1",
        "rag.chat.context-compression.enabled=true",
        "rag.chat.context-compression.worker-enabled=true"
})
@Import({
        ChatPersistenceRepository.class,
        ContextCompressionService.class,
        ContextCompressionIntegrationTests.Configuration.class
})
class ContextCompressionIntegrationTests {

    @Autowired
    private ContextCompressionService compression;

    @Autowired
    private ChatPersistenceRepository chats;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private AnswerSourceService answerSources;

    @MockitoBean
    private ChatModelProvider model;

    private PlatformUserPrincipal owner;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM chat_context_summary_jobs");
        UUID ownerId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO users (id, username, password_hash, role)
                VALUES (?, ?, 'test-password-hash', 'USER')
                """,
                ownerId, "compression-owner-" + ownerId
        );
        owner = PlatformUserPrincipal.from(
                users.findById(ownerId).orElseThrow()
        );
        when(answerSources.load(any(), anyList())).thenReturn(Map.of());
        when(model.summarizeContext(any(), anyList(), anyInt()))
                .thenReturn(new ContextSummaryResult(
                        "{\"topic\":\"rolling\",\"userGoals\":[],"
                                + "\"constraints\":[],\"entityBindings\":[],"
                                + "\"decisions\":[],\"openQuestions\":[],"
                                + "\"priorResults\":[]}"
                ));
    }

    @Test
    void rollsForwardWithoutBlockingAndCascadesWithTheSession() {
        var session = chats.createSession(owner.id(), "Long conversation");
        addRefusedRuns(session.id(), 7, 1);

        ContextCompressionService.ContextStatus pending =
                compression.prepare(owner, session.id());
        assertThat(pending.status())
                .as(pending.reasonCode())
                .isEqualTo("PENDING");

        ContextCompressionService.ClaimedJob first = compression.claim();
        assertThat(first).isNotNull();
        compression.process(first);

        ContextCompressionService.ContextStatus firstStatus =
                compression.status(owner, session.id());
        assertThat(firstStatus.status()).isEqualTo("USED");
        assertThat(firstStatus.coveredMessageCount()).isEqualTo(10);
        assertThat(firstStatus.tailMessageCount()).isEqualTo(4);

        addRefusedRuns(session.id(), 3, 8);
        compression.prepare(owner, session.id());
        ContextCompressionService.ClaimedJob second = compression.claim();
        assertThat(second).isNotNull();
        assertThat(second.parentSummaryId()).isNotNull();
        compression.process(second);

        ContextCompressionService.ContextStatus rolled =
                compression.status(owner, session.id());
        assertThat(rolled.status()).isEqualTo("USED");
        assertThat(rolled.coveredMessageCount()).isEqualTo(16);
        assertThat(rolled.tailMessageCount()).isEqualTo(4);
        assertThat(rolled.estimatedSavedTokens()).isPositive();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM chat_context_summaries WHERE session_id = ?",
                Integer.class, session.id()
        )).isEqualTo(2);
        assertThat(chats.deleteSession(owner.id(), session.id())).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM chat_context_summaries WHERE session_id = ?",
                Integer.class, session.id()
        )).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM chat_context_summary_jobs WHERE session_id = ?",
                Integer.class, session.id()
        )).isZero();
        assertThatThrownBy(() -> compression.status(owner, session.id()))
                .isInstanceOf(com.example.rag.common.ApiException.class);
    }

    @Test
    void catchesUpAnExistingConversationLargerThanTheReadWindow() {
        var session = chats.createSession(owner.id(), "Existing long conversation");
        addRefusedRuns(session.id(), 30, 1);

        assertThat(compression.prepare(owner, session.id()).status())
                .isEqualTo("PENDING");
        ContextCompressionService.ClaimedJob first = compression.claim();
        assertThat(first).isNotNull();
        compression.process(first);

        ContextCompressionService.ContextStatus catchingUp =
                compression.status(owner, session.id());
        assertThat(catchingUp.status()).isEqualTo("PENDING");
        assertThat(catchingUp.reasonCode())
                .isEqualTo("CONTEXT_SUMMARY_CATCHING_UP");

        ContextCompressionService.ClaimedJob second = compression.claim();
        assertThat(second).isNotNull();
        compression.process(second);

        ContextCompressionService.ContextStatus ready =
                compression.status(owner, session.id());
        assertThat(ready.status()).isEqualTo("USED");
        assertThat(ready.coveredMessageCount()).isEqualTo(56);
        assertThat(ready.tailMessageCount()).isEqualTo(4);
    }

    private void addRefusedRuns(UUID sessionId, int count, int start) {
        for (int index = start; index < start + count; index++) {
            var run = chats.startRun(
                    owner.id(), sessionId,
                    new StartRunCommand(
                            "Question " + index, "en", "phase22-v1",
                            "compression-" + UUID.randomUUID()
                    )
            ).orElseThrow();
            assertThat(chats.finishRun(
                    owner.id(), run.run().id(),
                    new RunCompletion(
                            RunStatus.REFUSED,
                            "No grounded answer " + index,
                            "en", null, "{}", "BM25",
                            null, null, null, null,
                            "HYBRID", "HYBRID", false, null,
                            null, null, "STANDARD", "STANDARD",
                            0, 0, "[]", "[]", "[]"
                    )
            )).isTrue();
        }
    }

    @TestConfiguration
    static class Configuration {

        @Bean
        ChatProperties chatProperties() {
            ChatProperties properties = new ChatProperties();
            properties.getLlm().setEnabled(true);
            properties.getLlm().setLocalEndpoint(true);
            properties.getLlm().setModel("compression-test-model");
            properties.getLlm().setModelRevision("compression-test-v1");
            properties.getContextCompression().setEnabled(true);
            properties.getContextCompression().setWorkerEnabled(true);
            return properties;
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
