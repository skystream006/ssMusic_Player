# UI test selection

App classes are in [JVM/Robolectric tests](../../../app/src/test/java/com/ssytdlp/app/),
package `com.ssytdlp.app`. Start with one row; do not run every suite for a local
layout fix. Use [shared validation](../../repo-router/references/validation.md)
for commands, AndroidKeyStore/TLS fixtures, clocks, gestures, and prerequisites.

| Changed behavior | Smallest useful existing selection |
| --- | --- |
| Library/header, login, mini-player, theme | Relevant methods in `MusicUiTest`; `LandscapeUiTest` for layout/configuration changes |
| Player controls/tabs/actions | Relevant methods in `NowPlayingScreenTest`; `MusicUiTest` if shared dock code changes |
| Navigation retention/covered-page input | `LibraryNavigationTest`, `PlayerScreenTransitionTest` |
| Lyrics layout/follow/seek/pinch/select | `LyricsUiTest` |
| Lyrics editor layout/draft | `LyricsEditorDialogTest` (defined in [LyricsEditorTest.kt](../../../app/src/test/java/com/ssytdlp/app/LyricsEditorTest.kt), alongside the separate parser class `LyricsEditorTest`) |
| Queue/library gestures | `SongListGesturesTest`, `SongReorderingTest`; add playback persistence tests only for changed queue semantics |
| Playlist editor/sharing/privacy surfaces | Affected methods in `PlaylistEditingTest`, `PlaylistSharingTest`, `PrivacyActionsTest` |
| Replacement picker/eligibility display | `ReplaceFileDialogTest`; use library tests for content validation |
| Transcription display/options | `TranscriptionStatusUiTest`, relevant `NowPlayingScreenTest` methods |
| Settings and app tools | `SettingsUiTest`, relevant `MusicUiTest` methods; nested `DebugLogTest$DebugLogUiTest` for diagnostic presentation |
| Thumbnail requests/decoding/account changes | `TrackArtworkTest`, `TrackArtworkCacheTest` |
| Album image caching/display | `MetadataArtworkCacheTest`, `AlbumArtworkCacheUiTest` |
| Audio-reactive visuals | `EdgeLightingTest`, `AudioVisualizerTest`; `BackgroundLifecycleTest` for observation/lifecycle changes |

Useful targeted regression anchors include
`LibraryNavigationTest.playerRoundTripKeepsScrollGroupsFolderAndThumbnails`,
`coveredLibraryCannotReceiveTouchesKeysOrAccessibilityActions`, and
`TrackArtworkTest.changingRevisionOrAccountClearsOldArtworkWhileTheNewReadWaits`.

For the nested diagnostic class, use the quoted selector
`'com.ssytdlp.app.DebugLogTest$DebugLogUiTest'` so the shell does not expand `$`.

The [device tests](../../../app/src/androidTest/java/com/ssytdlp/app/) contain
`MusicDeviceTest` for folder/transcription dialogs and `ArtworkDecoderDeviceTest`
for native AVIF decoding/display. Run the affected class when JVM rendering cannot
establish the behavior; assembling instrumentation alone is not execution.

Preserve method-level narrow-dialog qualifiers used for Robolectric issue #8460;
do not shrink all tests to work around one dialog. When UI logic/resources change,
follow focused tests with the app lint/debug build tasks in shared validation.
