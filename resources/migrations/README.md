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

The default database is `data/church-song-tracking.db`. Override it with the
`CHURCH_SONG_TRACKING_DB_PATH` environment variable or an explicit argument:

```sh
clojure -X:migrate :db-path '"/tmp/church-songs.db"'
```
