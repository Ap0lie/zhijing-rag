package com.example.rag.graph;

import com.example.rag.graph.GraphAssembler.ParentExtraction;
import com.example.rag.graph.GraphBuildContracts.ChildSource;
import com.example.rag.graph.GraphBuildContracts.GraphConfig;
import com.example.rag.graph.GraphBuildContracts.ParentSource;
import com.example.rag.graph.GraphBuildContracts.SourceDocument;
import com.example.rag.graph.GraphBuildContracts.SpanSource;
import com.example.rag.graph.GraphExtractionProvider.ExtractedEntity;
import com.example.rag.graph.GraphExtractionProvider.ExtractedMention;
import com.example.rag.graph.GraphExtractionProvider.ExtractedRelationship;
import com.example.rag.graph.GraphExtractionProvider.ExtractionResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GraphAssemblerTests {

    @Test
    void everyFactAndCommunityClaimKeepsTheExactChildSpanAnchor() {
        CommunityDetectionClient communities =
                mock(CommunityDetectionClient.class);
        when(communities.detect(
                anyList(),
                anyList(),
                anyString(),
                anyLong(),
                anyDouble()
        )).thenReturn(Map.of("n0", 0, "n1", 0));
        GraphAssembler assembler = new GraphAssembler(
                communities,
                new GraphProperties()
        );
        UUID documentId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        UUID spanId = UUID.randomUUID();
        String childText = "Alpha uses Beta.";
        ChildSource child = new ChildSource(
                childId,
                0,
                childText,
                "Architecture",
                GraphAssembler.sha256(childText),
                List.of(new SpanSource(
                        spanId,
                        0,
                        3,
                        3,
                        20,
                        20 + childText.length(),
                        0,
                        childText.length(),
                        GraphAssembler.sha256(childText)
                ))
        );
        ParentSource parent = new ParentSource(
                parentId,
                0,
                childText,
                "Architecture",
                GraphAssembler.sha256(childText),
                List.of(child)
        );
        SourceDocument document = new SourceDocument(
                documentId,
                "Architecture",
                revisionId,
                1,
                1,
                List.of(parent)
        );
        ExtractionResult extraction = new ExtractionResult(
                List.of(
                        new ExtractedEntity(
                                "Alpha",
                                "CONCEPT",
                                "source",
                                List.of(),
                                List.of(new ExtractedMention(
                                        childId,
                                        "Alpha"
                                ))
                        ),
                        new ExtractedEntity(
                                "Beta",
                                "CONCEPT",
                                "target",
                                List.of(),
                                List.of(new ExtractedMention(
                                        childId,
                                        "Beta"
                                ))
                        )
                ),
                List.of(new ExtractedRelationship(
                        "Alpha",
                        "Beta",
                        "USES",
                        "Alpha uses Beta",
                        childId,
                        childText
                ))
        );

        var result = assembler.assemble(
                1,
                config(),
                List.of(document),
                Map.of(parentId, new ParentExtraction(
                        UUID.randomUUID(),
                        GraphAssembler.sha256("input"),
                        extraction
                )),
                List.of(),
                0,
                1
        );

        assertThat(result.entities()).hasSize(2);
        assertThat(result.mentions()).hasSize(2)
                .allMatch(item -> item.childChunkId().equals(childId))
                .allMatch(item -> item.sourceSpanId().equals(spanId));
        assertThat(result.relationships()).hasSize(1);
        assertThat(result.relationshipEvidence()).singleElement()
                .satisfies(item -> {
                    assertThat(item.childChunkId()).isEqualTo(childId);
                    assertThat(item.sourceSpanId()).isEqualTo(spanId);
                });
        assertThat(result.communityClaims()).singleElement()
                .satisfies(claim -> assertThat(
                        claim.relationshipEvidenceId()
                ).isEqualTo(result.relationshipEvidence().getFirst().id()));
    }

    private static GraphConfig config() {
        return new GraphConfig(
                "phase8-test",
                "local-test",
                "revision-1",
                GraphExtractionProvider.PROMPT_VERSION,
                GraphExtractionProvider.SCHEMA_VERSION,
                "unicode-nfkc-lower-v1",
                "phase8-baseline-rules-v1",
                "leidenalg",
                "0.10.2",
                42,
                1,
                "test",
                Instant.now()
        );
    }
}
