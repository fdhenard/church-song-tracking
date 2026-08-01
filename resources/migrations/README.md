# Database migrations

Migratus loads migrations from this directory. Create matching up/down SQL files
using a timestamp followed by a descriptive name:

```text
20260731120000-create-songs.up.sql
20260731120000-create-songs.down.sql
```

Apply pending migrations with:

```sh
clojure -X:migrate
```

Roll back the latest migration with:

```sh
clojure -X:rollback
```

The database path comes from the `:db-path` value in the EDN configuration file
named by `CHURCH_SONG_TRACKING_CONFIG_PATH`. It is an H2 database base path; H2
adds the `.mv.db` suffix. For example:

```edn
{:db-path "/absolute/path/to/church-song-tracking"}
```
