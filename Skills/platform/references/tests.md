# Platform tests

Listed classes live in [app tests](../../../app/src/test/java/com/ssytdlp/app/), package `com.ssytdlp.app`. Select related classes/methods with the [shared validation commands](../../repo-router/references/validation.md); build/release changes use the appropriate shared lane rather than assuming unit tests validate workflows.

## Updater

| Request | File / symbol | Class / targeted methods |
| --- | --- | --- |
| Version and universal asset contract | [AppUpdater.kt](../../../app/src/main/java/com/ssytdlp/app/AppUpdater.kt) `UpdateVersion`, `parseUpdateRelease` | `AppUpdaterTest`: `versionsCompareNumerically`; `stableReleaseSelectsExpectedUniversalApk`; `rejectsReleasesWithMoreThanOneApk`; `rejectsUnpublishedMalformedAndMissingAssets` |
| URL confinement | `trustedUpdateUrl` | `AppUpdaterTest`: `initialUrlIsRestrictedToThisRepository`; `redirectsOnlyAllowExactHttpsGithubAssetHosts` |
| Bounded streaming / cancellation | `saveUpdateApk` | `AppUpdaterTest`: `streamedDownloadReportsProgressAndRetainsOnlyCompleteBytes`; `truncatedOrOversizedDownloadDeletesPartialFile`; `cancellingStreamClosesInputAndDeletesPartialFile` |
| Identity / signer validation | `validateUpdateIdentity`, `currentSigners` | `AppUpdaterTest`: `apkMustHaveOwnPackageHigherCodeExpectedVersionAndIdenticalSigner` (API 26); `modernPackageWithoutSigningInfoIsNotTrusted`; `modernPackagesRequireIdenticalCurrentSignersNotJustSigningHistory` |
| Permission lifecycle / process recreation | `permissionReturned`, `requestInstall` | `AppUpdaterTest`: `unknownSourcesReturnResumesExactlyOnceAndDenialDoesNotLoop`; `recreatedProcessDoesNotResumeUnvalidatedOrMissingDownload` |
| Private installer handoff | `updateInstallIntent`, `updatePermissionIntent`; [manifest](../../../app/src/main/AndroidManifest.xml), [update paths](../../../app/src/main/res/xml/update_paths.xml) | `AppUpdaterTest`: `installerHasReadGrantClipDataAndOnlyPrivateUpdateFiles`; `manifestProviderIsPrivateAndPermissionSettingsAreScoped` |
| No background startup networking | `AppUpdater.check`, `foregroundNetwork`; [MusicApplication.kt](../../../app/src/main/java/com/ssytdlp/app/MusicApplication.kt) | `BackgroundLifecycleTest`: `automatic update check remains deferred during background startup` |

These tests do not prove live GitHub connectivity, device system-root selection or successful Android installation. For changed updater transport/install behavior, use real-device checks for trusted/untrusted certificates, background/cancel, signature mismatch and denied/granted install permission; do not weaken checks to make fixtures pass.

## Diagnostics

| Request | File / symbol | Class / targeted methods |
| --- | --- | --- |
| Privacy / opt-in | [DebugLog.kt](../../../app/src/main/java/com/ssytdlp/app/DebugLog.kt) `DebugLogStore.event`, `setEnabled` | `DebugLogTest`: `loggingIsOffByDefaultAndCreatesNoFiles`; `diagnosticOutputNeverIncludesMessagesCausesStacksOrPayloads`; `preferencePersistsAndDisablingStopsWritesWithoutRemovingSavedLogs` |
| Retention / atomic writes / export | `DebugLogStore.read`, `DebugLog.shareIntent` | `DebugLogTest`: `filesAndExportAreBoundedAndNewestEventsSurviveRotation`; `reactiveModePersistsAndKeepsTheLatest100CompleteEntries`; `interruptedReactiveRewritePreservesCommittedSnapshotAndClearRemovesSidecars`; `sharingUsesBoundedPlainTextWithoutFileUrisOrGrants` |
| Stale queued work | `DebugLogStore.generation`, `clear` | `DebugLogTest`: `clearingInvalidatesPreviouslyQueuedEvents`; `disablingAndReenablingCannotWritePreviouslyQueuedEvents` |
| Crash ordering / failures | `DebugLogStore.crashHandler` | `DebugLogTest`: `crashIsRecordedBeforeOriginalHandlerReceivesSameThreadAndThrowable`; `crashFreezesQueuedAndNewEventsWithoutChangingPersistedOptIn`; `storageFailureCannotPreventOriginalCrashHandler` |

If `UpdateUi`/`DebugLogUi` behavior changes, hand presentation coverage to [UI](../../ui/SKILL.md); nested `DebugLogTest.DebugLogUiTest` (JVM class `com.ssytdlp.app.DebugLogTest$DebugLogUiTest`) is presentation coverage, not log-storage ownership. Add [auth-api](../../auth-api/SKILL.md) only when changing the shared lifecycle gate or server TLS policy. For Gradle/workflows/signing use [build authority](build-and-release.md), preserving current signing and version contracts; no dedicated workflow test suite is implied.
