# Church Song Tracking

[_toc_]

A Clojure application for tracking church songs.

## Admin UI

With `CHURCH_SONG_TRACKING_CONFIG_PATH` configured, start the local admin:

```sh
clojure -X:admin
# Optional: clojure -X:admin :port 3001
```

Open http://127.0.0.1:3000. The server binds to loopback and uses the configured
H2 database. It applies outstanding migrations at startup. Stop it with Ctrl-C.
For REPL use, call `church-song-tracking.admin/start!` with an options map and
`church-song-tracking.admin/stop!` to stop it.

Services is the home screen. Search lists, add or edit records, manage a service's
ordered song performances, and view a song's variations and performance history.
People, sermon series, and holidays have their own forms. Add a song and its
variation before adding a performance. To reorder songs, edit their order values;
use a temporary free position when swapping two occupied positions.

Deletion requires a confirmation page. Referenced records cannot be deleted;
remove or reassign their related records first. Imported source data is retained
when editing performances. Forms use CSRF protection and server-side validation.
This interface is intended for local use and has no login.

Run admin integration checks with:

```sh
clojure -M test/church_song_tracking/admin_test.clj
```

## Select Vimeo services for a future import

Open **Import services** in the admin sidebar, or visit
http://127.0.0.1:3000/imports after starting (or restarting) the admin.
The page automatically loads East White Oak Bible Church's recent public uploads
and hides videos whose Vimeo links already appear in your service library.
The available list stays visible after downloads, retries, and review.
Recent uploads are cached for five minutes; **Refresh Vimeo uploads** reloads the feed. Select one or several full service videos, or paste Vimeo video
links one per line for older services, then choose **Save selection**.
Only titles containing Livestream (case insensitive) appear in the picker;
message-only videos are hidden.

Selections are stored in a separate H2 table and survive restarts. Repeated links
are saved once; **Remove** only removes the selection. Browsing and saving selections do not
download videos or create services, songs, people, or performances. The migration
creates an empty selection table; no services are preselected.

After saving a selection, choose **Download video** to save the recording locally
without an API key, OCR, or AI charges. Once complete, **Save video file** lets
you download a copy through your browser. The picker shows recording durations
and disables entries for which Vimeo reports no recording.

Choose **Start import** to prepare a local OCR review draft. A background worker downloads
the Vimeo file and extracts frames every three seconds. Before OCR, it matches visually repeated slides throughout the service, allowing
small compression differences while checking for changed text. Apple Vision reads
each matching image once; repeated appearances reuse the recognized text.
Frames without recognized text and consecutive repeats of the same text are
excluded from the review gallery. All app imports use local OCR only; frames are
never automatically sent to OpenAI, including when local OCR is unavailable. Local OCR
and its caches consume no API credits. OCR text, confidence values, and bounding
boxes are cached under each job's `local-ocr` directory. Repeated gallery entries are grouped into one slide with an expandable list of
all appearances. Original sample timestamps are preserved, including reappearances
after other slides. Existing drafts and raw frames are kept unchanged. Review remains required: OCR can miss faint,
small, or illegible text. Raw frames are retained for checking completeness.

Apple Vision is included with macOS. The app builds a small native helper using
Apple Command Line Tools on first use; this Mac is already set up. On another Mac,
install the tools with `xcode-select --install` if needed, then install the project
video requirements. No Homebrew OCR package or separate OCR model download is needed.

To run **only local OCR**, without making any API calls or importing data:

```sh
.video-tools/bin/python scripts/local_ocr.py --directory '/absolute/path/to/video-cache/2'
# Optional quick check: append --limit 25
```

In the admin, **Start import**, **Continue import**, and **Retry import** all
download/reuse the video, read slides with
Apple Vision, and creates a review draft without an API key or OpenAI requests.
The review page shows recognized slide images, raw OCR text, and timestamps.
Explicit song credits and possible sermon title screens produce review
suggestions. Unknown fields still require manual entry; local OCR is not general
semantic image recognition. Save the corrected draft and approve it as usual.
No previous OpenAI answers are used to populate a local-only draft. Completed API
batches remain on disk. Existing drafts are protected from being overwritten.

No API key is required by the admin. If local OCR fails or is unavailable, the job
stops with an explanation; it never falls back to OpenAI. Old `:local-ocr? false`
settings and `CHURCH_SONG_TRACKING_LOCAL_OCR=0` cannot enable API use in app imports.
Local OCR results are reused on retries. Existing drafts remain unchanged.

