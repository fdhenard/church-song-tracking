CREATE ALIAS CST_VARIATION_NAME_KEY DETERMINISTIC AS $$
String variationNameKey(String value) {
    if (value == null) return null;
    return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
        .trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
}
$$;
--;;
ALTER TABLE song_variation ADD COLUMN variation_name_key VARCHAR
GENERATED ALWAYS AS (CST_VARIATION_NAME_KEY(variation_name)) NOT NULL;
--;;
ALTER TABLE song_variation ADD CONSTRAINT song_variation_song_name_unique UNIQUE (song_id, variation_name_key);
