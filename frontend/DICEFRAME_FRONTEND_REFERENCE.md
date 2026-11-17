# DiceFrame Frontend Reference

This note records frontend practices reviewed from `D:\diceframe\frontend-v2`. It is a reference for AIgame, not a request to copy DiceFrame or its product scope.

## 1. Frontend structure

DiceFrame uses a Vue 3, TypeScript, and Vite frontend. Its source is organized by responsibility:

- `src/api`: typed API functions and transport handling.
- `src/components`: reusable visual components.
- `src/composables`: stateful workflows and browser integrations.
- `src/features`: user-facing feature areas such as creation, play, worlds, and rulesets.
- `src/stores`: small cross-page client state stores.
- `src/router`: route definitions and access guards.
- `src/styles`: shared tokens and page-level styles.
- `e2e`: Playwright user-flow and responsive tests.

AIgame should use the same general separation under `frontend/src`, while keeping the initial feature set much smaller.

## 2. Typed API boundary

`src/api/client.ts` centralizes HTTP behavior. It handles request headers, JSON responses, error conversion, retry decisions, and transport-specific behavior. `src/api/types.ts` keeps response shapes explicit.

Recommended AIgame adaptation:

- Create one API client for `/api` requests.
- Define TypeScript DTOs for `WorldInitializationResponse`, `GameInstanceResponse`, save responses, and later action results.
- Convert HTTP errors into a small frontend error type with status and backend error code.
- Keep API DTOs separate from display-only view models.
- Configure the backend URL through environment variables or the Vite proxy.

The frontend should consume stable read-only projections such as `WorldInitializationResponse`, rather than depending on Java record internals or persistence JSON.

## 3. Current-game context

`src/stores/gameContext.ts` combines a URL game key with a remembered local-storage value. This makes refreshes and navigation preserve the selected game while still allowing a shared or direct link to select another game.

AIgame can use the same pattern with `gameId`:

- Put the active game ID in the route.
- Remember only the last selected ID in local storage.
- Reload authoritative state from the backend after navigation or refresh.
- Clear the remembered ID when the backend reports that the game is missing.

Local storage must never be treated as the authoritative game save.

## 4. Game state workflow

`src/composables/useGame.ts` is a useful model for a play-session controller. It coordinates loading, refreshes, event subscriptions, current-game changes, stale responses, and cleanup when the view is unmounted.

For AIgame, create a smaller `useGameSession` composable that owns:

- Loading the selected game.
- Tracking loading, submitting, and error states.
- Refreshing after an action or save.
- Cleaning up any SSE connection.
- Preventing an old request from overwriting a newer game selection.

Components should receive state and emit user intent instead of implementing the entire game lifecycle.

## 5. Narrative timeline

`src/components/GameTimeline.vue` has several patterns worth keeping:

- A scrollable timeline for player actions and GM narration.
- Older rounds loaded in batches instead of rendering an unbounded list at once.
- Automatic scroll-to-bottom only when the user is already near the bottom.
- A visible new-content indicator when the user is reading earlier content.
- Separate presentation for player actions, narration, checks, and system changes.
- Optional live narration while a response is being generated.

AIgame can start with a simpler event list, but should preserve the distinction between confirmed rule events and AI narration. Narration must be rendered from confirmed outcomes supplied by the backend.

## 6. Action composer

`src/components/ActionComposer.vue` treats the action input as a full workflow rather than a plain text box:

- Quick actions can prefill common intents.
- Submission disables duplicate actions while processing.
- Errors expose a recovery path instead of silently losing the draft.
- Voice input and other optional tools are isolated behind feature support checks.
- Buttons and controls use accessible labels and touch targets of at least 44px.

AIgame should initially implement text input, submit state, validation errors, and draft preservation. Quick actions can be added once the AP action vocabulary is stable. Voice input is a later enhancement.

## 7. Server-sent events and refresh strategy

DiceFrame uses `gameSse.ts` and the game composable to receive live updates. The important idea is to keep transport handling outside visual components.

When AIgame adds asynchronous narration or action processing:

- Define a small event envelope with game ID, revision, event type, and payload.
- Use the backend revision to discard stale updates.
- Reconnect and clean up the SSE connection in the session composable.
- Keep a normal GET refresh path for initial load and recovery.

SSE should be introduced after the synchronous action and save contracts are stable.

## 8. World creation flow

`src/features/create/CreateView.vue` uses a multi-step creation flow with progress state, draft restoration, validation, and a final confirmation view. This maps well to AIgame's world initialization requirements.

Suggested AIgame steps:

1. Select a supported world, initially `isekai_grey`.
2. Enter optional name, description, location, region, weather, season, time, and main quest values.
3. Enter or choose the opening scene.
4. Review the request and initialize the game.

The UI should show validation errors from the backend clearly and avoid making up defaults that differ from the application service. Draft data may be stored locally, but the initialized `WorldState` comes only from the backend response.

## 9. Responsive interaction

DiceFrame's responsive Playwright tests are especially useful. They check that:

- Narrow play screens use drawers for side panels.
- The action composer stays anchored to the viewport bottom on phones.
- Touch controls are at least 44px.
- Long localized labels do not cause horizontal overflow.
- Modals and drawers have predictable stacking order.
- Full-screen map or play workspaces fit the viewport.

AIgame should add a small responsive test set early, especially for the action composer and world summary. Desktop and mobile should share the same data flow; only the presentation and panel arrangement should change.

## 10. Internationalization and design tokens

DiceFrame has a locale layer and centralized CSS design tokens in `src/styles/tokens.css`. The useful lessons are:

- Keep user-facing strings out of core components where practical.
- Use stable message keys for labels and error states.
- Define colors, spacing, typography, focus states, and radii as tokens.
- Use a restrained, readable visual hierarchy for dense game information.

AIgame can begin with Chinese UI text and reserve the message structure for later English support. The token system should stay small until the actual visual language is established.

## 11. Testing practices

DiceFrame combines component tests, type checks, linting, and Playwright end-to-end flows. AIgame should adopt the parts that match its current risk:

- Type checking and production build for the frontend.
- API-client tests for success, validation errors, malformed responses, and save conflicts.
- A creation-flow browser test.
- A play-screen responsive test once the action view exists.
- A test that stale game responses cannot replace the currently selected game.

Do not reproduce DiceFrame's very large test matrix before AIgame has the corresponding product features.

## 12. What not to copy yet

DiceFrame also contains functionality outside AIgame's current scope:

- Multiplayer rooms and player seats.
- P2P/WebRTC transport and device pairing.
- Authentication and public share links.
- Plugin and module marketplaces.
- D&D-specific character builders and combat HUDs.
- Voice, map, image-generation, and administrator workspaces.

These features bring their own backend contracts and security concerns. AIgame should first complete the single-player world initialization, AP action resolution, persistence, and narrative loop.

## 13. Recommended AIgame frontend milestones

### Milestone A: initialization

- Add the Vite app under `frontend`.
- Implement the typed API client.
- Build world creation and initialization.
- Display the returned world projection.

### Milestone B: persistence

- Add game ID routing and remembered current-game context.
- Add save and load controls.
- Show loading, missing-save, invalid-save, and conflict states.

### Milestone C: play loop

- Add the narrative timeline and action composer.
- Integrate the AP action endpoint and ruleset result projection.
- Add SSE only if action processing or narration becomes asynchronous.

### Milestone D: polish

- Add responsive drawers and mobile layout.
- Add accessible labels and keyboard support.
- Add localized messages, visual tokens, and focused Playwright coverage.
