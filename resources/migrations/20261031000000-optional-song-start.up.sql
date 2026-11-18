-- Keep the optional musical start separately from the preferred navigation time.
ALTER TABLE song_performance ADD COLUMN song_start_time_vimeo VARCHAR NULL;
