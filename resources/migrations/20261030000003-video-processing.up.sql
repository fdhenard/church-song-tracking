ALTER TABLE vimeo_import_selection ADD COLUMN job_status VARCHAR NOT NULL DEFAULT 'selected';
--;;
ALTER TABLE vimeo_import_selection ADD COLUMN job_message VARCHAR;
--;;
ALTER TABLE vimeo_import_selection ADD COLUMN job_token VARCHAR;
