# Shared validation

Read when preparing checks. A domain's `tests.md` selects suites; this reference
defines execution and shared fixtures. Documentation-only changes need source,
link, and metadata review, not an Android build, unless they affect an executable
contract or the repository later adds documentation tests.

## Prerequisites and authority

- Use the checked-in [wrapper configuration](../../../gradle/wrapper/gradle-wrapper.properties)
  (currently Gradle 9.4.1), not a globally installed Gradle.
- [App configuration](../../../app/build.gradle.kts) requires **full Git history**
  during configuration, even for a focused task. If shallow, obtain history with
  `git -C "$ROOT" fetch --unshallow` before building. Do not remove the guard.
- Install JDK 17 for compilation/core and JDK 21 for app JVM tests; the AVIF
  dependency requires the latter. [Gradle properties](../../../gradle.properties)
  discovers `JAVA_HOME_17_X64` and `JAVA_HOME_21_X64`. The Gradle launcher JDK must
  be compatible with the wrapper/Android plugin; a compiler toolchain does not
  establish the daemon requirement.
- Android SDK platform 36 is required; minimum supported API is 26. Set
  `ANDROID_HOME` or the local SDK configuration appropriately.
- Device tests require a connected emulator/device.
- Read actual Gradle configuration before copying README build guidance. The
  README currently contains stale wrapper/version-prefix statements and
  conflicting launcher-JDK advice; these Skills do not resolve that separate issue.

## Commands

Set `ROOT` to the **absolute checkout root** (the directory containing the wrapper
and settings file). For this task's checkout:

```sh
ROOT=/home/runner/work/ssMusic_Player/ssMusic_Player
sh "$ROOT/gradlew" -p "$ROOT" :core:test --tests 'com.ssytdlp.app.core.TrackSortTest'
sh "$ROOT/gradlew" -p "$ROOT" :app:testDebugUnitTest --tests 'com.ssytdlp.app.AudioCacheTest'
```

Replace the example class with the smallest affected class, or its exact test
method when appropriate. Repeat `--tests` for multiple selected classes in the
same test task. Kotlin backtick method names need shell quoting. A test filter
reduces execution, not necessarily compilation/configuration.

Use the same absolute wrapper/project invocation with these task arguments:

| Change/check | Task arguments |
| --- | --- |
| App lint and debug build | `:app:lintDebug :app:assembleDebug` |
| Device test compilation only | `:app:assembleDebugAndroidTest` |
| One device suite | `:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ssytdlp.app.ArtworkDecoderDeviceTest` |
| Broad integration | `:core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` |
| Release configuration | `:core:test :app:testDebugUnitTest :app:lintRelease :app:assembleRelease` |

There are only `:app` and `:core` modules. Do not invent per-Skill build tasks.
Run focused tests first; add caller/consumer tests for changed contracts. Use
broader checks for shared model, dependency, resource, lifecycle, or packaging
changes. Building a release is not permission to publish one or run cleanup.
Report blocked/unrun checks explicitly; assembly does not prove device behavior.

## Shared fixtures

- **Application startup:** `MusicApplication` creates `SessionStore`, which opens
  AndroidKeyStore. Robolectric tests using it install a test provider before
  application creation and remove it afterward. Follow
  [BackgroundLifecycleTest](../../../app/src/test/java/com/ssytdlp/app/BackgroundLifecycleTest.kt)
  or [AccountRefreshTest](../../../app/src/test/java/com/ssytdlp/app/AccountRefreshTest.kt)
  `installKeyStore`/`removeKeyStore`, rather than changing production storage.
- **TLS:** localhost certificates need an explicit `localhost` account-origin host.
  Follow [ServerApiTest.setup](../../../app/src/test/java/com/ssytdlp/app/ServerApiTest.kt)
  and `AccountRefreshTest.setup`; do not rely on the environment-dependent host
  returned by MockWebServer.
- **Compose clocks:** when automatic advancement is disabled, send
  `Snapshot.sendApplyNotifications()` after state writes in `runOnIdle`.
  [EdgeLightingTest](../../../app/src/test/java/com/ssytdlp/app/EdgeLightingTest.kt)
  has an `update` helper and a host-drawing alternative to `captureToImage()`,
  which hangs in this Robolectric setup.
- **Gesture input:** finish or cancel an active touch gesture before invoking
  another node's click; see
  [NowPlayingScreenTest](../../../app/src/test/java/com/ssytdlp/app/NowPlayingScreenTest.kt).
  Preserve existing per-test screen qualifiers and
  lifecycle/animation cleanup rather than changing whole-suite defaults.

Real-provider passkey behavior, audio focus/Bluetooth/lock-screen controls,
foreground-service behavior, and APK installer permissions still need appropriate
device/manual verification. The existing device suites cover dialogs and native
artwork decoding, not all these system integrations.
