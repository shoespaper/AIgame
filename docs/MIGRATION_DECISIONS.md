# Migration Decisions

This document records intentional differences between the original Python skill and the new application. A difference must be classified as an engineering correction or a gameplay-rule change. Record the affected behavior, reason, tests, and player-visible impact before merging it.

## D-001: Single-Player Modular Monolith

- **Original direction:** Multi-user online service with account isolation and distributed locking.
- **Decision:** Build for one player per game instance and deploy as a modular monolith. Keep boundaries that permit future accounts and multiplayer, but do not build JWT, Redis/Redisson, per-campaign distributed locks, or multi-tenant storage in the first architecture phase.
- **Reason:** The current product goal is to launch the game for individual players and reduce implementation cost while preserving room to grow.
- **Gameplay impact:** None intended.

## D-002: Rulesets Behind a Runtime Contract

- **Original direction:** Organize migrated rules into domain packages centered on the existing AP engine.
- **Decision:** Treat AP as one ruleset implementing a small ruleset runtime contract. Keep game lifecycle and API orchestration ruleset-neutral; keep authoritative state typed and owned by its ruleset.
- **Reason:** Future game engines may not use AP mechanics. A narrow contract avoids coupling the whole application to today's engine without inventing a universal rules language.
- **Gameplay impact:** None intended.

## D-003: AI Is a Narrator, Not a State Authority

- **Original direction:** Add an AI narration service after rule migration.
- **Decision:** AI receives confirmed outcomes and read-only projections. It may return narration and media requests, but cannot directly mutate authoritative state. Proposed game-changing intents must be validated and resolved by the ruleset.
- **Reason:** Game facts must remain deterministic, testable, and independent of model output.
- **Gameplay impact:** No rule values change. Narrative wording and presentation may differ.

## D-004: Preserve Baseline Rules; Review Corrections Explicitly

- **Original direction:** Prohibit all gameplay-rule changes during migration.
- **Decision:** Preserve calibrated AP values and behavior as the baseline, protected by Python golden fixtures. Engineering defects may be corrected when justified, but any correction that changes player-visible outcomes must be documented with before/after examples and tests. Balance changes remain separate from architecture work.
- **Reason:** The owner has authorized judgment-based improvement of the rough skill, while the existing play-tested balance remains valuable evidence.
- **Gameplay impact:** No silent changes. Any intentional outcome change requires an explicit decision entry.

## D-005: No Global Active-Game or Save-Path State

- **Original direction:** Remove module-level `MEMORY_ROOT` to avoid cross-user data overlap.
- **Decision:** Resolve game identity and storage through explicit application/repository inputs; do not keep active-game identity or save roots in static mutable state.
- **Reason:** Explicit context improves testability and prevents state leakage, including in a single-player deployment with multiple saved games.
- **Gameplay impact:** None intended.

## D-006: Reproducible Randomness

- **Original direction:** Inject random generators and persist seeds for audit and rollback.
- **Decision:** Preserve random selection semantics and make random sources explicit. Persist a seed or equivalent replay metadata wherever deterministic reproduction is required; define the exact scope during ruleset migration.
- **Reason:** Tests and bug reports need reproducible outcomes, without scattering unseeded random sources through rules code.
- **Gameplay impact:** Preserve distributions and selection weights. Sequence reproducibility may differ from Python unless explicitly matched and tested.

## Known Source Audit Note

An earlier source summary reported zero `round()` calls, but inspection found a `round()` use in `lore_loader.py`. Re-audit numeric operations from source during the relevant migration slice; do not treat the earlier count as authoritative. Python integer truncation, floor division, overflow behavior, and zero-divisor behavior need explicit equivalence tests where they affect outcomes.

