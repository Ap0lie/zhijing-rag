package com.example.rag.graph;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class GraphRouteAvailabilityService {

    private final JdbcTemplate jdbc;

    GraphRouteAvailabilityService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Availability current() {
        return new Availability(
                exists("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM graph_publications publication
                            JOIN graph_manifests manifest
                              ON manifest.graph_generation =
                                 publication.graph_generation
                             AND manifest.status = 'ACTIVE'
                            JOIN graph_retrieval_publications profile
                              ON profile.singleton_id = 1
                            WHERE publication.singleton_id = 1
                        )
                        """),
                exists("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM global_graph_publications publication
                            JOIN global_graph_manifests manifest
                              ON manifest.global_generation =
                                 publication.global_generation
                             AND manifest.status = 'ACTIVE'
                            WHERE publication.singleton_id = 1
                              AND EXISTS (
                                SELECT 1
                                FROM global_community_reports report
                                JOIN global_report_claims claim
                                  ON claim.global_generation =
                                     report.global_generation
                                 AND claim.report_id = report.id
                                JOIN global_report_evidence evidence
                                  ON evidence.global_generation =
                                     claim.global_generation
                                 AND evidence.claim_id = claim.id
                                WHERE report.global_generation =
                                      manifest.global_generation
                              )
                        )
                        """)
        );
    }

    private boolean exists(String sql) {
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class));
    }

    public record Availability(
            boolean localAvailable,
            boolean globalAvailable
    ) {
    }
}
