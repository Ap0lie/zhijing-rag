package com.example.rag.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GraphPhase10ContractTests {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void publicGlobalSuiteIsVersionedAndDoesNotInventMeasuredQuality()
            throws Exception {
        JsonNode dataset;
        try (InputStream input = getClass().getResourceAsStream(
                "/graph-global-golden/v1/dataset.json"
        )) {
            assumeTrue(
                    input != null,
                    "External graph evaluation resources are intentionally not checked in"
            );
            dataset = json.readTree(input);
        }

        assertThat(dataset.path("suiteVersion").asText())
                .isEqualTo("graph-global-golden-v1");
        assertThat(dataset.path("status").asText())
                .isEqualTo("PUBLIC_CANDIDATES_SELECTED");
        assertThat(dataset.path("selection").path("caseCount").asInt())
                .isEqualTo(30);
        assertThat(dataset.path("cases")).hasSize(30);
        assertThat(dataset.path("sources").path("multiHopRag")
                .path("revision").asText()).isNotBlank();
        assertThat(dataset.path("sources").path("xrag")
                .path("revision").asText()).isNotBlank();

        ObjectNode report = json.createObjectNode();
        report.put("suiteVersion", "graph-global-golden-v1");
        report.put("status", "PUBLIC_CANDIDATES_SELECTED");
        report.put("caseCount", 30);
        report.put("quality", "NOT_MEASURED");
        report.put("latency", "NOT_MEASURED");
        report.put(
                "nextAction",
                "Map public Evidence to local Document/Revision/Child/SourceSpan"
        );
        Path output = Path.of(
                "target",
                "phase10-reports",
                "global-graph-contract.json"
        );
        Files.createDirectories(output.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(
                output.toFile(),
                report
        );
    }
}
