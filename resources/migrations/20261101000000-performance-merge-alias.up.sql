CREATE TABLE performance_merge_alias (
 old_performance_id BIGINT PRIMARY KEY,
 canonical_performance_id BIGINT NOT NULL REFERENCES song_performance(id),
 merge_event_id BIGINT NOT NULL REFERENCES service_merge_event(id)
);
