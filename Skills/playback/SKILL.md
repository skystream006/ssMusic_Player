---
name: playback
description: Use for Media3 service/controller behavior, live queue edits, playback restoration, position polling, offline audio caching, or PCM metering and their session/lifecycle wiring.
---

# Playback

Start at [PlaybackService](../../app/src/main/java/com/ssytdlp/app/PlaybackService.kt) for player ownership, [PlaybackConnection](../../app/src/main/java/com/ssytdlp/app/PlaybackConnection.kt) for commands, or [AudioCache](../../app/src/main/java/com/ssytdlp/app/AudioCache.kt) for offline audio.

## Read by request

- [Service and queue](references/service-and-queue.md): controller trust, queue identity, polling and PCM metering.
- [Cache and resume](references/cache-and-resume.md): service leases, persisted queue, expiry, invalidation and cache sizing.
- [Targeted tests](references/tests.md), then [shared validation](../repo-router/references/validation.md).
- Only if ownership is ambiguous, consult [architecture](../repo-router/references/architecture.md) or [routing](../repo-router/references/routing.md).

Own `PlaybackPersistence`, `PlaybackStore`, `PlaybackPositionPolling`, `OfflineCacheSize`, `AudioLevelMeter`, their relevant `SessionStore`/`MusicApplication` wiring, and playback service declarations/permissions.

Preserve service-owned background audio while foreground polling/metering sleeps. Queue edits must reject stale snapshots and retain the selected duplicate, position and play intent. Restore only an empty player, paused without preparing media. Revoke stale service/cache leases on account or token change; expiry also blocks cached-only reads. Fill only the selected started track, never a whole playlist.

If UI controls/visuals change, consult [UI](../ui/SKILL.md); credentials or transport policy, [auth-api](../auth-api/SKILL.md); playlist mutations or metadata replacement, [library](../library/SKILL.md); build/packaging or updater behavior, [platform](../platform/SKILL.md).

Normally exclude `PlayerUi.kt`, `QueueContent.kt`, `AudioVisualizer.kt`, unrelated `MusicViewModel` methods, `AuthProtocol.kt` and release infrastructure. External ssYTDLP executes server jobs. Resolve tool paths to absolute checkout paths; retain relative documentation links.
