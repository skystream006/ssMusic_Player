---
name: library
description: Handle ssMusic Player library and playlist behavior, sorting, paging, media permissions, sharing/privacy, metadata and lyric mutations, transcription requests, imports/replacements, and media API payloads/models. Use for client domain behavior, not generic HTTP or server worker implementations.
---

# Start with the operation

- Begin with the relevant `MusicViewModel` method or `LibraryState` accessor in
  the [app package](../../app/src/main/java/com/ssytdlp/app/), then its
  [core model](../../core/src/main/kotlin/com/ssytdlp/app/core/Models.kt).
- Choose [library and jobs](references/library-and-jobs.md) **or**
  [metadata and imports](references/metadata-and-imports.md); do not load both
  automatically. These identify entry symbols and test anchors.
- Preserve stable identity, authoritative state overrides, saved order, optimistic
  rollback, capability-specific permission rules, and stale-owner guards.
- Consult [ui](../ui/SKILL.md) only for presentation,
  [playback](../playback/SKILL.md) for queue/audio effects, and
  [auth-api](../auth-api/SKILL.md) when generic transport/session contracts change.
  Adding a field to an existing media request does not automatically require auth.
- Normally ignore themes, animation/assets, release configuration, generic HTTP
  implementation, and unrelated engine internals.
- Select focused model/helper/integration checks from [tests](references/tests.md)
  and execute using [shared validation](../repo-router/references/validation.md).

Resolve links to absolute checkout paths for file tools. For mixed-file ownership,
consult [architecture](../repo-router/references/architecture.md), not the entire
repository.
