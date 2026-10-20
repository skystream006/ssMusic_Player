# Cache and playback restoration

| Request | Authoritative file / symbol | Targeted evidence |
| --- | --- | --- |
| Persist queue, index, position, modes and selected library | [PlaybackStore.kt](../../../app/src/main/java/com/ssytdlp/app/PlaybackStore.kt) `SavedPlayback`, `setAccount`, `savePlayback`, `saveSelectedLibrary` | [Resume tests](tests.md#cache-and-resume): `PlaybackStoreTest` |
| Restore / checkpoint a player | [PlaybackPersistence.kt](../../../app/src/main/java/com/ssytdlp/app/PlaybackPersistence.kt) initializer, `save`, `close`; [PlaybackService.kt](../../../app/src/main/java/com/ssytdlp/app/PlaybackService.kt) checkpoint wiring | `PlaybackPersistenceTest` |
| Audio lease, ownership, eviction or offline reads | [AudioCache.kt](../../../app/src/main/java/com/ssytdlp/app/AudioCache.kt) `setAccount`, `acquire`, `Handle`, `Reader`, `ResizableEvictor` | `AudioCacheTest` |
| Invalidate changed bytes, including unchanged URLs | `AudioCache.invalidate`; [MusicViewModel.kt](../../../app/src/main/java/com/ssytdlp/app/MusicViewModel.kt) `patchMetadata`, `replaceFile` cache calls | `AudioCacheTest` revision/in-flight writer cases; library owns mutation semantics |
| Change local size limit | [OfflineCacheSize.kt](../../../app/src/main/java/com/ssytdlp/app/OfflineCacheSize.kt); [SessionStore.kt](../../../app/src/main/java/com/ssytdlp/app/SessionStore.kt) `chooseOfflineCacheSize` | `AudioCacheTest` resize cases; `SettingsUiTest.offlineCachePreferencePersistsAndInvalidValuesFallBackTo32MiB` |

## Invariants

- Persisted playback ownership is origin + user ID, **not token**: renewal retains snapshots, duplicates and selected-library state. Sign-out or different owner clears them and rejects old-owner writes. Do not add credentials to snapshots.
- Restore only a new empty player; skip unplayable/foreign entries, normalize index/position/repeat, and retain position only for the original selected occurrence. Restore paused without preparing/downloading. Reconnection never rewinds a live queue. Empty queues replace old snapshots; task removal/close checkpoint synchronously.
- `SessionStore` owns one application audio cache under `noBackupFilesDir`; services acquire revocable handles through account-bound `ServerApi.playbackCalls`. Different owner/logout discards bytes; token renewal retains bytes but revokes existing handles. Active readers defer actual cache release/deletion. Cosmetic user changes do not revoke leases.
- Guard cached opens/reads as well as HTTP against expiry. Call session-expiry cleanup **outside** the cache monitor to avoid lock inversion with `SessionStore`. Closing a lease cancels writers/calls without releasing another service's shared cache.
- Fill only the selected started track; changing tracks cancels its fill. Failures are opportunistic and retry on resumed playback, not every player event. Streaming readers do not hold cache-write hole locks; cache initialization failure falls back to authenticated streaming.
- Hash owner/URI keys rather than storing credentials or URL query text in filenames/indexes. Per-URI persisted revision keys isolate an already-running old writer after metadata/file edits. Do not describe cached audio as encrypted: hashing identifiers is not content encryption.
- Size options run from 32 MiB (default) to 2 GiB. Invalid settings fall back to default; the device preference survives sign-out. Resize the existing LRU cache without revoking readers/leases; reject non-positive runtime limits.

If account semantics change, consult [auth-api](../../auth-api/SKILL.md). If metadata/replacement workflow changes, coordinate [library](../../library/SKILL.md) rather than owning all `MusicViewModel`; size-picker presentation belongs to [UI](../../ui/SKILL.md). See [service lifecycle](service-and-queue.md) and [shared validation](../../repo-router/references/validation.md).