The standalone worker also defaults to local-only processing. For advanced manual
use only, `--use-openai` explicitly opts into the retained paid API implementation;
it is never passed by the admin. `--no-local-ocr` only affects that explicit mode.

`deduplication-local.json` in each job's cache records retained indexes, skipped
indexes, OCR reuse mappings, and zero API requests. Progress reports how many
unique images need OCR and how many repeats reuse existing text. Previously saved API batches remain on disk but
are not used to populate local-only review drafts. The queue updates its progress
automatically. Use **Download pending videos** to queue all newly selected services, or start
them individually. Up to three videos download simultaneously, each requesting
four streaming segments in parallel. Progress shows MB/s (decimal megabytes per
second) and approximate time remaining, updating about every three seconds.
Initial estimates may be unavailable and can fluctuate with network conditions.
Missing segments fail the job rather than silently producing an incomplete video. Local OCR has its own single-worker queue, so choose **Continue import** on
a completed download while other services are still downloading. **Start import**
also hands off automatically from downloading to analysis without occupying a
download slot during analysis. Existing downloads go directly to analysis.
**Cancel processing** stops a job. **Retry import** reuses the download and
completed recognition batches. Interrupted jobs are marked for retry on restart.
Download failures distinguish missing playable recordings, login/password requirements,
missing video links, HTTP rejection/rate limits, and connection errors. Processing failures
separately identify disk space, permissions, frame extraction, and recognition problems.
Each message provides a next step without displaying raw provider errors or signed URLs.
Existing saved failures keep their original message until the next attempt.
Videos, sampled frames, recognition results, and logs are stored under
`~/Library/Caches/church-song-tracking/vimeo-imports/<selection-id>/` by default,
outside the database directory and Dropbox, and retained for review and retry.
Override the cache root with `CHURCH_SONG_TRACKING_VIDEO_CACHE_DIR` or
`:video-cache-dir` in the external EDN configuration. Use an absolute local path.
Cache files are disposable, but clearing unfinished jobs removes their download
and recognition progress. Keeping them until review is complete allows retries
and evidence previews. Changing the location requires stopping the admin, moving
existing cache files to the new directory, and restarting; the app does not copy
or fall back to the old Dropbox location. The H2 database stays in `:data-base-dir`.

When the admin starts, it checks the local cache for unfinished imports. A missing
video returns an import without a draft to pending download. Saved drafts and
OCR ranges are kept. If selected slide frames are also missing, the draft needs
OCR again before approval. If none of the expected import directories are found
for multiple unfinished imports, startup reports the count and configured path
in the terminal and defers updates until each import is opened or retried.
The admin also checks individual imports when you open a draft or start a job.

### Choose a video section before OCR

After downloading, choose **Choose OCR range** beside the service. Use the video
player to seek, then **Use current video time** to set the start/end, or enter
seconds, m:ss, or h:mm:ss. Leave the end blank to process through the end of the
video. **Preview selected range** plays that section; **Save range and run local
OCR** starts processing. A range must be at least 3 seconds and at most 3 hours.
The app samples the chosen section directly; it preserves the downloaded file.
Slide times and source links remain relative to the original Vimeo recording.

For a service that already has a draft, choosing a different range marks it as
needing OCR again. Existing manual service/song edits are preserved when the
recognized-slide evidence is refreshed. Approval is blocked until the new range
has been processed, and stale approval forms are rejected by their revision.
Saving a range does not approve or populate service data. Local-only processing
and the independent download/analysis queues still apply.

Local rules prepare suggestions only from explicit song title/author credits and
possible short sermon-title screens with a Bible reference. Fields without clear
evidence remain blank. Musical start times are never inferred from still frames.
A possible sermon series reconstructed from OCR is flagged for verification.

For previously processed services, **Find song and sermon details** extracts
suggestions from the saved OCR without downloading, rerunning OCR, or using an
API. Existing edits are preserved and only blank values are filled. The service
and song library is untouched until you approve the review.

When ready, choose **Review extracted data**. Check the service details, ordered
songs, authors, approximate slide times, and recognized slide images/OCR evidence.
Each song's database fields appear beside its source slide: title, author,
order, song start time, and title-slide time. The sermon title, series, and slide
time have their own source slide alongside them. Select a different source slide
if needed; the image, timestamp, and optional original OCR text update together.
Raw OCR and other detected slides stay in a separate expandable reference gallery.
Save corrections with **Save draft**. **Approve saved draft and import** imports
only that service in one transaction. Stale revisions, duplicate approvals, and
conflicting existing services are blocked. Editing disables approval until saved.
Original extraction and the reviewed metadata are retained as provenance.

