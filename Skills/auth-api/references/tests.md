# Authentication and transport tests

Sources: [core tests](../../../core/src/test/kotlin/com/ssytdlp/app/core/) (`com.ssytdlp.app.core`) and [app tests](../../../app/src/test/java/com/ssytdlp/app/) (`com.ssytdlp.app`). Names below are actual methods, including Kotlin space-containing names. Use [shared validation commands](../../repo-router/references/validation.md) and batch related selectors in the same module invocation.

## Protocol

| Request | File / symbol | Class / targeted methods |
| --- | --- | --- |
| PKCE or callback parsing | [AuthProtocol.kt](../../../core/src/main/kotlin/com/ssytdlp/app/core/AuthProtocol.kt) `challenge`, `acceptCallback` | `AuthProtocolTest`: `PKCE matches the RFC 7636 S256 test vector`; `rejects unsolicited expired and clock rollback callbacks`; `rejects altered origins paths fragments state and duplicates` |
| Server origin handling | `AuthProtocol.normalizeOrigin`; [ServerConfig.kt](../../../app/src/main/java/com/ssytdlp/app/ServerConfig.kt) `set` | `AuthProtocolTest`: `accepts HTTPS hostname origins and canonicalizes default port`; `rejects insecure or ambiguous server addresses` (protocol coverage, not a dedicated configuration-store suite) |
| Auth models / resource confinement | [Models.kt](../../../core/src/main/kotlin/com/ssytdlp/app/core/Models.kt) auth models, `ServerResource.resolve` | `ModelsTest`: `login and current user responses retain shared account details`; `resource URLs preserve escaped nested file names and query versions`; `resource URLs reject cross origin cleartext and non API locations` |

## Transport

| Request | File / symbol | Class / targeted methods |
| --- | --- | --- |
| Configured origin and exchange isolation | [MusicApplication.kt](../../../app/src/main/java/com/ssytdlp/app/MusicApplication.kt) `AppServer`; [ServerApi.kt](../../../app/src/main/java/com/ssytdlp/app/ServerApi.kt) `exchange` | `ServerApiTest`: `stored login requests for a different server cannot be exchanged`; `app exchange uses unauthenticated transport even with an existing session` |
| Credential confinement / expiry | `ServerApi.authenticatedClient` | `ServerApiTest`: `authenticated requests send bearer only in the header`; `redirects are not followed and cannot receive the bearer token`; `401 clears precisely the session used by the request`; `expired sessions are cleared without making a request` |
| Artwork transport | `ServerApi.artwork` | `ServerApiTest`: `artwork rejects oversized responses including chunked bodies`; `artwork refuses foreign URLs and redirects and expires unauthorized sessions` |

## Session and lifecycle

| Request | File / symbol | Class / targeted methods |
| --- | --- | --- |
| Profile refresh without account resurrection | [SessionStore.kt](../../../app/src/main/java/com/ssytdlp/app/SessionStore.kt) `updateUser`; [MusicViewModel.kt](../../../app/src/main/java/com/ssytdlp/app/MusicViewModel.kt) `refreshAccount` | `AccountRefreshTest`: `userRefreshPreservesSessionAndPlaybackAndSurvivesReload`; `lateUserResponsesCannotRestoreOrReplaceAnotherSession` |
| Resumed activity gating | [UiActivityGate.kt](../../../app/src/main/java/com/ssytdlp/app/UiActivityGate.kt) | `UiActivityGateTest`: `startup is paused and multiple resumed activities are tracked independently`; `safe reads cancel on pause and retry once on resume`; `nonrepeatable read is cancelled without replay on resume` |
| Cancellation / ownership / non-replay | `ServerApi.request`, `executeJson`, `exchange` | `BackgroundApiTest`: `only playback reads are exempt not jobs or mutations`; `response body reads also stop when paused`; `mutations defer then cancel on pause without replaying uncertain server changes`; `deferred work cannot run under a replacement account`; `paused login exchange cancels without replaying its single use code` |

Do not treat these as end-to-end browser passkey or production Android Keystore coverage. Changed callback delivery needs device checks; involve [UI](../../ui/SKILL.md) only if sign-in presentation changes. Changed media teardown also needs [playback tests](../../playback/references/tests.md). Do not replace owner checks with UI-only permission checks.
