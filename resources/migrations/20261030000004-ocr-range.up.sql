ALTER TABLE vimeo_import_selection ADD COLUMN trim_start_seconds BIGINT NOT NULL DEFAULT 0;
--;;
ALTER TABLE vimeo_import_selection ADD COLUMN trim_end_seconds BIGINT;
--;;
ALTER TABLE vimeo_import_selection ADD COLUMN range_needs_ocr BOOLEAN NOT NULL DEFAULT FALSE;
--;;
ALTER TABLE vimeo_import_selection ADD COLUMN job_refresh_review BOOLEAN NOT NULL DEFAULT FALSE;
