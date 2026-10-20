# Request routing

Read only the matching row, then its Skill. Descriptions in each `SKILL.md` are
the invocation metadata; this table resolves ambiguity rather than duplicating
the architecture.

| Requested change | Primary Skill | First topic |
| --- | --- | --- |
| Layout, navigation, dialogs, gestures, accessibility, appearance | [ui](../../ui/SKILL.md) | `screens.md` |
| Artwork, decoding, image caches, edge lighting, visualizer rendering | [ui](../../ui/SKILL.md) | `artwork-and-effects.md` |
| Library, playlists, sorting/paging, privacy/sharing, job requests | [library](../../library/SKILL.md) | `library-and-jobs.md` |
| Metadata/lyrics mutation, transcription options, imports/replacements | [library](../../library/SKILL.md) | `metadata-and-imports.md` |
| Login/callback, server selection, expiry/logout, HTTPS, HTTP lifecycle | [auth-api](../../auth-api/SKILL.md) | `session-and-transport.md` |
| Queue, seek/transport, service lifetime, audio metering | [playback](../../playback/SKILL.md) | `service-and-queue.md` |
| Restored playback, offline audio/cache limits | [playback](../../playback/SKILL.md) | `cache-and-resume.md` |
| Gradle, dependencies, packaging/signing, release workflows | [platform](../../platform/SKILL.md) | `build-and-release.md` |
| APK update/install behavior, diagnostic logging | [platform](../../platform/SKILL.md) | `updates-and-diagnostics.md` |

An endpoint-specific media payload belongs to `library`, not automatically to
`auth-api`. An artwork cache belongs to `ui`; cached audio belongs to `playback`;
typed song metadata belongs to `library`. A test-only request follows the behavior
under test, not a separate testing Skill.

## Conditional handoffs

| From | Consult only when changing |
| --- | --- |
| ui → library | Mutation rules, permission decisions, authoritative state accessors |
| ui → playback | Transport/queue semantics or the meter interface |
| ui → auth-api | Account transition or sign-in contracts |
| ui → platform | Update or diagnostic behavior rather than presentation |
| library → auth-api | Generic transport, credentials, cancellation, session ownership |
| library → playback | Queue replacement or cached audio invalidation |
| playback → auth-api | Stream authorization and account/session revocation |
| playback → library | Sorting or song identity/mutation contracts |
| auth-api → playback / ui / library | Logout/account switching changes owned cache/state teardown |
| platform → auth-api | Shared foreground activity gating, not merely HTTP usage |

For mixed files, consult the shared ownership table in
[architecture](architecture.md), then read only the relevant symbols.

## Five routing examples

| Request | Initial context | Expand only if |
| --- | --- | --- |
| Reject replacement files with an incompatible extension | library: `validateReplacementDocument`, `ReplacementDocumentTest` | ui dialog behavior changes |
| Passkey callbacks fail after changing servers | auth-api: configured origin, pending login, callback/exchange | ui messaging or playback teardown changes |
| Add another offline-cache size option | playback: `OfflineCacheSize`, resizing and persisted selection | ui selector behavior changes |
| Artwork flashes from the previous account | ui: cache ownership/generation and display consumers | auth-api publication order or library typed metadata is implicated |
| Rename release APKs without breaking updates | platform: workflow artifact name and `parseUpdateRelease` | ui installation instructions change |

Cross-layer example: expose a server-supported metadata field and retain it in
restored queues. Confirm the external contract first; then `library` handles
defaults/payload/state, `ui` the editor, and `playback` snapshot compatibility if
the field belongs in saved tracks. Run the union of affected tests. Do not load
`auth-api` unless transport or session assumptions change. Do not invent or edit
server implementations in this client repository.

## Activation and maintenance

This checkout does not establish a loader registration for root `Skills`.
Before relying on automatic invocation, confirm the host's supported discovery
paths and expose these `name`/`description` metadata through its supported
configuration. Do not assume a particular agent's default directory is scanned.
Until configured, explicitly open the chosen domain's `SKILL.md`, or this router
when uncertain. Do not copy the reference bodies into global instructions.

When a symbol, path, contract, or test changes, update its owning topic/test
reference. Keep the complete topology only in `architecture.md` and common
commands/fixtures only in `validation.md`. Do not add Skills for individual
screens, helpers, or caches.
