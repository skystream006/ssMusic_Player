# Updates and diagnostics

| Request | Authoritative file / symbol | Targeted evidence |
| --- | --- | --- |
| Stable release selection / comparison | [AppUpdater.kt](../../../app/src/main/java/com/ssytdlp/app/AppUpdater.kt) `UpdateVersion`, `parseUpdateRelease` | [Updater tests](tests.md#updater): `AppUpdaterTest` |
| Trust, redirects, limits or cancellation | `updateClient`, `trustedUpdateUrl`, `foregroundNetwork`, `downloadFile`, `saveUpdateApk` | `AppUpdaterTest`; `BackgroundLifecycleTest` |
| APK identity / installer permission | `validateUpdateIdentity`, `currentSigners`, `installerIntent`, `permissionReturned`; [manifest](../../../app/src/main/AndroidManifest.xml), [update paths](../../../app/src/main/res/xml/update_paths.xml) | `AppUpdaterTest` package/signature/provider cases |
| Opt-in, bounded logging and export | [DebugLog.kt](../../../app/src/main/java/com/ssytdlp/app/DebugLog.kt) `DebugLog`, `DebugLogStore`, `DebugEvent`, `DebugLogMode` | [Diagnostics tests](tests.md#diagnostics): `DebugLogTest` |
| Crash ordering / stale queued events | `DebugLogStore.crashHandler`, `generation`, `clear` | `DebugLogTest` crash/generation cases |

## Updater invariants

- The updater is independent of `ServerApi` and account credentials. Its private client builds trust from **system Android CA roots only**, unlike the server's [network configuration](../../../app/src/main/res/xml/network_security_config.xml), which also trusts user CAs. Never reuse the authenticated server client or relax certificate/hostname validation.
- Metadata comes from this repository's latest GitHub release endpoint, not Actions artifacts. Require a stable, non-draft release, numeric three-part `v` tag and exactly one APK named `ssMusic-Player-v<version>.apk` matching the tag/path.
- Initial downloads are confined to this repository's HTTPS GitHub release path on port 443, without credentials/query/fragment. Automatic redirects remain disabled; each manual hop must satisfy `trustedUpdateUrl` (exact allowed GitHub asset hosts, no suffix matching). Six request attempts bound the redirect chain.
- Metadata is bounded to 1 MiB; APK size must be 1–100 MiB and exactly match the release declaration, even for chunked streams. Partial/cancelled/oversized files are deleted. Validate before renaming the completed file and again before installation.
- Require the app's package ID, expected version name, higher version code and a nonempty **identical current signer set**; signing history alone is insufficient. Do not advise uninstalling to bypass a signature mismatch.
- Network work waits for resumed UI, cancels active calls/body reads on pause and restarts safely, discarding partial downloads. Startup checks are attempted once per updater instance. Process recreation clears cached update files and does not restore trust/permission state.
- Installation still requires Android/user approval. Keep the provider private and restricted to the update-cache directory, granting only read access with ClipData. Permission return resumes one pending install request once; denial must not loop.

## Diagnostics invariants

Logging is off by default and stores files under `noBackupFilesDir`. Serialize only event enums, timestamps, numeric status/duration and sanitized exception **class names**—never URLs, tokens, codes/verifiers, song/user data, messages, payloads, causes or stacks.

The asynchronous queue is bounded to 128 and drops oldest work rather than blocking playback. Full mode rotates two 32 KiB files; export is bounded to 64 KiB. Reactive mode retains 100 complete events with `AtomicFile`. Clear/toggle increments generation to invalidate stale queued writes; disabling retains saved logs. Crash logging runs before delegating the original thread/throwable, freezes later writes and cannot suppress the previous handler on storage failure. Share bounded plain text, not file URIs/grants.

For [UpdateUi](../../../app/src/main/java/com/ssytdlp/app/UpdateUi.kt)/[DebugLogUi](../../../app/src/main/java/com/ssytdlp/app/DebugLogUi.kt) presentation consult [UI](../../ui/SKILL.md); lifecycle-gate policy belongs to [auth-api](../../auth-api/SKILL.md). For signer/asset naming coordinate [build and release](build-and-release.md), then [shared validation](../../repo-router/references/validation.md).
