# Game Runtime Architecture

## Goal and Scope

Build a deployable, single-player AI role-playing game as a modular monolith. Keep the current AP rules as the first ruleset while making the runtime able to host other rulesets later. AI narrates confirmed game outcomes; application code remains the authority for game data.

This phase defines architecture and interfaces. It does not migrate the full Python engine, add multiplayer, or introduce distributed infrastructure.

## Core Boundaries

Use these modules under `com.gameneko.aigame`:

- `api`: HTTP controllers and request/response DTOs. Controllers parse, call application services, and shape responses.
- `application`: use cases, orchestration, transaction boundaries, and runtime selection.
- `engine`: ruleset-neutral game lifecycle, action processing contracts, state versioning, and projections.
- `rulesets.ap`: the AP rules implementation migrated from the Python skill.
- `narrative`: prompt assembly and LLM integration. It receives read-only projections and confirmed outcomes.
- `content`: world, ruleset, prompt, and asset catalogs, separate from executable rules.
- `application.port`: application-owned repository and provider interfaces.
- `persistence`: storage adapters and schema/migration code implementing application ports.
- `infrastructure`: external clients and framework integrations.

Dependencies point inward: `api -> application -> engine contracts`; rulesets implement engine contracts; persistence and infrastructure adapters implement ports owned by the application or engine. Domain and ruleset code must not depend on Spring, HTTP, persistence adapters, or LLM clients. Add architecture tests to enforce these boundaries.

## Game and Ruleset Flow

Represent a playthrough with a `GameInstance` carrying its identifier, selected ruleset and version, state revision, and lifecycle phase. The ruleset owns its typed authoritative state. Process player input through a ruleset runtime contract that validates the action and resolves it into a result/event batch. Apply the result to produce the next state, persist it, then create a read-only projection for the API and narration service.

Avoid a universal game-state map or a rules DSL. Keep shared runtime contracts small; ruleset-specific state and mechanics stay in their own module. Result events support narration, audit, and later replay needs, but this design does not require full event sourcing.

## Narrative and Media Boundary

The narration service may produce text and optional scene/image-generation requests from confirmed outcomes and an explicitly scoped projection. It may also return constrained effect proposals such as `gain_experience`, `acquire_item`, `offer_quest`, or `schedule_event`. A proposal is not a game fact: the application converts it to a closed typed action, validates and resolves it through the selected ruleset or world action service, and only a separately submitted proposal at the current revision may update authoritative state. The AI must not submit arbitrary state patches. Store generated media as assets referenced by narrative records, not as rule state.

## Persistence and Runtime

Hide storage behind repositories so local development and deployment storage can evolve independently. Persist a game state revision atomically; do not retain module-global active-game or save-root state. Keep synchronous rule processing. Add authentication, multi-user isolation, distributed locks, SSE, and horizontal scaling only when product scope requires them.

## Migration Order

1. Align contributor instructions and this architecture with the single-player scope.
2. Establish runtime contracts, typed game instance/state boundaries, repository interfaces, and architecture tests.
3. Migrate one complete vertical slice, beginning with daily settlement, with focused tests.
4. Build Python golden fixtures and compare migrated AP behavior field by field before broad rules migration.
5. Migrate combat and remaining AP systems in small, verified slices.
6. Add narration and image generation behind provider interfaces after authoritative action processing is stable. Narration receives an allow-listed snapshot and confirmed event batch. External providers are optional and require explicit configuration. The local provider keeps development and tests independent of network services.

