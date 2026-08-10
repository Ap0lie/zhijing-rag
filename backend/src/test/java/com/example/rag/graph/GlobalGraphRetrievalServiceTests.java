package com.example.rag.graph;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalGraphRetrievalServiceTests {

    @Test
    void candidateSqlProjectsSourceLocatorWithoutExpandingGlobalView() {
        String sql = GlobalGraphRetrievalService.candidateSql()
                .replaceAll("\\s+", " ");

        assertThat(sql)
                .doesNotContain("source_locator_projection")
                .contains(
                        "span.source_text_hash",
                        "span.locator_address::text AS locator_address",
                        "JOIN source_units start_unit "
                                + "ON start_unit.id = "
                                + "span.start_source_unit_id "
                                + "AND start_unit.document_id = "
                                + "span.document_id "
                                + "AND start_unit.revision_id = "
                                + "span.revision_id "
                                + "AND start_unit.normalization_version = "
                                + "span.normalization_version",
                        "JOIN source_units end_unit "
                                + "ON end_unit.id = "
                                + "span.end_source_unit_id "
                                + "AND end_unit.document_id = "
                                + "span.document_id "
                                + "AND end_unit.revision_id = "
                                + "span.revision_id "
                                + "AND end_unit.normalization_version = "
                                + "span.normalization_version",
                        "WHEN span.locator_kind = 'PAGE' "
                                + "THEN start_unit.unit_order",
                        "WHEN span.locator_kind = 'PAGE' "
                                + "THEN end_unit.unit_order",
                        "start_unit.label_metadata ->> 'sourceLabel'",
                        "start_unit.stable_address"
                );
    }
}
