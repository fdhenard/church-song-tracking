ALTER TABLE song_performance ADD COLUMN musical_key VARCHAR(2) DEFAULT NULL
    CHECK (musical_key IS NULL OR musical_key IN ('C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B'));
