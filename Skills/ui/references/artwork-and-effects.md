# Artwork, appearance, and audio-reactive visuals

| Request/symptom | First source and symbol | First tests |
| --- | --- | --- |
| Row thumbnail loading, stale revision/account image | [TrackArtwork.kt](../../../app/src/main/java/com/ssytdlp/app/TrackArtwork.kt): `rememberTrackArtwork`; [TrackArtworkCache.kt](../../../app/src/main/java/com/ssytdlp/app/TrackArtworkCache.kt): `load`, `requestKey`, `synchronizeAccount` | `TrackArtworkTest`, `TrackArtworkCacheTest` |
| Large album art, remount flicker, repeated decoding | [PlayerUi.kt](../../../app/src/main/java/com/ssytdlp/app/PlayerUi.kt): `AlbumArtwork`, `PlayerArtwork`; [MetadataArtworkCache.kt](../../../app/src/main/java/com/ssytdlp/app/MetadataArtworkCache.kt): `load`, `setAccount`, `artworkSizeBucket` | `MetadataArtworkCacheTest`, `AlbumArtworkCacheUiTest` |
| AVIF/legacy format decoding or allocation limits | [ArtworkDecoder.kt](../../../app/src/main/java/com/ssytdlp/app/ArtworkDecoder.kt): `decodeArtworkBitmap`, `avifSourceFitsMemoryBudget` | `TrackArtworkTest`, device `ArtworkDecoderDeviceTest` |
| Theme, skin, wave artwork | [MusicUi.kt](../../../app/src/main/java/com/ssytdlp/app/MusicUi.kt): `MusicTheme`; [Skins.kt](../../../app/src/main/java/com/ssytdlp/app/Skins.kt): `AppSkin`, `SkinBackground`; [WaveVisuals.kt](../../../app/src/main/java/com/ssytdlp/app/WaveVisuals.kt): `WaveBackdrop`, `SystemBarAppearance` | `MusicUiTest`, `SettingsUiTest`, `LandscapeUiTest` |
| Edge lighting style, motion or lifecycle | [EdgeLighting.kt](../../../app/src/main/java/com/ssytdlp/app/EdgeLighting.kt): `PlaybackEdgeLighting`, `EdgeLighting`, `edgeDisplacement`; [EdgeLightingStyle.kt](../../../app/src/main/java/com/ssytdlp/app/EdgeLightingStyle.kt) | `EdgeLightingTest`, `BackgroundLifecycleTest` |
| Visualizer drawing/style | [AudioVisualizer.kt](../../../app/src/main/java/com/ssytdlp/app/AudioVisualizer.kt): `PlaybackAudioVisualizer`, `AudioVisualizer` | `AudioVisualizerTest`, relevant `NowPlayingScreenTest` cases |

## Distinct pipelines

- Row thumbnails use the application API's `TrackArtworkCache`, authenticated
  thumbnail bytes, and bounded decoding. Do not fetch full song metadata for
  every row. Preserve URL revisions, account identity, bounded pending work,
  cancellation, and sharing of in-flight requests.
- Album artwork uses `MetadataArtworkCache` for data-image sources and display
  size buckets. Cache generation prevents late decode results from crossing an
  ownership reset. Never recycle evicted bitmaps still displayed by consumers.
- `ArtworkDecoder` bounds encoded bytes, dimensions, sampling, and native AVIF
  source allocation. JVM fallbacks do not prove native AVIF works on devices.
- Typed `SongMetadataCache` and `OwnedMetadataState` belong to
  [library](../../library/SKILL.md), not this image pipeline.

## Effects and resources

`PlaybackAudioVisualizer` acquires
[MusicApplication.observeAudioLevels](../../../app/src/main/java/com/ssytdlp/app/MusicApplication.kt)
and invokes its disposer. Respect resumed lifecycle and reduced/disabled motion.
Do not add a second audio-analysis pipeline. Consult
[playback](../../playback/SKILL.md) only for meter production/interface changes.

Appearance settings are persisted by `MusicViewModel.choose*` methods; server
theme preferences and device-only appearance switches are different contracts.
The [resources](../../../app/src/main/res/) contain skin XML, wave images, fonts,
and icons. The [wave renderer](../../../scripts/render-wave-assets.ps1) is an
existing PowerShell/System.Drawing asset tool, not a required app build step.
Load binary resources and [licenses](../../../app/src/main/assets/font-licenses/)
only for asset work; do not regenerate assets for unrelated UI changes.

For an account leak, first identify which cache produced the image. Consult
`auth-api` only if session publication/invalidation ordering must change.
Select affected suites in [tests](tests.md).
