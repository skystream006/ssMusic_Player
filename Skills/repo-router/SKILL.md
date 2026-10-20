---
name: repo-router
description: Route unclear or cross-domain ssMusic Player coding requests to the smallest relevant Skill for Android UI, library/media behavior, authentication/HTTP, playback, or build/update/diagnostic work. Use when ownership is uncertain, not before every clearly scoped domain task.
---

# Route before exploring

1. If a domain clearly matches, load its Skill directly; do not load all Skills.
2. Otherwise read [routing](references/routing.md), select one primary domain,
   and identify the requested behavior, likely symbol, and smallest relevant test.
3. Load that Skill and **one** topic reference. Inspect the named symbol and its
   immediate caller/test, not the whole source package.
4. Read [architecture](references/architecture.md) only for shared-file ownership
   or a cross-domain contract. Existing calls across domains do not require more
   Skills; changing their behavior or assumptions does.
5. Use [validation](references/validation.md) when preparing checks. Combine only
   the tests impacted by the change.
6. Keep unrelated references, screens, tests, assets, and generated files unloaded.

Links are relative to their containing document for portability. Resolve linked
paths against the checkout to absolute paths before using file tools. Find symbols
by name rather than relying on historical line numbers.

Root `Skills` is the canonical source, not a promise of automatic discovery.
See routing's activation instructions if the host has not registered these Skills.
