# Service, controller and queue

| Request | Authoritative file / symbol | Targeted evidence |
| --- | --- | --- |
| Background player, notification, focus or teardown | [PlaybackService.kt](../../../app/src/main/java/com/ssytdlp/app/PlaybackService.kt) `onCreate`, `onGetSession`, `onTaskRemoved`, `onDestroy`; playback service/permissions in the [manifest](../../../app/src/main/AndroidManifest.xml) | [Queue/service tests](tests.md#queue-and-service): `PlaybackPersistenceTest`; real-device checks for OS behavior |
| Connect, play, remove or reorder | [PlaybackConnection.kt](../../../app/src/main/java/com/ssytdlp/app/PlaybackConnection.kt) `connect`, `disconnect`, `matchesQueue`, `moveQueueItem`, `sortQueue` | `PlaybackPersistenceTest` duplicate/stale-snapshot cases |
| Refresh queued copies after file replacement | `Player.replaceSongFile`; `Track.toMediaItem`, `MediaItem.asTrack` in `PlaybackService.kt` | `PlaybackPersistenceTest` replacement cases |
| Foreground position polling | [PlaybackPositionPolling.kt](../../../app/src/main/java/com/ssytdlp/app/PlaybackPositionPolling.kt) `pollPlaybackPosition` | [Foreground tests](tests.md#foreground-meter): `PlaybackPositionPollingTest` |
| Non-destructive PCM analysis | [AudioLevelMeter.kt](../../../app/src/main/java/com/ssytdlp/app/AudioLevelMeter.kt) `handleBuffer`, `flush`, `level`, `waveform`; [MusicApplication.kt](../../../app/src/main/java/com/ssytdlp/app/MusicApplication.kt) `observeAudioLevels`, `updateMeter` | `AudioLevelMeterTest`, `BackgroundLifecycleTest` |

## Invariants

- The `MediaSessionService` owns ExoPlayer, notification integration, audio focus, noisy-device handling and network wake mode. UI detachment normally releases the controller, not live audio; explicit sign-out uses stop/clear. Service destruction saves persistence before releasing player/session and cache lease.
- `onGetSession` admits this package or trusted controllers. Adding media is stricter: only this package, decoded `Track` extras, and rebuilt same-origin URLs. Do not accept arbitrary controller-provided URIs.
- Service identity is origin + user ID + token. A missing/changed identity stops playback, clears items and stops the service; a cosmetic profile update must not do this.
- `matchesQueue` compares the complete ordered tracks, including duplicate occurrences. Moves/sorts operate in place, disable shuffle for explicit ordering, preserve selected occurrence/position/play intent, and do not mutate source playlists. Reject stale/invalid requests instead of rebuilding a guessed queue.
- `replaceSongFile` refreshes every matching copy. A changed current stream URL restarts at zero without forcing playback on; unrelated or nested companion changes must not rewind the current track.
- Position polling defaults to 300 ms only while resumed and catches up immediately on resume. The service's persistence checkpoint is separate: 30 seconds while playing, plus player events, backgrounding, task removal and shutdown.
- The PCM tap does not modify audio bytes or input position. Work is bounded, handles 16-bit/float PCM and non-finite values, and expires samples after 250 ms. `MusicApplication` enables it only while resumed with an observer or enabled edge lighting.

For persistence/cache changes read [cache and resume](cache-and-resume.md). Playback owns service declarations and playback permissions. For queue gestures/visualizers consult [UI](../../ui/SKILL.md); source-playlist order and replacements, [library](../../library/SKILL.md); URL/credential policy, [auth-api](../../auth-api/SKILL.md). Consult [platform](../../platform/SKILL.md) only if build/packaging or updater behavior changes. Follow [shared validation](../../repo-router/references/validation.md); tests do not prove lock-screen/Bluetooth/notification behavior.
