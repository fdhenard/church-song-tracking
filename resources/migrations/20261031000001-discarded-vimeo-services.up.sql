CREATE TABLE discarded_vimeo_service (
    vimeo_url VARCHAR PRIMARY KEY,
    video_title VARCHAR NOT NULL,
    duration_seconds BIGINT NULL,
    discard_reason VARCHAR(2000) NOT NULL CHECK (CHAR_LENGTH(TRIM(discard_reason)) > 0),
    discarded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
