---
name: auth-api
description: Use for server-origin setup, browser passkey callback and PKCE handling, encrypted sessions, account refresh, authenticated API transport, or foreground request cancellation.
---

# Authentication and API transport

Start at [ServerApi](../../app/src/main/java/com/ssytdlp/app/ServerApi.kt) for requests or [AuthProtocol](../../core/src/main/kotlin/com/ssytdlp/app/core/AuthProtocol.kt) for sign-in. Own `SessionStore`, `ServerConfig`, `UiActivityGate`, the auth models and `ServerResource`, `MusicViewModel` login/logout/account-refresh methods, callback consumption and callback/network/backup XML—not unrelated library operations.

## Read by request

- [Session and transport](references/session-and-transport.md): origin validation, PKCE, storage, owner checks, TLS and lifecycle policy.
- [Targeted tests](references/tests.md): verified classes/methods; use the [shared validation commands](../repo-router/references/validation.md).
- Only if ownership is ambiguous, consult [architecture](../repo-router/references/architecture.md) or [routing](../repo-router/references/routing.md).

## Preserve

Keep HTTPS hostname origins, strict single-use callbacks, encrypted account/pending-login storage, header-only bearer credentials, same-origin API resources, disabled redirects, and expected-session clearing. Defer foreground work until an activity is resumed; restart safe reads, never replay uncertain mutations or token exchanges. Preserve cancellation through response-body reads and reject stale-account results.

If the request changes sign-in presentation, consult [UI](../ui/SKILL.md); playback account/cache teardown, [playback](../playback/SKILL.md); endpoint business semantics, [library](../library/SKILL.md); build/packaging or updater behavior, [platform](../platform/SKILL.md).

Normally exclude `*Ui.kt`, `Playback*.kt`, `AppUpdater.kt`, Gradle/workflows and non-auth `MusicViewModel`/`Models` sections. External ssYTDLP still executes server jobs.

Resolve tool paths to absolute paths within the current checkout; keep documentation links relative.
