# AIgame Frontend

This directory is reserved for the AIgame web frontend.

## Scope

The frontend will provide the single-player game experience for the Spring Boot API:

- Create a game from a supported world definition.
- View the current world summary and narrative timeline.
- Submit player actions after the ruleset action API is available.
- Load and save game instances.

The backend remains authoritative for world state, ruleset state, action validation, and resolved outcomes. The frontend presents API projections and sends player intent; it must not calculate or patch authoritative game state locally.

## Suggested stack

- Vue 3
- TypeScript
- Vite
- Pinia for client-side UI state
- Vue Router
- A typed API client for the Spring Boot endpoints

The frontend can run independently during development and proxy `/api` requests to the backend. Keep the backend URL configurable; do not hard-code a machine-specific path.

## Initial screens

1. Game creation and world initialization.
2. Game play view with narrative timeline, world summary, and action composer.
3. Save/load view for game instances.

See [DICEFRAME_FRONTEND_REFERENCE.md](DICEFRAME_FRONTEND_REFERENCE.md) for the reviewed patterns from `D:\diceframe\frontend-v2`.
