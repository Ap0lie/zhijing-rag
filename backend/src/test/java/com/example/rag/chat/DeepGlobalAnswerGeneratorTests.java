package com.example.rag.chat;

import com.example.rag.chat.ChatModelProvider.AnswerExecution;
import com.example.rag.chat.ChatModelProvider.ModelAnswer;
import com.example.rag.chat.ChatModelProvider.ModelEvidence;
import com.example.rag.chat.ChatModelProvider.ModelHistoryMessage;
import com.example.rag.chat.ChatModelProvider.ModelMemory;
import com.example.rag.chat.ChatModelProvider.ModelSegment;
import com.example.rag.chat.ChatPersistenceContracts.AnswerStrategy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeepGlobalAnswerGeneratorTests {

    @Test
    void capsParallelMapsAndUsesOneStructuredReduce() {
        List<ModelEvidence> evidence = evidence(9);
        AtomicInteger mapCalls = new AtomicInteger();
        AtomicInteger reduceCalls = new AtomicInteger();
        ChatModelProvider provider = new ChatModelProvider() {
            @Override
            public ModelAnswer answer(
                    String question,
                    List<ModelEvidence> items
            ) {
                mapCalls.incrementAndGet();
                UUID citationId = items.getFirst().citationId();
                return DeepGlobalAnswerGeneratorTests.answer(
                        "map",
                        citationId
                );
            }

            @Override
            public ModelAnswer reduce(
                    String question,
                    List<ModelEvidence> items,
                    List<ModelAnswer> mapAnswers
            ) {
                reduceCalls.incrementAndGet();
                assertThat(mapAnswers).hasSize(8);
                return DeepGlobalAnswerGeneratorTests.answer(
                        "reduced",
                        items.getFirst().citationId()
                );
            }
        };

        AnswerExecution result = DeepGlobalAnswerGenerator.answer(
                provider,
                "Summarize",
                evidence,
                Duration.ofSeconds(2)
        );

        assertThat(result.strategyRequested())
                .isEqualTo(AnswerStrategy.DEEP_GLOBAL);
        assertThat(result.strategyUsed())
                .isEqualTo(AnswerStrategy.DEEP_GLOBAL);
        assertThat(result.mapCallCount()).isEqualTo(8);
        assertThat(result.reduceCallCount()).isEqualTo(1);
        assertThat(mapCalls).hasValue(8);
        assertThat(reduceCalls).hasValue(1);
    }

    @Test
    void invalidMapCitationCannotEnterReduceAndFallsBackToStandard() {
        List<ModelEvidence> evidence = evidence(2);
        AtomicInteger reduceCalls = new AtomicInteger();
        ChatModelProvider provider = new ChatModelProvider() {
            @Override
            public ModelAnswer answer(
                    String question,
                    List<ModelEvidence> items
            ) {
                if (items.size() == 1) {
                    return DeepGlobalAnswerGeneratorTests.answer(
                            "unsupported",
                            UUID.randomUUID()
                    );
                }
                return DeepGlobalAnswerGeneratorTests.answer(
                        "standard",
                        items.getFirst().citationId()
                );
            }

            @Override
            public ModelAnswer reduce(
                    String question,
                    List<ModelEvidence> items,
                    List<ModelAnswer> mapAnswers
            ) {
                reduceCalls.incrementAndGet();
                return DeepGlobalAnswerGeneratorTests.answer(
                        "should not run",
                        items.getFirst().citationId()
                );
            }
        };

        AnswerExecution result = DeepGlobalAnswerGenerator.answer(
                provider,
                "Summarize",
                evidence,
                Duration.ofSeconds(2)
        );

        assertThat(result.strategyUsed()).isEqualTo(AnswerStrategy.STANDARD);
        assertThat(result.mapCallCount()).isEqualTo(2);
        assertThat(result.reduceCallCount()).isZero();
        assertThat(result.fallbackCode())
                .isEqualTo("DEEP_GLOBAL_MAP_UNAVAILABLE");
        assertThat(reduceCalls).hasValue(0);
    }

    @Test
    void hardDeadlineCancelsSlowMapCalls() {
        ChatModelProvider provider = (question, evidence) -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ChatModelException(
                        "CHAT_CANCELLED",
                        "cancelled",
                        exception
                );
            }
            return answer("late", evidence.getFirst().citationId());
        };

        assertThatThrownBy(() -> DeepGlobalAnswerGenerator.answer(
                provider,
                "Summarize",
                evidence(1),
                Duration.ofMillis(50)
        )).isInstanceOfSatisfying(ChatModelException.class, exception ->
                assertThat(exception.code()).isEqualTo("DEEP_GLOBAL_TIMEOUT"));
    }

    @Test
    void preservesValidatedMemoryUsageThroughMapAndReduce() {
        List<ModelEvidence> evidence = evidence(1);
        UUID memoryId = UUID.randomUUID();
        List<ModelMemory> memories = List.of(new ModelMemory(
                memoryId,
                "USER_PREFERENCE",
                "回答语言",
                "默认使用简体中文"
        ));
        ChatModelProvider provider = new ChatModelProvider() {
            @Override
            public ModelAnswer answer(
                    String question,
                    List<ModelEvidence> items
            ) {
                throw new AssertionError("必须使用带 Memory 的调用路径");
            }

            @Override
            public ModelAnswer answer(
                    String question,
                    List<ModelEvidence> items,
                    List<ModelHistoryMessage> history,
                    List<ModelMemory> modelMemories
            ) {
                assertThat(modelMemories).containsExactlyElementsOf(memories);
                return DeepGlobalAnswerGeneratorTests.answer(
                        "map",
                        items.getFirst().citationId(),
                        memoryId
                );
            }

            @Override
            public ModelAnswer reduce(
                    String question,
                    List<ModelEvidence> items,
                    List<ModelAnswer> mapAnswers,
                    List<ModelHistoryMessage> history,
                    List<ModelMemory> modelMemories
            ) {
                assertThat(mapAnswers.getFirst().segments().getFirst()
                        .memoryIds()).containsExactly(memoryId);
                return DeepGlobalAnswerGeneratorTests.answer(
                        "reduced",
                        items.getFirst().citationId(),
                        memoryId
                );
            }
        };

        AnswerExecution result = DeepGlobalAnswerGenerator.answer(
                provider,
                "Summarize",
                evidence,
                List.of(),
                memories,
                Duration.ofSeconds(2),
                (strategy, mapCalls, reduceCalls) -> {
                }
        );

        assertThat(result.answer().segments()).singleElement()
                .satisfies(segment -> assertThat(segment.memoryIds())
                        .containsExactly(memoryId));
    }

    private static List<ModelEvidence> evidence(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> new ModelEvidence(
                        UUID.randomUUID(),
                        "Document " + index,
                        1,
                        List.of("Section"),
                        1,
                        1,
                        "Evidence " + index,
                        null
                ))
                .toList();
    }

    private static ModelAnswer answer(String text, UUID citationId) {
        return new ModelAnswer(
                List.of(new ModelSegment(text, List.of(citationId))),
                null
        );
    }

    private static ModelAnswer answer(
            String text,
            UUID citationId,
            UUID memoryId
    ) {
        return new ModelAnswer(
                List.of(new ModelSegment(
                        text,
                        List.of(citationId),
                        List.of(memoryId)
                )),
                null
        );
    }
}
