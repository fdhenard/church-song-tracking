UPDATE song_variation SET prominent_electric_guitar = FALSE WHERE prominent_electric_guitar IS NULL;
--;;
ALTER TABLE song_variation ALTER COLUMN prominent_electric_guitar SET DEFAULT FALSE;
--;;
ALTER TABLE song_variation ALTER COLUMN prominent_electric_guitar SET NOT NULL;
