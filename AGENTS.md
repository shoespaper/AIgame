# Repository Guidelines

## Project Purpose

This repository is a modular monolith for a single-player AI role-playing game. The existing Python skill is the behavioral baseline for the first AP ruleset; the Java runtime should remain open to future rulesets. Application code owns authoritative game facts, while AI narrates confirmed outcomes.

## Project Structure

- `src/main/java/com/gameneko/aigame/api`: HTTP controllers, DTOs, and global error handling.
- `application`: use cases and orchestration.
- `engine`: ruleset-neutral runtime contracts, state lifecycle, results, and projections.
- `rulesets/ap`: typed AP state and rules migrated from Python.
- `narrative`: LLM narration and future image-generation providers.
- `content`: world, prompt, and asset catalogs.
- `application/port`: repository and provider interfaces owned by the application.
- `persistence`: storage adapters that implement application ports.
- `infrastructure`: external clients and framework integrations.
- `docs`: architecture and migration decisions.

## Build and Run

Use JDK 21 and Maven:

```text
mvn verify
mvn spring-boot:run
```

Health endpoints: `/api/health` and `/actuator/health`. The initial skeleton requires no database, Redis, or LLM service.

## Design Rules

Keep controllers thin: parse input, call an application service, and shape the response. Keep engine and ruleset code framework-free. Do not use `Map<String, Object>` as authoritative game state; each ruleset owns typed state. Do not create a universal rules DSL. JSON is suitable for static content, while calculations belong in ruleset code.

AI may return narration, media requests, and constrained effect proposals. Proposals such as granting an item or spending currency must be validated and resolved by the selected ruleset before persistence. AI output never directly patches game state.

Preserve AP behavior by default. Document every intentional player-visible correction in `docs/MIGRATION_DECISIONS.md` with before/after behavior and tests. Use integer or long types for game values, preserve Python truncation and floor-division semantics, and make random sources explicit and reproducible.

## Testing and Changes

Run `mvn verify` before submitting changes. Add focused JUnit 5 tests for application and ruleset behavior; use Python golden fixtures for migrated AP calculations. Keep `ArchitectureTest` passing when packages change. Use small, focused imperative commit messages such as `Add runtime contracts`. Pull requests should explain behavior changes and include test commands and results. Never silently change balance values while refactoring.
