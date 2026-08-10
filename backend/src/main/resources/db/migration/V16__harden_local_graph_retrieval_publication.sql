ALTER TABLE graph_retrieval_publication_events
    ADD CONSTRAINT uq_graph_retrieval_events_id_profile
        UNIQUE (id, profile_version);

ALTER TABLE graph_retrieval_publications
    DROP CONSTRAINT fk_graph_retrieval_publications_event,
    ADD CONSTRAINT fk_graph_retrieval_publications_event_profile
        FOREIGN KEY (publication_event_id, profile_version)
        REFERENCES graph_retrieval_publication_events (id, profile_version);
