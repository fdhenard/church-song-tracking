ALTER TABLE vimeo_import_selection ADD COLUMN draft_data VARCHAR;
--;;
ALTER TABLE vimeo_import_selection ADD COLUMN extracted_data VARCHAR;
--;;
ALTER TABLE vimeo_import_selection ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
--;;
ALTER TABLE vimeo_import_selection ADD COLUMN imported_service_id BIGINT REFERENCES church_service(id) ON DELETE SET NULL;

--;;
CREATE TABLE import_approval_lock (id INTEGER PRIMARY KEY);
--;;
INSERT INTO import_approval_lock (id) VALUES (1);
