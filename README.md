# Church Song Tracking

[_toc_]

A Clojure application for tracking church songs.

## Development

### initial setup

Create an EDN configuration file anywhere on your hard drive. For example:
`~/dev/proggie_data/church-song-tracking/config.edn`:

```edn
{:db-path "/absolute/path/to/church-song-tracking.db"}
```

Add a line in `~/.zshrc` like this:

```bash
export CHURCH_SONG_TRACKING_CONFIG_PATH="${HOME}/dev/proggie_data/church-song-tracking/config.edn"
```

### Migrations

This project uses [Migratus](https://github.com/yogthos/migratus) to manage its
SQLite schema. Migration files live in `resources/migrations` and use matching
timestamped `up` and `down` files:

```text
20260731120000-create-songs.up.sql
20260731120000-create-songs.down.sql
```

#### Migrate up

```sh
$ clojure -X:migrate
```

#### Migrate down

Roll back the most recently applied migration:

```sh
$ clojure -X:rollback
```

The configuration file must contain an EDN map with a `:db-path`. Its parent
directory is created automatically when migrations run.
