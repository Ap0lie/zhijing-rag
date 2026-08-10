package com.example.rag.pipeline;

import com.example.rag.persistence.DocumentFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;

@Service
public class PipelineWorkerHealthService {

    private static final Duration MINIMUM_FRESHNESS = Duration.ofSeconds(30);

    private final JdbcTemplate jdbc;
    private final PipelineProperties properties;
    private final ParserRegistry parsers;

    public PipelineWorkerHealthService(
            JdbcTemplate jdbc,
            PipelineProperties properties,
            ParserRegistry parsers
    ) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.parsers = parsers;
    }

    @Transactional
    public void publishCapabilities() {
        if (!properties.workerEnabled()
                || properties.workerStage()
                != com.example.rag.persistence.PipelineStage.PARSE) {
            return;
        }
        List<ParserProviderKind> providers = List.of(DocumentFormat.values())
                .stream()
                .flatMap(format -> parsers.providersFor(format).stream())
                .distinct()
                .sorted(Comparator.comparing(Enum::name))
                .toList();
        jdbc.update(
                "DELETE FROM pipeline_worker_capabilities WHERE worker_id = ?",
                properties.workerId()
        );
        providers.forEach(provider -> jdbc.update(
                """
                INSERT INTO pipeline_worker_capabilities (
                    worker_id, parser_provider, last_seen_at
                ) VALUES (?, ?, CURRENT_TIMESTAMP)
                """,
                properties.workerId(),
                provider.name()
        ));
    }

    public boolean isParserAvailable(ParserProviderKind provider) {
        Duration freshness = properties.heartbeatInterval().multipliedBy(3);
        if (freshness.compareTo(MINIMUM_FRESHNESS) < 0) {
            freshness = MINIMUM_FRESHNESS;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM pipeline_worker_capabilities
                    WHERE parser_provider = ?
                      AND last_seen_at >= CURRENT_TIMESTAMP
                          - (? * INTERVAL '1 millisecond')
                )
                """,
                Boolean.class,
                provider.name(),
                freshness.toMillis()
        ));
    }
}
