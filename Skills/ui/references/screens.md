# Screens, navigation, and interaction

Open only the source for the requested surface. Test names resolve through
[tests](tests.md).

| Request/symptom | First source and symbol | First tests |
| --- | --- | --- |
| App navigation, login presentation, themes | [MusicUi.kt](../../../app/src/main/java/com/ssytdlp/app/MusicUi.kt): `MusicApp`, `MusicTheme`, `ServerSetupScreen`, `LoginScreen` | `MusicUiTest`, `LandscapeUiTest` |
| Lost library scroll/folder state after player navigation | [MusicUi.kt](../../../app/src/main/java/com/ssytdlp/app/MusicUi.kt): `LibraryBrowsingState`, `rememberLibraryBrowsingState`; [PlayerScreenTransition.kt](../../../app/src/main/java/com/ssytdlp/app/PlayerScreenTransition.kt): `RetainedPage` | `LibraryNavigationTest`, `PlayerScreenTransitionTest` |
| Mini-player, Now Playing, transport, lyrics presentation | [PlayerUi.kt](../../../app/src/main/java/com/ssytdlp/app/PlayerUi.kt): `PlayerDock`, `MiniPlayer`, `NowPlayingScreen`, `PlayerTransport`, `LyricsContent` | `MusicUiTest`, `NowPlayingScreenTest`, `LyricsUiTest` |
| Track rows, library browser, menus | `MusicUi.kt`: `LibraryContent`, `LibraryBrowser`, `TrackRow`; `PlayerUi.kt`: `TrackMenu`, `SongActionsMenu` | `MusicUiTest`, relevant permission/menu cases |
| Queue drag/swipe or library reorder gesture | [QueueContent.kt](../../../app/src/main/java/com/ssytdlp/app/QueueContent.kt): `QueueContent`; [SongReordering.kt](../../../app/src/main/java/com/ssytdlp/app/SongReordering.kt): `rememberSongReorderState` | `SongListGesturesTest`, `SongReorderingTest` |
| Playlist editor | [EditPlaylistDialog.kt](../../../app/src/main/java/com/ssytdlp/app/EditPlaylistDialog.kt): `PlaylistEditor`, `EditPlaylistDialog` | `PlaylistEditingTest` |
| Metadata, sharing, transcription dialog | `PlayerUi.kt`: `MetadataDialog`, `ShareMediaDialog`, `SharePlaylistDialog`, `TranscribeDialog` | `NowPlayingScreenTest`, `PlaylistSharingTest`, `MusicDeviceTest` |
| Full-screen lyrics editor or file picker dialog | [LyricsEditor.kt](../../../app/src/main/java/com/ssytdlp/app/LyricsEditor.kt): `LyricsEditorDialog`; [ReplaceFileDialog.kt](../../../app/src/main/java/com/ssytdlp/app/ReplaceFileDialog.kt): `ReplaceFileDialog` | `LyricsEditorDialogTest`, `ReplaceFileDialogTest` |
| Jobs/import presentation, status badges | [JobsUi.kt](../../../app/src/main/java/com/ssytdlp/app/JobsUi.kt): `JobsScreen`, `ImportDialog`; [TranscriptionStatus.kt](../../../app/src/main/java/com/ssytdlp/app/TranscriptionStatus.kt): `TranscriptionStatus`, `label`, `tooltip` | `MusicUiTest`, `TranscriptionStatusUiTest` |
| Settings, update, diagnostic surfaces | [SettingsUi.kt](../../../app/src/main/java/com/ssytdlp/app/SettingsUi.kt): `SettingsScreen`, `OfflineCacheSetting`; [UpdateUi.kt](../../../app/src/main/java/com/ssytdlp/app/UpdateUi.kt), [DebugLogUi.kt](../../../app/src/main/java/com/ssytdlp/app/DebugLogUi.kt) | `SettingsUiTest`, relevant `MusicUiTest` cases |

## Preserve

- `MusicApp` collects account/playback with lifecycle awareness. Retained library
  pages keep browsing state but must not receive touches, keys, focus, or
  accessibility actions while covered; `RetainedPage` also limits their lifecycle.
- Use the library's authoritative accessors for row/queue values. The behavioral
  rules live in [library state](../../library/references/library-and-jobs.md);
  do not duplicate their logic in composables.
- Queue row indices identify **occurrences**, not unique song keys. A changed
  snapshot cancels gestures rather than applying stale indices.
- `PlayerDock` and Now Playing share `SongActionsMenu`; keep compact actions
  beside the seek slider without shrinking transport controls or song text.
- Distinguish callbacks for track swipes from the previous transport button.
  Consult playback if their semantics change, not for styling the buttons.
- Keep drafts bound to their original song/account and preserve cancellation,
  busy states, landscape behavior, scrolling, and accessibility labels.

For lyric parsing, permission decisions, payloads, and mutation state consult
[library](../../library/SKILL.md). For skins/fonts/drawables or image performance,
read [artwork and effects](artwork-and-effects.md) instead of unrelated screens.
