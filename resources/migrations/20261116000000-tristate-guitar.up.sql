ALTER TABLE song_variation ALTER COLUMN prominent_electric_guitar DROP NOT NULL;
--;;
ALTER TABLE song_variation ALTER COLUMN prominent_electric_guitar SET DEFAULT NULL;
--;;
UPDATE song_variation SET prominent_electric_guitar = NULL WHERE prominent_electric_guitar = FALSE;
