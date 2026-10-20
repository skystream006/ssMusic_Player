---
name: ui
description: Handle ssMusic Player Android Compose screens, navigation, dialogs, gestures, styling, accessibility, artwork rendering and image caches, and visual effects. Use for visible behavior and UI tests, not playback-engine or media-business-rule changes.
---

# Start with the affected surface

- Begin in the [app package](../../app/src/main/java/com/ssytdlp/app/) using
  [screens](references/screens.md), or
  [artwork and effects](references/artwork-and-effects.md) for image/visualization
  changes. Read one topic, then the named composable and immediate callers.
- `MusicApp`/`MusicTheme` define UI composition; `PlayerScreenTransition` defines
  retained-page behavior. Do not read them for an isolated dialog unless needed.
- Preserve lifecycle-aware state, saved navigation, accessibility semantics,
  domain-owned state accessors, and bounded/account-owned image work.
- Consult [library](../library/SKILL.md) for mutation/permission changes,
  [playback](../playback/SKILL.md) for transport/meter changes,
  [auth-api](../auth-api/SKILL.md) for account-transition contracts, or
  [platform](../platform/SKILL.md) for update/logging behavior.
- Normally ignore unrelated screens, generic HTTP/auth internals, the audio
  engine/cache, release files, and binary assets.
- Select the affected class/method from [tests](references/tests.md), then use
  [shared validation](../repo-router/references/validation.md) for prerequisites,
  focused execution, and app lint/build checks.

Resolve document links to absolute checkout paths for file tools. If ownership
is ambiguous within a file, consult the shared
[architecture](../repo-router/references/architecture.md), not every domain.
