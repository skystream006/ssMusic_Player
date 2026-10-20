# Metadata, lyrics, transcription, and document operations

| Request/symptom | First source and symbols | First tests |
| --- | --- | --- |
| Rating/metadata/lock save | [MusicViewModel.kt](../../../app/src/main/java/com/ssytdlp/app/MusicViewModel.kt): `saveRating`, `saveMetadata`, `lockTranscription`, `patchMetadata` | Relevant `AccountRefreshTest` cases, `LibraryStateTest` |
| Lyric validation or patch fields | [LyricsEditor.kt](../../../app/src/main/java/com/ssytdlp/app/LyricsEditor.kt): `formatSyltForEditing`, `parseSyltForEditing`, `buildLyricsEditRequest`; view-model `saveLyrics` | `LyricsEditorTest`, lyric-save cases in `AccountRefreshTest` |
| Cached/stale metadata or rapid skip races | [SongMetadataCache.kt](../../../app/src/main/java/com/ssytdlp/app/SongMetadataCache.kt): `MetadataIdentity`, `MetadataOwner`, `get/put`; [OwnedMetadataState.kt](../../../app/src/main/java/com/ssytdlp/app/OwnedMetadataState.kt): `publish`; view-model `beginMetadataUpdate`, `finishMetadataUpdate` | `SongMetadataCacheTest`, metadata/account cases in `AccountRefreshTest` |
| Transcription options, NoVocals-only, availability/lock | [TranscriptionOptions.kt](../../../app/src/main/java/com/ssytdlp/app/TranscriptionOptions.kt): `isValid`, `toRequestBody`; view-model `transcribe`, `pollTranscriptions` | `TranscriptionOptionsTest`, relevant `ModelsTest`, `ServerApiTest`, `AccountRefreshTest` cases |
| Replacement extension/size/stream validation | [DocumentImports.kt](../../../app/src/main/java/com/ssytdlp/app/DocumentImports.kt): `selectedDocument`, `validateReplacementDocument`, `buildReplacementBody`; [ReplaceFileDialog.kt](../../../app/src/main/java/com/ssytdlp/app/ReplaceFileDialog.kt): `canReplaceFile` | `ReplacementDocumentTest`, `ReplaceFileDialogTest` |
| Replacement completion, queue/audio stale bytes | View-model `replaceFile`, `LibraryState.withReplacedFile` | Replacement cases in `AccountRefreshTest`, `LibraryStateTest`; playback tests if that contract changes |
| Imports, document grants, exports | `DocumentImports.kt`: `buildImportBody`; view-model `importFiles`, `importLocal`, `saveDownload` | Relevant `BackgroundApiTest`/`ServerApiTest` transfer cases; add targeted coverage for new import validation |

## Preserve

- Metadata/lyrics models live in [Models.kt](../../../core/src/main/kotlin/com/ssytdlp/app/core/Models.kt).
  Ordinary metadata editing and shared-user rating are distinct capabilities;
  do not blanket-enable or disable both based on role.
- Locks use the metadata PATCH and only `transcriptionLocked` when toggled.
  Lyric patches contain only changed `sylt`/`uslt` fields; explicit empty values
  clear data. Preserve untouched modes, timestamp precision/order, escaping,
  limits, and plain-lyric whitespace.
- Metadata writes must not grant `canEdit` from a PATCH response. The update path
  preserves known GET authority or reloads it. Stale GETs must not overwrite a
  newer edit.
- Typed cache identity includes song key and stream/artwork revisions; ownership
  includes origin, user ID, and session. Preserve bounds/TTL, cancellation,
  generation checks, and `OwnedMetadataState`'s immediate owner-aware reads.
  Decoded image caches instead belong to [ui](../../ui/SKILL.md).
- NoVocals-only emits solely `NoVocalsOnly=true`; it may operate on locked songs
  without unlocking them or sending hidden language/lyrics settings.
- Replacement accepts matching audio extensions and bounded nonempty streams,
  including unknown or changing reported sizes. Keep outer replacement bodies
  one-shot, not just multipart parts. `content` document URIs are opened through
  the resolver; do not turn display filenames into arbitrary filesystem paths.
- Publish replacement state only after a confirmed matching server response and
  account check. Preserve playlist membership, update nested companions/queued
  copies, invalidate affected audio and metadata, and retain playback intent.

For image display consult UI; for audio invalidation/queue behavior consult
playback. For generic replay/cancellation rules consult auth-api rather than
implementing transport policy in a feature action.
Use [tests](tests.md) to select exact cases without loading entire large suites.
