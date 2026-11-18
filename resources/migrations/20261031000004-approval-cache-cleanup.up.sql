ALTER TABLE vimeo_import_selection ADD COLUMN cache_cleanup_status VARCHAR NULL;
--;;
ALTER TABLE vimeo_import_selection ADD COLUMN cache_cleanup_message VARCHAR NULL;
--;;
ALTER TABLE vimeo_import_selection ADD COLUMN cache_cleaned_at TIMESTAMP NULL;