Still-frame analysis cannot establish musical start times or identify people by
name from faces. Those fields stay blank unless supported by visible text; song
start times require manual verification. Brief slides between samples can be
missed, and all candidates need review. No audio transcription is performed.
Videos longer than three hours are rejected rather than silently truncated.

### Video processing setup

Install project-local video tools (no system FFmpeg installation is needed):

```sh
python3 -m venv .video-tools
.video-tools/bin/python -m pip install -r scripts/requirements-video.txt
```

The admin requires no API credentials. For the optional, explicitly enabled
`--use-openai` standalone CLI mode only, export
`CHURCH_SONG_TRACKING_OPENAI_API_KEY`. That mode uses paid API requests and sends
selected images to OpenAI with `store: false`. Keys are never sent to the browser
or saved in the database. Merely setting a key does not enable API use.

Optional fields in the existing external EDN configuration:

```edn
:video-cache-dir "/absolute/local/path/to/video-cache"
:video-model "gpt-4.1-mini"
:video-python "/absolute/path/to/.video-tools/bin/python"
```

The explicit OpenAI CLI mode uses OpenAI's documented [image input](https://developers.openai.com/api/docs/guides/images-vision)
and [structured output](https://developers.openai.com/api/docs/guides/structured-outputs) formats.
No API key is sent to the browser or saved in the database.

Run the offline pipeline and job safety checks:

```sh
.video-tools/bin/python -m unittest discover -s scripts -p 'test_*.py'
clojure -M test/church_song_tracking/video_jobs_test.clj
clojure -M test/church_song_tracking/import_review_test.clj
```

The intended extraction includes song titles and authors from title slides,
song order and timestamps, song leader, preacher, sermon title, and sermon series.
If Vimeo's feed is unavailable, paste video links instead.

Run selection and isolation checks with:

```sh
clojure -M test/church_song_tracking/vimeo_import_test.clj
```

## Development

### initial setup

Create an EDN configuration file anywhere on your hard drive. For example:
`~/dev/proggie_data/church-song-tracking/config.edn`:

```edn
{:data-base-dir "/absolute/path/to/data"
 :db-name "church-song-tracking.h2"}
```

Add a line in `~/.zshrc` like this:

```bash
export CHURCH_SONG_TRACKING_CONFIG_PATH="${HOME}/dev/proggie_data/church-song-tracking/config.edn"
```

### Migrations

This project uses [Migratus](https://github.com/yogthos/migratus) to manage its
H2 schema. Migration files live in `resources/migrations` and use matching
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

The configuration file must contain `:data-base-dir` and `:db-name`. Its parent
directory is created automatically when migrations run. The path is the H2 file
database base name; H2 stores the database in a file with an `.mv.db` suffix.

## Import songs

Export the spreadsheet as CSV, then run:

```sh
clojure -X:ingest :path '"resources/Church Songs Played - Sheet1.csv"'
```

The importer applies migrations and imports rows in one transaction. Repeating
the same file skips existing performances. Conflicting performances or service
metadata abort the transaction. Run one import at a time.

Services are grouped by date, which also supplies the service title. Artist on
Slide takes precedence over Artist from Youtube; missing artists use an empty
string. Variations are grouped by song, example link, and guitar flag. The Vimeo
timestamp with a slide takes precedence. Any retreat annotation marks the service
as interesting for a retreat. Original values are retained as EDN in
`song_performance.source_data`, including both artist fields, both Vimeo times,
and the retreat annotation.

Test with `clojure -M test/church_song_tracking/initial_ingest_test.clj`.

Song start time is optional and stored in nullable `song_performance.song_start_time_vimeo`. The Vimeo navigation time prefers the title/author slide time, falling back to the reviewed song start time. Approval requires at least one of those times; local still-frame OCR cannot establish the musical start when a credit slide is absent.

Vimeo discovery scans the public upload archive using yt-dlp in flat-playlist mode (metadata only). Results are cached for one hour in `vimeo-catalog.edn` under the local video cache directory and survive app restarts. Import services shows 25 unimported Livestream videos per page. Refresh Vimeo uploads updates the catalog in the background, preserving the previous successful list on errors. Public or private videos inaccessible without a login cannot be discovered.

Saving a Vimeo selection also queues downloads for those selected URLs, with the existing three-download concurrency limit. Already active jobs, downloaded files, and reviewed drafts are skipped. OCR is started separately after downloading.

Review offers “Discard OCR and choose range again” for unimported, idle services. This clears the selected OCR range, extracted review evidence, and song/sermon draft fields while keeping service date/title/leader/preacher, the downloaded video, and per-frame OCR caches. It returns the service to the actionable list to choose a new range. Cached OCR is reused only when the source frame and OCR engine version match; choosing a new range still runs frame extraction and duplicate checks.

Different Vimeo URLs may be selected and downloaded even when titles match. Duplicate-title warnings and recording durations remain visible; no recording is automatically removed or merged. Repeated selection of the same URL is still deduplicated. Discovery caches durations for ambiguous Livestream titles.

The import review form offers searchable dropdowns for song leader (People), preacher (distinct preachers on existing services), sermon series (Sermon series), song title, and author (Songs). Clicking a field or its arrow shows all existing choices; typing filters the list, with arrow-key and Enter selection supported. Suggestions load from the current database library each time the review opens, allow new typed values, and never replace existing OCR suggestions or edits automatically.

Service-date suggestions use the month/day/year in the original Livestream video title (for example, `Livestream 08-09-26` suggests `2026-08-09`). Two-digit years mean 2000–2099; invalid or missing dates remain blank. Existing review dates are preserved, and suggestions must be saved and reviewed before approval.

Each song review section checks the saved song library and performance history. Matching title/author records show earlier-performance counts, the most recent earlier service date, and history links; songs without a matching title are labeled new to the library. A matching title with a different author is treated as a new song, with an explicit title-match / different-author explanation. A blank author remains undetermined. Comparisons update as title, author, or service date changes; later/same-day performances do not count as earlier performances. Failed history lookups are shown as unavailable rather than labeled new.

The import review includes a local video player with selected-OCR-range and whole-video modes, a bounded seek slider, and full-video timestamps. Each source-slide panel has “Show this slide in video” to seek to its original timestamp; slides outside the selected range switch playback to whole-video mode. Playback does not change the saved range, draft data, or approval state.

When sermon detection misses a slide, pause the review player and choose “Use this frame as the sermon slide.” This nominates the nearest three-second source frame for local OCR (reusing its cache when possible), adds the frame to review evidence, and returns title/preacher/scripture suggestions. The browser fills blank fields, keeps existing edits, sets the nominated source/time, and requires Save draft before approval. Active or imported services and stale review revisions reject nomination.

Selecting a frame in the sermon Source slide dropdown also runs the nominated-frame local OCR workflow automatically. Blank fields and unchanged suggestions from the preceding nominated frame are refreshed; manually edited fields are preserved. The selected image, sermon time, and readable title/preacher/series details are prepared for review, with Save draft still required.

Import services tables show newest service dates first, using the date in the Livestream title. Same-date uploads are ordered by Vimeo video ID descending. Unrecognized dates follow dated videos, ordered by Vimeo ID. Available services are sorted before pagination; the actionable and processing groups are each sorted independently.

Import services uses one Ready for your next action table for new downloadable videos and selected services needing OCR/review, plus a Queued or processing table. Unselected videos offer Download or Discard; clicking it selects that Vimeo URL and queues its download immediately, moving it into the processing table. Downloads remain limited to three at a time. Available videos are paginated, selected services are visible on each page, duplicate-title notices remain, and both tables sort newest service date first.

Discarding an available video requires a reason. The Vimeo URL, title, known duration, reason, and timestamp are persisted in H2 table `discarded_vimeo_service`. Discarded URLs are excluded from the ready list and rejected by download selection, including pasted links. The Discarded services list displays the reason and allows restoration. Discard applies to one Vimeo URL, leaving other same-title recordings available; active, downloaded, and imported services cannot be discarded through this action.

Approved sermon and song title images are copied to a permanent `slide-images/service-<id>/` archive beside the H2 database. File names include their SHA-256 hash; completed copies are verified and published atomically before database paths are committed. H2 points to `church_service.sermon_title_image_path` and `song_performance.title_image_path`. Library service/performance pages display these archived copies. Videos and other sampled frames remain in the local cache until approval cleanup. Override the archive with `CHURCH_SONG_TRACKING_SLIDE_ARCHIVE_DIR` or `:slide-archive-dir`; the archive cannot be inside the video cache.

The idempotent slide-image backfill uses approved source-frame indexes and title-slide times, recovers missing frames from retained local videos when available, and reports missing sources. It does not download videos or run OCR. For offline maintenance, stop the admin before running `clojure -X:backfill-slide-images`; the running admin also exposes the authenticated `backfill-slide-images` maintenance action. The backfill itself does not delete source caches.

The original Vimeo video title is stored separately in `vimeo_import_selection.vimeo_video_title` and `church_service.vimeo_video_title`. Discovery initializes it, completed downloads capture the title from download metadata, OCR preserves it independently of editable draft fields, and approval copies it to the service. Review and library screens show it read-only. Existing titles can be backfilled from retained download metadata, immutable extraction provenance, or the Vimeo catalog without changing service titles.

For approvals made after automatic cleanup was introduced, approval first preserves the selected title-slide images, commits the reviewed H2 records and file paths, then verifies the permanent image checksums and deletes only that import’s video/OCR cache directory. Reviewed data, Vimeo metadata, image paths, and OCR provenance remain in H2. Failed verification/deletion retains the remaining cache and reports a warning without undoing approval. Interrupted pending cleanup resumes on startup; older approvals with NULL cleanup status are never swept automatically. Existing cache data requires a separate cleanup operation.

### Optional Vimeo download session

When Vimeo blocks a public download, expand **Vimeo download session** on Import services. Open Vimeo in Chrome (default profile) or Firefox, sign in and complete any verification, then select that browser and click **Save Vimeo session**. This explicitly reads the browser cookie store; only Vimeo cookies are saved. macOS may ask for access to Chrome’s cookie encryption key. Firefox can be used if Chrome cannot be read.

On macOS, saving a browser session may require **Full Disk Access** for the terminal app that launches the admin (**iTerm** for iTerm2, or **Terminal** for Apple Terminal). If **Save Vimeo session** fails and reading the browser cookie database from that terminal returns `Operation not permitted`, open **System Settings → Privacy & Security → Full Disk Access** and enable the terminal app. If macOS offers folder-specific access, select just the browser profile folder. Full Disk Access applies to the terminal and the commands it runs. Quit the terminal app completely with **⌘Q**, reopen it, run `clojure -X:admin` from the repo, and retry **Save Vimeo session**. Firefox uses its cookie database directly. The Keychain prompt applies to Chrome. Public downloads use the app’s default download flow and need no browser session.

The saved session is stored with owner-only permissions at `~/Library/Application Support/church-song-tracking/vimeo-session.cookies`, outside Dropbox, H2, and the disposable video cache. The app never asks for a Vimeo password. Downloads try public access first, then retry an access denial once using the saved session. An expired or rejected session must be refreshed with **Save Vimeo session**. **Disconnect Vimeo session** removes the local saved copy; it does not sign you out of your browser. Jobs already running may finish using their loaded session.

### Merge duplicate-title recordings

**Merge with another service** beside a downloaded, idle recording on Import services opens a list of recording names with selection checkboxes. Select two to eight downloaded recordings, then click **Continue** to arrange only those recordings using **Move up / Move down**. **Back to selection** lets you change the selected parts. On Arrange recordings, **Recording title** offers the selected source titles and also accepts a custom title for the combined recording. Titles can differ; already approved services and existing merges cannot be merged. Creating a merge requires acknowledging any existing review work: the combined timeline needs a fresh OCR pass. Entire files are joined; overlapping material is not automatically removed.

Before approval, expand **Undo merged recording** on the merged import row and confirm that combined review work can be discarded. This restores the source recordings as separate imports, including a failed merge. Cancel active processing first. The first source video is restored from its preserved original; other source videos and their saved review drafts remain intact. The combined cache is retained in an `undone-merge-*` backup folder under the video cache. Review work discarded when the merge was created cannot be recovered by undo. Approved services cannot be undone through this action.

Merging runs in its own background queue using the bundled FFmpeg. Compatible streams are joined without re-encoding; optional conversion normalizes incompatible sources to H.264 video and AAC audio when audio exists. If only some parts have audio, converted parts without audio get silence. Missing audio is never reconstructed. Future downloads request video plus audio when available. Disk space is checked before output creation; the combined file is fully decoded and its duration checked before activation. Failed or interrupted merges can be retried using retained originals. A failed merge also offers **Retry merge with conversion**.

The merged entry continues through Choose OCR range, local OCR, review, and explicit service approval. `church_service.is_merged` identifies merged services; `merged_recording_note` stores a readable ordered list of source titles and Vimeo URLs. `service_video_source` retains each original selection, title, URL, playback order, duration, and start offset in the combined recording. The source list appears on the service row, service details, and import review. Combined review timestamps can be mapped to original timestamps by subtracting the appropriate source start offset. Original Vimeo uploads are not changed.

Source selections are reserved by the merge and cannot be independently processed or deleted. The first original is retained separately while the combined video is reviewed. After approval commits and permanent title images are verified, cleanup removes both the combined cache and its exclusively reserved source caches; H2 source history and permanent slide images remain. Existing approved services are never automatically reconciled or changed.

### Merge duplicate songs

Open **Songs → Merge duplicate songs** to see groups with matching titles (case, Unicode form, and extra spaces are normalized for grouping). **Compare and merge** shows each song’s title, artist, creation date, variation fields, and performance counts. Select only records that represent the same composition; matching titles with different authors can be different songs. Choose the row to update with **Retain this song record**. Choose a source song independently for the title and artist, or select custom entry and type a correction, then **Preview merge**. Source fields must come from included songs. Acceptance updates the retained row, redirects references, and deletes the other included song rows. A separate confirmation screen shows the exact records and affected counts before **Confirm and merge songs** writes anything.

For each variation, choose **Keep this variation** or choose a kept variation to receive its performances before deletion. The receiving variation must belong to an included song and must itself be kept; its fields are preserved. The preview lists every keep/delete decision.

The merge is one H2 transaction. Distinct variations move to the retained song; variations identical in name, example link, and guitar setting are consolidated and their performance foreign keys redirected. Performance IDs, service placement, timestamps, slide-image paths, and original source notes remain unchanged. Concurrent changes invalidate the comparison snapshot and require another review. A database failure rolls back the whole merge.

`song_merge_event` records the original songs, variation fields, and performance-reference mappings; `song_merge_alias` redirects old song URLs to the retained record. `song_identity_alias` remembers explicitly merged title/artist pairs so later approved video imports or spreadsheet imports reuse the retained song. Aliases follow subsequent merges, and review history recognizes them without double-counting performances. These changes apply only when a user confirms a merge; merely visiting the screens does not combine records.

### Unique variation names going forward

Variation names are unique within each song in both the application and H2. The database generates `variation_name_key` with Unicode NFKC normalization, trimmed/collapsed whitespace, and locale-independent lowercase, then enforces `UNIQUE (song_id, variation_name_key)`. The key is generated by H2 and cannot be supplied or changed manually; display names are preserved. Inserts, renames, moves, concurrent writes, and direct SQL cannot create normalized-name duplicates. Names may still be reused on different songs. Migration refuses unresolved duplicates rather than modifying or removing records. Application validation and transaction locks provide friendly errors before the database constraint. The admin keeps an idle connection open while running so H2 does not repeatedly compile the normalization function between requests.

Video approvals reuse an existing unambiguous Default variation without replacing its example link or guitar settings. Spreadsheet imports reuse matching variation settings and reject a conflicting same-name variation instead of creating another. Song merges consolidate matching variation identities/settings and reject distinct same-name variations being brought together; rename a conflicting variation before merging. Pre-existing duplicates already on the retained song can remain unchanged.

### Merge duplicate variations

Open **Variations → Merge duplicate variations**, or use **Merge duplicate variations for this song** on a song’s detail page. Groups contain matching variation names within a single song; case and extra whitespace are ignored. The comparison shows variation names, example links, guitar settings, creation times, and performance counts.

Select the variations, choose the record to retain, and set the final name, example link, and guitar setting. **Preview variation merge** shows the final fields and affected counts before a separate **Confirm and merge variations** step. Include all remaining variations with that name or choose a different final name; the merge cannot leave a new name collision. A stale comparison must be reloaded.

The merge runs in one H2 transaction and updates only performance variation references. Performance IDs, service/order, timestamps, archived image paths, and original source notes remain unchanged. `variation_merge_event` saves before/after fields and reference mappings. `variation_merge_alias` keeps old variation links pointing to the retained record, including after subsequent song merges consolidate variations. Unselected records remain separate. Opening either screen does not modify library data.

### Merge possible duplicate services

Open **Services → Merge possible duplicate services**, or **Compare and merge service** on an import's **Possible duplicate** notice. The flow handles same-date library records and an unapproved import's saved review draft together with a library record. Finish OCR and save the draft first. Matching dates and song titles are suggestions for human review, never automatic deletion rules.

Choose the included service rows, the row to retain, and a source row for each service field. Existing service fields can satisfy missing preacher, leader, or sermon details in an incoming draft without a separate edit; the original saved draft remains preserved in history. Sermon and song title slides appear beside the comparison. Choose which song performances to keep and give them unique final orders. Unchecking a performance explicitly schedules its deletion; original data and permanent image paths remain in H2 merge history. The preview lists all final fields, retained/deleted service rows, kept performances, and removed performances. A separate checked confirmation is required before acceptance.

Acceptance is one H2 transaction, including approval of a pending import. Kept performance IDs, song/variation references, timestamps, source notes, and image paths are preserved. Import selections, video-source references, prior merge links, and source history follow the retained service. Other service rows are deleted, and old service links redirect to the final row. Stale comparisons and database failures leave the library and pending approval unchanged. The migration creates empty history tables and never merges existing records.

`service_merge_event` stores complete before/after snapshots; `service_merge_alias` redirects old service links; `service_merge_source` preserves original service dates/titles, video titles/links, and permanent sermon image references. The retained service shows these original sources. All source Vimeo links continue to count as imported. Permanent images are not deleted. An incoming import's images are archived before approval commits; cache cleanup verifies both current and historical images and defers deletion if verification fails.

This flow reconciles service records. It does not join video files or change timestamps to a different recording's timeline. Review each timestamp against its original source video; the separate recording merge flow remains available for joining video files before OCR.

Within a service merge, any performance from an included service can be merged into another kept performance, even if their titles or authors differ. Title matches only flag possible duplicates. Use the **Choose final performance fields** panel below the table to select exactly one source for the song/author, variation, each video's time, optional start time, and title image. Each field may come only from the retained performance or performances explicitly selected to merge into it. Unrelated performance options are hidden and rejected by the backend. Values are read from the original rows so one performance’s changes cannot overwrite a later source choice. Song and variation may come from different originals: the variation settings are reused or copied to the chosen song without moving a shared variation. The selected variation name and settings are preserved. A conflicting variation used only by the reconciled performances is updated to those chosen settings. If other services still use it, preview lists those performances and requires a separate explicit approval before changing the shared settings; otherwise nothing is saved. Discarded variations, including temporary import defaults, are deleted only after a global reference check confirms they are unused. Merged variation links redirect to the retained variation, and original variation and alias data remain in service merge history. Unrelated pre-existing unused variations are not swept. Older confirmations that proposed renamed variations must be previewed again. The database uniqueness constraint remains enforced. Preview shows the chosen sources and values; acceptance preserves the kept performance ID and source notes, saves both originals and resolved results in merge history, and redirects old performance links through `performance_merge_alias`. All image files remain preserved.

### Merge preachers / people and sermon series

Open **People → Merge preachers / people** or **Sermon series → Merge sermon series**. Include records representing the same person or series, choose the row to retain, and optionally enter a corrected final name. Names can differ. Preview lists affected services before a separate approval. People are shared by preachers and song leaders, so both roles are redirected when that person is merged.

Acceptance updates service references and removes the other selected records in one transaction. Merge history preserves the original records and reference mappings. Old links redirect to the retained row, including after subsequent merges; former names remain recognized by future video import approvals. A changed comparison requires a fresh preview. These screens never merge records automatically.

### Song prediction

Open **Song prediction** in the sidebar. Optionally choose a song leader, enter a sermon title and scripture (using full book names, such as `John 3:16–21`), select a holiday, and check one or more existing song tags. Choose **Predict songs** for up to ten ranked songs with supporting reasons and links to their library records. Leaving every input blank ranks by overall recorded usage. Inputs remain selected so you can compare different emphases.

The local ranking adds leader service-use rate (weight 4), holiday service-use rate (3), each matching selected or holiday-name tag (3), strongest sermon-title word overlap (2), strongest scripture match (2), and overall service-use rate (1). Same-book/chapter scripture matches score 1; same-book matches score 0.25. Title overlap ignores common words. Ties sort by title, author, and ID. Variations and reprises count once per service. Only saved services containing performances contribute history. A new song with a matching tag can qualify without history; untagged songs remain eligible. Fewer than ten evidence-backed songs produces a shorter list.

These are relative rankings, not calibrated probabilities. With AI off, no API requests are made. Optional AI matching uses OpenAI `text-embedding-3-small` embeddings to compare sermon title/scripture meaning and adds up to 3 points for the strongest related past service (cosine similarity at least 0.55). Reasons identify the related sermon and link to that service. This is a similarity signal, not a theological judgment. Predictions do not separately adjust for recent rotation and have not been backtested. Partial records and tagging affect results. The page reads library data only and does not create or change a service.

Turn **Use AI to find related past sermons** on or off before choosing **Predict songs**. It defaults off and is preserved with your other form selections. AI mode requires a sermon title or scripture and the server environment variable `CHURCH_SONG_TRACKING_OPENAI_API_KEY`. Restart the server after setting the key; never put it in the page or source code. Only entered and historical sermon titles/scripture references are sent to OpenAI, not song leader names, recordings, or OCR. API charges may apply. Successful embeddings are cached in bounded server memory and reused until restart; changed sermon text gets a new embedding. API errors show a notice and local predictions, with no silent claim that AI ran. Missing sermon input also falls back with a clear notice. This setting does not affect local-only video imports.

### Song usage statistics

Open **Song statistics** in the navigation (also linked from Songs). With no filters, it shows the top 20 songs across the library; choose a worship leader to rank only that leader’s services. Only saved services with recorded songs are included. All variations roll up to their song, while different song records/authors remain separate until merged.

Rank uses distinct service appearances, followed by performance count, most recent use, and title/author. Repeat services means service appearances after the first. Reprises within a service increase performances but not service appearances. Historical selection rate is appearances divided by the number of services in the selected group. It is a baseline if past usage continues, not a forecast. Unapproved drafts and services without recorded songs do not enter the denominator. Holiday, conference, and sermon-topic filters are not part of this first version.

### Clean up unused songs and variations

Open **Songs → Clean up unused songs** or **Variations → Clean up unused variations**. Select individual eligible rows or use the select-all checkbox, then preview the exact deletions before confirming. A song is unused when none of its variations has a performance; deleting it also deletes those unused variations. Variation cleanup removes only the selected variations and keeps their songs.

Saved unapproved review choices (including automatic title/author matches) and retained merge links protect rows from deletion. Protected rows show their reason and cannot be selected. References and the comparison snapshot are checked again inside a transaction at acceptance; stale selections or new usage cause the whole deletion to be refused. Performances, services, and image/video files are untouched. `library_cleanup_event` preserves the deleted row data for audit; no library rows are automatically deleted by visiting these screens.

### Song tags

Open a song and use **Song tags** on its edit form. Check reusable existing tags and/or enter new short phrases separated by commas, such as `Christmas, Easter, Missions`, then **Save changes**. A song may have multiple tags; uncheck a tag to remove it from that song. Tag names ignore case, Unicode form, and extra whitespace for reuse, with an 80-character maximum per tag.

The Songs table displays tags, and its search includes tag text. Tags describe suitability independently of service holidays and sermon series. Song merges preserve the union of the included songs’ tags. Unused-song cleanup removes its assignments and retains tag details in cleanup history; reusable tag definitions remain available for other songs. No existing songs are tagged automatically.

Service review also includes **Tags to add** on every song entry. Select reusable tags or enter comma-separated new phrases, save the draft, then approve the service. Approval adds these tags to the resolved song: the explicit existing selection, an automatic existing match, or the newly created song. Existing tags are never removed through review. Saving or rerunning suggestions does not tag library songs before approval; failed approval rolls back tag creation and assignments with the rest of the import.

Import review derives song order from the title / author slide time, falling back to song start time. Save the draft to refresh the displayed sequence. Equal timestamps keep their displayed sequence; unfinished rows without valid times appear last and must have a time before approval. Approval recalculates and stores consecutive `order_in_service` values in the database, including for older saved drafts. Library performance editing and service merge ordering remain available for manual adjustments.

Service review has a **Variation to use** selector directly under **Song to import**. It lists variations belonging to the explicit existing song or the unique automatic title/author match and shows the selected variation’s example link and guitar setting. Changing the song clears an incompatible variation choice. Save the draft before approving. Approval uses the selected variation ID without changing its settings; deleted variations or variations belonging to another song are rejected. **Use Default (existing or create)** preserves the existing behavior and is the default for new songs. Pending-service merge previews also reflect the saved variation choice.

On service review, click **Add variation** to reveal the variation name, optional example link, and prominent electric guitar fields. **Cancel new variation** clears those fields and returns to the dropdown choice. Save the draft, then approve to create the variation for the resolved song; saving alone does not create library variations. A matching name (ignoring case and extra spaces) reuses the existing variation and its settings. Choose a unique name to use different settings.

Variation prominent electric guitar uses Yes (`true`), No (`false`), or Review (`NULL`). New variations default to Review. The November 16 migration changes all existing false values to NULL, preserving true values. Rolling it back converts NULL to false.

Performances have an optional Key dropdown with 12 pitches plus Unset (NULL). Accidentals show both names, such as C♯ / D♭; storage uses the sharp spelling. Existing performances and newly imported performances default to NULL. Service merges include Key among the fields that can be retained.

Variations also have an optional Key with the same 12 pitches and Unset (NULL). It is available in the variation form and import review’s Add variation panel. Variation and performance keys are independent; choosing a variation preserves its stored key without filling the performance key.
