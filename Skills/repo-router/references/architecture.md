# Architecture and ownership

Load for cross-domain reasoning, not routine single-domain changes.
[Settings](../../../settings.gradle.kts) defines two modules: Android `:app`
depends on JVM `:core`. These Skills are logical ownership boundaries, **not**
separate build modules or an enforced clean-architecture layering.

```text
MainActivity → MusicApp / Compose
                  ├─ MusicViewModel / LibraryState
                  │    ├─ ServerApi → external ssYTDLP
                  │    ├─ SessionStore / typed metadata cache
                  │    └─ PlaybackConnection → MediaController
                  │           → PlaybackService / ExoPlayer
                  │                ├─ AudioCache
                  │                ├─ PlaybackPersistence → PlaybackStore
                  │                └─ AudioLevelMeter
                  ├─ artwork decoding / image caches
                  └─ AppUpdater → GitHub Releases
MusicApplication: service construction, shared owners, foreground lifecycle
DebugLog: bounded diagnostic facility used across domains
:core: authentication protocol, serializable models, permissions, sorting
```

The external server owns media storage, downloads, transcoding, transcription
execution, and backups. There is no backend, server schema, or local job-worker
module here. Some dialogs call `ServerApi` directly; do not assume all HTTP
requests originate in the view model.

## Source map

App Kotlin files below are in
[the app package](../../../app/src/main/java/com/ssytdlp/app/);
core files are in [the core package](../../../core/src/main/kotlin/com/ssytdlp/app/core/).
Use the linked domain topic for exact entry symbols.

| Domain | Files / source boundaries |
| --- | --- |
| [ui](../../ui/SKILL.md) | `MusicUi`, `PlayerUi`, `QueueContent`, `JobsUi`, `SettingsUi`, `EditPlaylistDialog`, `ReplaceFileDialog`, `LyricsEditor`, `TranscriptionStatus`, `SongReordering`, `PlayerScreenTransition`; `Skins`, `WaveVisuals`, `EdgeLighting`, `EdgeLightingStyle`, `AudioVisualizer`; `TrackArtwork`, `ArtworkDecoder`, `TrackArtworkCache`, `MetadataArtworkCache`; `UpdateUi`, `DebugLogUi` (all `.kt`) |
| [library](../../library/SKILL.md) | `MusicViewModel.kt`, `DocumentImports.kt`, `TranscriptionOptions.kt`, `SongMetadataCache.kt`, `OwnedMetadataState.kt`; core `TrackSort.kt` and media symbols in `Models.kt` |
| [auth-api](../../auth-api/SKILL.md) | `ServerApi.kt`, `ServerConfig.kt`, `SessionStore.kt`, `UiActivityGate.kt`; core `AuthProtocol.kt`, authentication models and `ServerResource` |
| [playback](../../playback/SKILL.md) | `PlaybackConnection.kt`, `PlaybackService.kt`, `PlaybackPersistence.kt`, `PlaybackStore.kt`, `PlaybackPositionPolling.kt`, `AudioCache.kt`, `OfflineCacheSize.kt`, `AudioLevelMeter.kt` |
| [platform](../../platform/SKILL.md) | `AppUpdater.kt`, `DebugLog.kt`; [Gradle root configuration](../../../build.gradle.kts), [app configuration](../../../app/build.gradle.kts), [core configuration](../../../core/build.gradle.kts), [properties](../../../gradle.properties), [wrapper](../../../gradle/wrapper/gradle-wrapper.properties), [workflows](../../../.github/workflows/) |
| Resources and docs | [Resources](../../../app/src/main/res/) and [asset renderer](../../../scripts/render-wave-assets.ps1) belong to ui except security/update XML below; [README](../../../README.md) sections follow their feature owner |
| Tests | [App JVM/Robolectric](../../../app/src/test/java/com/ssytdlp/app/), [core JVM](../../../core/src/test/kotlin/com/ssytdlp/app/core/), [device](../../../app/src/androidTest/java/com/ssytdlp/app/) suites follow behavior ownership |

## Shared-file ownership: route by symbol, not filename

| File | Authoritative owner for the changed behavior |
| --- | --- |
| [MusicViewModel.kt](../../../app/src/main/java/com/ssytdlp/app/MusicViewModel.kt) | library operations/state → library; login/session methods → auth-api; appearance preferences → ui |
| [Models.kt](../../../core/src/main/kotlin/com/ssytdlp/app/core/Models.kt) | Media models/permissions → library; `User`, `Session`, `Account`, login responses and `ServerResource` → auth-api; `Preferences` → ui |
| [LyricsEditor.kt](../../../app/src/main/java/com/ssytdlp/app/LyricsEditor.kt) | Composable editor → ui; lyric parsing/validation/patch construction → library |
| [ReplaceFileDialog.kt](../../../app/src/main/java/com/ssytdlp/app/ReplaceFileDialog.kt) | Presentation → ui; `canReplaceFile` eligibility → library |
| [SessionStore.kt](../../../app/src/main/java/com/ssytdlp/app/SessionStore.kt) | Session lifecycle → auth-api; cache sizing/playback ownership integration → playback |
| [MainActivity.kt](../../../app/src/main/java/com/ssytdlp/app/MainActivity.kt) | UI entry → ui; callback consumption → auth-api; notification permission behavior → playback |
| [MusicApplication.kt](../../../app/src/main/java/com/ssytdlp/app/MusicApplication.kt) | Composition root: consult the domain whose service/lifecycle contract changes |
| [AndroidManifest.xml](../../../app/src/main/AndroidManifest.xml) | Callback/network/backup policy → auth-api; playback service/permissions → playback; APK install provider/permission → platform |

`Track.key`, `ApiJson`, `LibraryState` accessors, session ownership, and lifecycle
gating are shared contracts. Their detailed invariants live only in the owning
domain references. Using an existing contract does not trigger every consumer.

## Persistence and environment

- Session/pending login: encrypted preferences plus Android Keystore.
- Server origin/settings/playback snapshots: device preferences.
- Audio: Media3 disk cache with its internal database/index; no application Room schema.
- Metadata/artwork: bounded in-memory caches with account ownership.
- Diagnostics: bounded files; updater: temporary downloaded APK files.

Server origin is configured on-device. [Network policy](../../../app/src/main/res/xml/network_security_config.xml)
requires HTTPS and trusts system/user-installed CAs; the updater separately uses
system roots. [Backup exclusions](../../../app/src/main/res/xml/data_extraction_rules.xml)
and [update provider paths](../../../app/src/main/res/xml/update_paths.xml) are
security-relevant configuration. Build/signing prerequisites and commands live
in [validation](validation.md) and the platform references.

## Outside normal context

Do not inspect generated build output, local SDK configuration, IDE/Gradle
caches, Git internals, keystore contents, binary images/fonts, unrelated agent
instructions, or all tests by default. Asset/licensing requests may explicitly
load relevant resources and [font licenses](../../../app/src/main/assets/font-licenses/).
There are deliberately no separate backend, database, worker, persistence,
shared-code, or testing Skills.
