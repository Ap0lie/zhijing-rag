package com.example.rag.search;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelCircuitBreakersTests {

    @Test
    void consecutiveFailuresOpenForTheConfiguredWindowWithoutRetrying() {
        SearchProperties properties = new SearchProperties();
        properties.setModelFailureThreshold(2);
        properties.setModelCircuitBreakDuration(Duration.ofSeconds(30));
        ModelCircuitBreakers breakers = new ModelCircuitBreakers(properties);
        AtomicInteger calls = new AtomicInteger();

        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> breakers.call(
                    ModelCircuitBreakers.ModelType.RERANK,
                    () -> {
                        calls.incrementAndGet();
                        throw new IllegalStateException("down");
                    }
            )).isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> breakers.call(
                ModelCircuitBreakers.ModelType.RERANK,
                () -> {
                    calls.incrementAndGet();
                    return "unexpected";
                }
        )).isInstanceOf(ModelCircuitOpenException.class);
        assertThat(calls).hasValue(2);

        breakers.reset();
        assertThat(breakers.call(
                ModelCircuitBreakers.ModelType.RERANK,
                () -> {
                    calls.incrementAndGet();
                    return "ok";
                }
        )).isEqualTo("ok");
        assertThat(calls).hasValue(3);
    }
}
