package com.example.rag.search;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

@Component
@ConditionalOnProperty(prefix = "rag.search", name = "enabled", havingValue = "true")
final class ModelCircuitBreakers {

    enum ModelType {
        EMBEDDING,
        RERANK
    }

    private final SearchProperties properties;
    private final Map<ModelType, Circuit> circuits = new EnumMap<>(ModelType.class);

    ModelCircuitBreakers(SearchProperties properties) {
        this.properties = properties;
        for (ModelType type : ModelType.values()) {
            circuits.put(type, new Circuit());
        }
    }

    <T> T call(ModelType type, Supplier<T> operation) {
        Circuit circuit = circuits.get(type);
        long now = System.nanoTime();
        if (circuit.openUntilNanos.get() - now > 0) {
            throw new ModelCircuitOpenException(type);
        }
        try {
            T result = operation.get();
            circuit.failures.set(0);
            circuit.openUntilNanos.set(0);
            return result;
        } catch (RuntimeException failure) {
            if (circuit.failures.incrementAndGet() >= properties.getModelFailureThreshold()) {
                circuit.openUntilNanos.set(
                        now + properties.getModelCircuitBreakDuration().toNanos()
                );
                circuit.failures.set(0);
            }
            throw failure;
        }
    }

    void reset() {
        circuits.values().forEach(Circuit::reset);
    }

    private static final class Circuit {

        private final AtomicInteger failures = new AtomicInteger();
        private final AtomicLong openUntilNanos = new AtomicLong();

        void reset() {
            failures.set(0);
            openUntilNanos.set(0);
        }
    }
}

final class ModelCircuitOpenException extends RuntimeException {

    ModelCircuitOpenException(ModelCircuitBreakers.ModelType type) {
        super(type + " circuit is open");
    }
}
