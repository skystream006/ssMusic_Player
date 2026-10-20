# Session and transport

This reference covers the client boundary, not server passkey implementation. Consult [architecture](../../repo-router/references/architecture.md) only if application ownership is ambiguous.

| Request | Authoritative file / symbol | Targeted evidence |
| --- | --- | --- |
| Accept or change a server | [ServerConfig](../../../app/src/main/java/com/ssytdlp/app/ServerConfig.kt) `set`; [AuthProtocol](../../../core/src/main/kotlin/com/ssytdlp/app/core/AuthProtocol.kt) `normalizeOrigin` | [Protocol tests](tests.md#protocol): `AuthProtocolTest` |
| Begin sign-in / validate a callback | `AuthProtocol.begin`, `loginUrl`, `acceptCallback`; [MusicViewModel](../../../app/src/main/java/com/ssytdlp/app/MusicViewModel.kt) `beginLogin`, `cancelLogin`, `callback`; [AppServer](../../../app/src/main/java/com/ssytdlp/app/MusicApplication.kt) | [Transport tests](tests.md#transport): `ServerApiTest`; [lifecycle tests](tests.md#session-and-lifecycle): `BackgroundApiTest` |
| Persist / clear / refresh an account | [SessionStore](../../../app/src/main/java/com/ssytdlp/app/SessionStore.kt) `save`, `clear`, `updateUser`; `MusicViewModel.logout`, `refreshAccount` | [Session tests](tests.md#session-and-lifecycle): `AccountRefreshTest` |
| Decode auth data / resolve media paths | [Models](../../../core/src/main/kotlin/com/ssytdlp/app/core/Models.kt) `User`, `Session`, `Account`, `LoginResponse`, `UserResponse`, `ServerResource.resolve` | [Protocol tests](tests.md#protocol): `ModelsTest` |
| Add authenticated JSON, upload, download or artwork transport | [ServerApi](../../../app/src/main/java/com/ssytdlp/app/ServerApi.kt) `request`, `upload`, `download`, `artwork`, `playbackCalls`, `requireOwner` | [Transport tests](tests.md#transport): `ServerApiTest` |
| Stop UI network work in the background | [UiActivityGate](../../../app/src/main/java/com/ssytdlp/app/UiActivityGate.kt) `readWhileResumed`, `onceWhileResumed`; top-level `isBackgroundPlaybackRequest`, `ServerApi.mutationWhileResumed` | [Lifecycle tests](tests.md#session-and-lifecycle): `UiActivityGateTest`, `BackgroundApiTest` |

## Invariants

- `ServerConfig` promotes a bare hostname to HTTPS. Normalization rejects IPs, user info, paths beyond `/`, queries/fragments and invalid ports; default port 443 is canonicalized. Configuration is non-secret; account and pending-login values are AES-GCM encrypted with Android Keystore keys and preference-name AAD. Failed decryption removes the corrupt value.
- PKCE uses S256 and independent random state/verifier values. Accept only the exact custom callback shape, unique `code`/`state` parameters, matching state, a valid code and the ten-minute window without clock rollback. Use the saved/configured origin, not callback-supplied authority. `callback` consumes pending state before exchange and validates token type/shape/expiry before saving.
- `exchange` uses unauthenticated transport; authenticated resources stay on the account's HTTPS origin under `/api/`, including normalized paths. Do not send bearer tokens in URLs/cookies, follow redirects, or bypass TLS validation.
- Request ownership is origin + user ID + session token, checked before execution and before publishing JSON/artwork results. Expiry/401 clears only the expected token. Profile refresh uses expected-account equality and matching user ID; it must not reset playback/caches.
- Resumed means at least one **resumed** activity, not merely started/visible. Safe reads may restart after pause. Mutations, uploads and single-use exchanges do not replay; caller cancellation remains cancellation. Stream-writing downloads are not automatically repeatable.
- Only GET/HEAD job lyrics/stream/download and `/api/stream/…` routes bypass the UI gate; jobs, health, artwork and mutations do not. Artwork is bounded to 64 KiB, including chunked bodies.

## Conditional handoffs

[MusicApplication](../../../app/src/main/java/com/ssytdlp/app/MusicApplication.kt) reconciles configured/session origins and wires activity callbacks. Auth-api owns [MainActivity](../../../app/src/main/java/com/ssytdlp/app/MainActivity.kt) callback consumption. Involve [UI](../../ui/SKILL.md) only for screen/browser-launch presentation; [playback](../../playback/SKILL.md) for account teardown/cache leases; [library](../../library/SKILL.md) for endpoint semantics and role-dependent actions.

Auth-api owns callback/network/backup policy in the [manifest](../../../app/src/main/AndroidManifest.xml), [network security configuration](../../../app/src/main/res/xml/network_security_config.xml) and [backup exclusions](../../../app/src/main/res/xml/data_extraction_rules.xml). Server transport trusts system and user CAs without cleartext. Consult [platform](../../platform/SKILL.md) only if build/packaging or updater behavior changes; the updater deliberately uses a separate system-roots-only client. Use [shared validation](../../repo-router/references/validation.md), not copied build prerequisites.
