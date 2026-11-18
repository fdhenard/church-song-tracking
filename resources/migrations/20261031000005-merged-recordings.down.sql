DROP TABLE service_video_source;
--;;
ALTER TABLE church_service DROP COLUMN merged_recording_note;
--;;
ALTER TABLE church_service DROP COLUMN is_merged;
--;;
ALTER TABLE vimeo_import_selection DROP COLUMN merged_into_selection_id;
--;;
ALTER TABLE vimeo_import_selection DROP COLUMN is_merged;
