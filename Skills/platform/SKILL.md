---
name: platform
description: Use for Gradle/toolchains, SDK or packaging configuration, release workflows and signing, the app updater's download/install security, or diagnostic log storage and privacy.
---

# Build, updates and diagnostics

Start with [app Gradle](../../app/build.gradle.kts) for build/version/signing behavior, [AppUpdater](../../app/src/main/java/com/ssytdlp/app/AppUpdater.kt) for updates, or [DebugLog](../../app/src/main/java/com/ssytdlp/app/DebugLog.kt) for diagnostics.

## Read by request

- [Build and release](references/build-and-release.md): authoritative toolchains, wrapper, versioning, signing and workflow boundaries.
- [Updates and diagnostics](references/updates-and-diagnostics.md): independent updater transport, validation/install handoff, bounded private logging.
- [Targeted tests](references/tests.md) and [shared validation commands](../repo-router/references/validation.md).
- Only if ownership is ambiguous, consult [architecture](../repo-router/references/architecture.md) or [routing](../repo-router/references/routing.md).

Actual Gradle files override stale README guidance. Keep `:app -> :core` as the only project dependency; external ssYTDLP executes media/jobs. Preserve full-history versioning, compile/test toolchain separation, signing continuity, system-CA-only updater transport, trusted redirects, bounded downloads, APK identity checks, and opt-in logs without sensitive payloads. Never document credential values.

If presentation changes, consult [UI](../ui/SKILL.md): `UpdateUi.kt` and `DebugLogUi.kt` belong there. For server TLS/session or lifecycle-gate policy consult [auth-api](../auth-api/SKILL.md); service declarations or audio lifecycle consult [playback](../playback/SKILL.md).

Normally exclude `*Ui.kt`, `MusicViewModel.kt`, `Playback*.kt` and `ServerApi.kt` unless the request crosses their boundaries. Do not infer a Gradle-daemon JDK requirement from compiler/test settings.

Resolve tool paths to absolute checkout paths while keeping links relative.
