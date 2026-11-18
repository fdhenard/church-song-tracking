DROP TABLE import_approval_lock;
--;;
ALTER TABLE vimeo_import_selection DROP COLUMN imported_service_id;
--;;
ALTER TABLE vimeo_import_selection DROP COLUMN revision;
--;;
ALTER TABLE vimeo_import_selection DROP COLUMN extracted_data;
--;;
ALTER TABLE vimeo_import_selection DROP COLUMN draft_data;
