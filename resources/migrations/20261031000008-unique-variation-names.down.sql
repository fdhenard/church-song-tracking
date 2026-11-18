ALTER TABLE song_variation DROP CONSTRAINT song_variation_song_name_unique;
--;;
ALTER TABLE song_variation DROP COLUMN variation_name_key;
--;;
DROP ALIAS CST_VARIATION_NAME_KEY;
