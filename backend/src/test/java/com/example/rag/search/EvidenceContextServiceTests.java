package com.example.rag.search;

import com.example.rag.search.EvidenceContextService.ContextRow;
import com.example.rag.search.EvidenceContextService.ContextSeed;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceContextServiceTests {

    private final EvidenceContextService service = new EvidenceContextService(null);

    @Test
    void childEvidenceIsAlwaysRetainedWhileParentLimitsAreDeterministic() {
        UUID firstDocument = UUID.randomUUID();
        UUID secondDocument = UUID.randomUUID();
        List<ContextSeed> seeds = new ArrayList<>();
        Map<UUID, ContextRow> rows = new LinkedHashMap<>();

        for (int index = 0; index < 6; index++) {
            UUID childId = UUID.randomUUID();
            UUID revisionId = UUID.randomUUID();
            UUID documentId = index < 3 ? firstDocument : secondDocument;
            String child = "child-" + index;
            String parent = "before ".repeat(400) + child + " after ".repeat(400);
            seeds.add(new ContextSeed(childId, documentId, revisionId, child));
            rows.put(childId, new ContextRow(
                    childId,
                    documentId,
                    revisionId,
                    child,
                    100,
                    UUID.randomUUID(),
                    parent,
                    "Section " + index,
                    index + 1,
                    index + 1,
                    1_200
            ));
        }

        var plan = service.plan(seeds, rows, 6_000);

        assertThat(plan.materials()).hasSize(6);
        assertThat(plan.childTokens()).isEqualTo(600);
        assertThat(plan.parentCount()).isEqualTo(4);
        assertThat(plan.parentTokens()).isEqualTo(3_200);
        assertThat(plan.totalTokens()).isLessThanOrEqualTo(6_000);
        assertThat(plan.materials().values())
                .filteredOn(material -> material.parent() != null)
                .allSatisfy(material -> {
                    assertThat(material.parent().contributedTokens()).isLessThanOrEqualTo(800);
                    assertThat(material.parent().truncated()).isTrue();
                });
        assertThat(plan.trimReasons()).contains("PER_DOCUMENT_PARENT_LIMIT");
    }

    @Test
    void sharedParentConsumesBudgetOnceAndMissingParentFallsBackToChild() {
        UUID documentId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID firstChild = UUID.randomUUID();
        UUID secondChild = UUID.randomUUID();
        UUID missingChild = UUID.randomUUID();
        String parentText = "prefix first child middle second child suffix";
        List<ContextSeed> seeds = List.of(
                new ContextSeed(firstChild, documentId, revisionId, "first child"),
                new ContextSeed(secondChild, documentId, revisionId, "second child"),
                new ContextSeed(missingChild, documentId, revisionId, "missing child")
        );
        Map<UUID, ContextRow> rows = Map.of(
                firstChild,
                row(firstChild, documentId, revisionId, parentId, "first child", parentText),
                secondChild,
                row(secondChild, documentId, revisionId, parentId, "second child", parentText)
        );

        var plan = service.plan(seeds, rows, 6_000);

        assertThat(plan.materials()).hasSize(3);
        assertThat(plan.parentCount()).isOne();
        assertThat(plan.parentTokens()).isEqualTo(50);
        assertThat(plan.materials().get(firstChild).parent())
                .isSameAs(plan.materials().get(secondChild).parent());
        assertThat(plan.materials().get(missingChild).childText()).isEqualTo("missing child");
        assertThat(plan.materials().get(missingChild).parent()).isNull();
        assertThat(plan.trimReasons()).contains("PARENT_MISSING");
    }

    private static ContextRow row(
            UUID childId,
            UUID documentId,
            UUID revisionId,
            UUID parentId,
            String child,
            String parent
    ) {
        return new ContextRow(
                childId,
                documentId,
                revisionId,
                child,
                10,
                parentId,
                parent,
                "Section",
                1,
                1,
                50
        );
    }
}
