# Build and release authority

| Request | Authoritative file / symbol | Validation |
| --- | --- | --- |
| Plugins, repositories, module dependency | [root Gradle](../../../build.gradle.kts), [settings](../../../settings.gradle.kts), [app Gradle](../../../app/build.gradle.kts), [core Gradle](../../../core/build.gradle.kts) | [Shared validation](../../repo-router/references/validation.md): select affected module/build lane |
| Wrapper or toolchain configuration | [wrapper properties](../../../gradle/wrapper/gradle-wrapper.properties), [Gradle properties](../../../gradle.properties), app/core `kotlin.jvmToolchain`, app `testOptions` | Shared validation; inspect actual launchers, not README prose |
| SDK, package or installer provider/permission | app `android`; installer declarations in the [manifest](../../../app/src/main/AndroidManifest.xml), [update paths](../../../app/src/main/res/xml/update_paths.xml) | [Platform tests](tests.md): `AppUpdaterTest` manifest/provider cases; device checks for OS behavior |
| Version, signing, release publication | app `gitOutput`, `revision`, `signingConfigs`; [manual release](../../../.github/workflows/manual-release.yml) | Shared release lane; `AppUpdaterTest` identity/version cases |
| Merge reminder / release retention | [release reminder](../../../.github/workflows/release-reminder.yml), [cleanup releases](../../../.github/workflows/cleanup-releases.yml) | Inspect workflow semantics; no dedicated Kotlin workflow tests |

## Current build contract

- Modules are only `:app` and `:core`, with `implementation(project(":core"))` in app. Consult [architecture](../../repo-router/references/architecture.md) only if ownership is ambiguous; native client code does not execute ssYTDLP's server media jobs.
- Wrapper **9.4.1** pins a distribution checksum. Root plugins specify AGP **9.1.1** and Kotlin **2.4.20**. Read these files again before changing versions.
- App compiles/targets SDK **36**, minimum **26**. App and core use JDK **17** compilation toolchains; app Java/Kotlin targets are 17. App unit tests explicitly launch on JDK **21** for the bundled AVIF decoder; core does not declare that test override. Gradle properties expose both installed JDK environment locations. These settings do **not** specify a separate mandatory Gradle-daemon JDK.
- Full Git history is enforced at configuration time. `revision` is the first-parent commit count at `HEAD`; version name is **`1.1.<count>`**, version code **`<count> + 1`**. Do not substitute total commits, timestamps or a shallow-history estimate.
- The checked-in debug key signs debug and default release builds. An optional distribution key is selected with `APK_SIGNING_STORE_FILE` and its password/alias environment inputs. Document names/roles only, never values; do not replace keys casually. Changing signers cannot be repaired by increasing the version.

## Workflow invariants

Manual release checks out latest `main` with full history, installs JDKs 21/17, validates all-or-none signing-secret configuration, tests/lints/builds, verifies the APK with `apksigner`, and derives `ssMusic-Player-v<version>.apk` from output metadata. It publishes a latest `v<version>` release; an existing tag's release is left unchanged. Restored signing material is removed in an always-run cleanup step.

The merged-PR reminder uses `pull_request_target` metadata only: never check out or execute PR code there. It deduplicates its comment and does not publish. Cleanup runs manually/daily, retains the five newest releases (including drafts/prereleases), deletes older releases by ID and only removes exact tags not shared by retained releases.

## Discrepancies and handoffs

[README](../../../README.md) still mentions wrapper 8.13, `1.0.<count>` and conflicting Gradle-JDK guidance. Those are not build authority; do not copy them into instructions or silently fix unrelated files. Updater's old `v1.0.N` error wording is likewise not a parser restriction.

For release asset/security changes also read [updates](updates-and-diagnostics.md). If packaging changes affect callback/network/backup policy, [auth-api](../../auth-api/SKILL.md) is authoritative; playback service/permissions belong to [playback](../../playback/SKILL.md). Consult [UI](../../ui/SKILL.md) only if presentation changes. Use shared validation for commands/prerequisites rather than duplicating them here.
