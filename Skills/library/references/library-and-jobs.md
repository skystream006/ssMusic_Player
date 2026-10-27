# Library state, playlists, and server jobs

The main entry is [MusicViewModel.kt](../../../app/src/main/java/com/ssytdlp/app/MusicViewModel.kt).
Locate the named method first; do not read the entire file.

| Request/symptom | First symbols | First tests |
| --- | --- | --- |
| Selection, paging, search, missing library rows | `LibraryState`, `selectLibrary`, `refreshTracks`, `loadLibrary`, `trackPage`, `allTracks` | `LibraryStateTest`, pagination cases in `ServerApiTest` |
| Sort display/playback mismatch | `LibraryState.orderedSelection`, `orderedTracks`, `playLibrary`, `sortLibrary`; [TrackSort.indices](../../../core/src/main/kotlin/com/ssytdlp/app/core/TrackSort.kt) | `TrackSortTest`, `LibraryStateTest`, `PlaylistEditingTest` |
| Rename, privacy, move playlist, partial save | `savePlaylist`, `openPlaylistEditor`, `updatePlaylistPrivacy` | `PlaylistEditingTest` |
| Song transfer/remove/reorder and version conflict | `transfer`, `remove`, `reorder`, `mutateSong`, `launchSongAction` | `PlaylistEditingTest`, relevant `AccountRefreshTest` cases |
| Stale rating/transcription/privacy/artwork on queued tracks | `LibraryState.rating`, `transcription`, `privacy`, `artworkUrl`, `withTrackPage`, `pollTranscriptions` | `LibraryStateTest`, `PrivacyActionsTest` |
| Media ownership/sharing rules | [Models.kt](../../../core/src/main/kotlin/com/ssytdlp/app/core/Models.kt): `LibraryPlaylist.canRename/canShare/canChangePrivacy`, `Job.canModify`, `Track.privacy`; view-model `setSongPrivate`, `setPlaylistPrivate`, `saveContributors` | `ModelsTest`, `PrivacyTest`, `PlaylistSharingTest`, `PrivacyActionsTest` |
| Client job controls or server backup settings | `createJob`, `jobAction`, `pollJobs`, `playJob`, `startBackup`, `backupSchedule`, `pollSettings` | Relevant `AccountRefreshTest`, `BackgroundApiTest`, and UI cases; add a focused regression if none covers the operation |

## State and contract invariants

- `Track.key` encodes job ID and filename; membership/playlist identity is
  separate. Never replace it with display title or a queue occurrence index.
- `LibraryState` overrides stale track snapshots by key. Ratings include explicit
  clearing; transcription precedence is pending → refreshed map (including null)
  → inline track → legacy job summary. Compact job summaries may omit records.
- Keep source `tracks.files` in saved order. `orderedSelection` sorts the full
  selection using cached ratings and stable ties; `orderedTracks` slices it for
  display. `playLibrary` queues the full selection with `pageOffset` added to the
  clicked row index, without refetching. `allTracks` avoids inconsistent page versions.
- Per-song previews and pending state must roll back only the affected song.
  Reorders serialize versioned writes; unrelated metadata/navigation must survive
  recovery. Reject late work after account changes.
- `savePlaylist` can partially succeed across separate server mutations. Refresh
  the catalog before retrying remaining changes; do not assume an atomic save.
- Permissions differ by capability. Privacy is owner-only; rename/share/modify
  have different owner/admin/contributor/shared-account rules. Preserve protected
  entries, active-job restrictions, inherited privacy, and synthetic playlists.
  Client checks do not replace server authorization.
- Encode path IDs and nested filenames with existing `encode`/`songPath` helpers.
  Preserve `ApiJson` defaults/serial names when evolving models.

## Adjacent boundaries

Sharing dialogs currently contain request code in
[PlayerUi.kt](../../../app/src/main/java/com/ssytdlp/app/PlayerUi.kt)
(`ShareLinkDialog`, `shareMediaPath`). Library owns the contract/permissions even
there; UI owns presentation. Do not require refactoring to make a focused fix.

Job execution, downloads/transcoding, transcription, and backups happen on the
external server. This domain changes requests and observed state only.
Consult [auth-api](../../auth-api/SKILL.md) if lifecycle/transport changes, or
[playback](../../playback/SKILL.md) if queue semantics change. For song editing,
read [metadata and imports](metadata-and-imports.md) only as needed.
