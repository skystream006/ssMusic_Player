# Playback tests

All listed classes are in [app tests](../../../app/src/test/java/com/ssytdlp/app/), package `com.ssytdlp.app`. Use the [shared validation commands](../../repo-router/references/validation.md), batching related app selectors; names with spaces are literal Kotlin test methods.

## Queue and service

| Request | File / symbol | Class / targeted methods |
| --- | --- | --- |
| Duplicate-safe live queue editing | [PlaybackConnection.kt](../../../app/src/main/java/com/ssytdlp/app/PlaybackConnection.kt) `moveQueueItem`, `sortQueue` | `PlaybackPersistenceTest`: `moving a playing duplicate preserves its position and rejects stale queue snapshots`; `queue sorts preserve the playing duplicate position modes and persisted order without loading media`; `queue sorting rejects stale snapshots and default order but explicit sorting disables shuffle` |
| File replacement in the queue | `Player.replaceSongFile` | `PlaybackPersistenceTest`: `replacement refreshes every queued copy and persistence without resuming paused playback`; `replacement keeps the selected duplicate with repeat one enabled`; `replacing another song preserves current position and playback intent` |
| Checkpoint and reconnect | [PlaybackPersistence.kt](../../../app/src/main/java/com/ssytdlp/app/PlaybackPersistence.kt); [PlaybackService.kt](../../../app/src/main/java/com/ssytdlp/app/PlaybackService.kt) | `PlaybackPersistenceTest`: `shutdown checkpoints before release and survives a new player`; `reconnection never replaces or rewinds a live queue`; `pause and shutdown save position without waiting for the coarse checkpoint` |

## Cache and resume

| Request | File / symbol | Class / targeted methods |
| --- | --- | --- |
| Paused restoration and sanitization | `PlaybackPersistence` | `PlaybackPersistenceTest`: `restores the exact queue item and timestamp paused without requesting media`; `unplayable and foreign resources are skipped while retaining the selected item position`; `invalid current item falls forward without applying its timestamp to another track` |
| Owner-scoped snapshots | [PlaybackStore.kt](../../../app/src/main/java/com/ssytdlp/app/PlaybackStore.kt) | `PlaybackStoreTest`: `same user renewing a session retains saved playback`; `sign out clears selection and blocks late service writes`; `different users and servers cannot restore or overwrite another queue` |
| Offline reuse / fill scope | [AudioCache.kt](../../../app/src/main/java/com/ssytdlp/app/AudioCache.kt) `Handle.cacheCurrent` | `AudioCacheTest`: `current track finishes in background and survives a cache recreation without HTTP`; `changing current track cancels its fill without downloading a queue`; `buffered playback does not prevent the entire current track being cached` |
| Revocation / shared cache release | `AudioCache.setAccount`, `Handle.close` | `AudioCacheTest`: `token renewal retains cached bytes but revokes old service and cosmetic changes do not`; `logout cancels an in flight fill and late work cannot resurrect deleted data`; `overlapping service leases share one cache and closing one does not release another` |
| Expiry on cached-only audio | `Handle.checkSession` | `AudioCacheTest`: `expiry before cached open clears the expected session outside the cache monitor`; `expiry during cached playback rejects reads and clears audio without HTTP` |
| Revision isolation | `AudioCache.invalidate` | `AudioCacheTest`: `same URL edits invalidate cached bytes across recreation without dropping other songs`; `an in flight old file cannot repopulate an invalidated URL` |
| Runtime size / persisted setting | `ResizableEvictor.resize`; [SessionStore.kt](../../../app/src/main/java/com/ssytdlp/app/SessionStore.kt) `chooseOfflineCacheSize`; [OfflineCacheSize.kt](../../../app/src/main/java/com/ssytdlp/app/OfflineCacheSize.kt) | `AudioCacheTest`: `shrinking immediately evicts least recently used audio without revoking leases`; `shrinking preserves an already open reader even when its span is evicted`; `SettingsUiTest`: `offlineCachePreferencePersistsAndInvalidValuesFallBackTo32MiB` |

## Foreground meter

| Request | File / symbol | Class / targeted methods |
| --- | --- | --- |
| UI polling without stopping audio | [PlaybackPositionPolling.kt](../../../app/src/main/java/com/ssytdlp/app/PlaybackPositionPolling.kt) | `PlaybackPositionPollingTest`: `position polling sleeps while paused and immediately catches up on resume` |
| PCM pass-through / disabled analysis | [AudioLevelMeter.kt](../../../app/src/main/java/com/ssytdlp/app/AudioLevelMeter.kt) | `AudioLevelMeterTest`: `measuresActualPcmWithoutMovingOrChangingInput`; `audioTapPassesEveryByteThroughUnchanged`; `disabledAudioTapStillPassesEveryByteThrough` |
| Application observer lifecycle | [MusicApplication.kt](../../../app/src/main/java/com/ssytdlp/app/MusicApplication.kt) `updateMeter`, `observeAudioLevels` | `BackgroundLifecycleTest`: `default lighting stays off until an activity is resumed`; `visualizer observes audio independently of lighting only in the foreground` |

These are not full service/device coverage. For changed OS interactions, manually verify background/lock-screen audio, notifications, audio focus, unplugging/Bluetooth, process recreation and sign-out. Add [auth-api tests](../../auth-api/references/tests.md) only for transport/session changes; consult [UI](../../ui/SKILL.md) for gestures/visuals and [library](../../library/SKILL.md) for source-playlist changes.
