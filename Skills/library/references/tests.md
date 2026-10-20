# Library test selection

Use [shared validation](../../repo-router/references/validation.md) for command
templates and shared fixtures. [Core tests](../../../core/src/test/kotlin/com/ssytdlp/app/core/)
use package `com.ssytdlp.app.core`;
[app tests](../../../app/src/test/java/com/ssytdlp/app/) use `com.ssytdlp.app`.
Choose one relevant row first.

| Changed behavior | Focused tests |
| --- | --- |
| Serialization defaults, capability helpers | Core `ModelsTest`; `PrivacyTest` for ownership/inheritance |
| Stable sorting, saved order, local pages | Core `TrackSortTest`; app `LibraryStateTest` |
| Catalog/reorder/versioning/partial playlist save | `PlaylistEditingTest`; selected pagination methods in `ServerApiTest` |
| Sharing/privacy | `PlaylistSharingTest`, `PrivacyActionsTest`, core permission cases |
| Cached ratings/status/privacy and off-page tracks | `LibraryStateTest` |
| Lyric parsing and minimal patches | `LyricsEditorTest`; add `LyricsEditorDialogTest` only for editor behavior (both in [LyricsEditorTest.kt](../../../app/src/test/java/com/ssytdlp/app/LyricsEditorTest.kt)) |
| Transcription request options | `TranscriptionOptionsTest`; response/default cases in core `ModelsTest` |
| Typed metadata bounds/TTL/identity | `SongMetadataCacheTest` |
| Replacement validation/streaming | `ReplacementDocumentTest`; `ReplaceFileDialogTest` for eligibility/picker |
| Feature wire contracts | Relevant methods in `ServerApiTest`; `BackgroundApiTest` only for transfer/lifecycle changes |
| View-model mutation/race integration | Relevant methods in `AccountRefreshTest`, not the entire class by default |

Useful `AccountRefreshTest` anchors:

- `metadataPreviewRollsBackOnlyItsSongWhenSavingFails`
- `signOutCancelsPendingSongActionsWithoutPublishingLateResults`
- `savingLyricsPatchesOnlySuppliedFieldsAndRetainsPlaybackPermission`
- `noVocalsOnlyAllowsLockedSongsWithoutSendingLyricsOrChangingTheirLock`
- `patchCancelsOlderGetAndItsLateResponseCannotOverwriteSavedLyrics`
- `replacingCurrentFileInvalidatesLyricsAndGetsFreshEditAuthorityEvenWithoutNewRevision`

Useful `PlaylistEditingTest` anchors:
`partialFailureReloadsCatalogKeepsEditsAndRetriesOnlyRemainingChanges`,
`songReordersUpdateLocallyWhileRequestsAreSerializedWithoutReloading`, and
`signingOutDiscardsQueuedReordersAndTheirLateResponses`.

Search for the changed method, payload key, or endpoint in the selected test file
before adding a regression. If no test covers a feature (for example a new import
validation rule), add a focused test with existing JUnit/Robolectric/MockWebServer
tools; do not claim a generic transport test covers its business rules.

For cross-domain edits, add only affected UI or playback suites. For shared model
changes, run core tests plus affected app consumers and app compilation. Use
device `MusicDeviceTest` when changed folder/transcription dialogs require real
Android behavior.
