package com.example.rag.graph;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalGraphQueryServiceTests {

    @Test
    void validReportSqlRechecksLocatorUnitsWithoutExpandingProjectionView() {
        String sql = GlobalGraphQueryService.validReportSql("report")
                .replaceAll("\\s+", " ");

        assertThat(sql)
                .doesNotContain("source_locator_projection")
                .contains(
                        "LEFT JOIN source_units start_unit "
                                + "ON start_unit.id = "
                                + "span.start_source_unit_id "
                                + "AND start_unit.document_id = "
                                + "current_evidence.document_id "
                                + "AND start_unit.revision_id = "
                                + "current_evidence.revision_id "
                                + "AND start_unit.normalization_version = "
                                + "span.normalization_version",
                        "LEFT JOIN source_units end_unit "
                                + "ON end_unit.id = "
                                + "span.end_source_unit_id "
                                + "AND end_unit.document_id = "
                                + "current_evidence.document_id "
                                + "AND end_unit.revision_id = "
                                + "current_evidence.revision_id "
                                + "AND end_unit.normalization_version = "
                                + "span.normalization_version",
                        "OR start_unit.id IS NULL",
                        "OR end_unit.id IS NULL"
                );
    }
}
