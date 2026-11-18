# AIgame Frontend

This directory contains the AIgame web frontend, built with Vue 3, TypeScript, Vite, Pinia, and Vue Router.

## Run locally

Requirements: Node.js 20.19+ and npm 10.9+.

```text
npm install
npm run dev
npm run build
```

The Vite development server runs on `http://localhost:5173` and proxies `/api` to `http://localhost:8080`. Set `VITE_BACKEND_URL` in a local `.env` file to change the proxy target. `VITE_API_BASE_URL` can override the API base path when the frontend is hosted separately and the backend allows cross-origin requests.

## Current screens

- `/`: game desk, backend health, and resume by game ID.
- `/create`: three-step world and AP character initialization.
- `/play/:gameId`: world and character status, supported AP actions, checkpoint save, and current-session operation log.

The backend currently supports `rest`, `consume_ration`, `use_potion`, and `advance_day`. The operation log shown in the play view is held in browser memory for the current session; the backend does not yet expose persistent narrative or event history.

The backend remains authoritative for world state, AP state, action validation, and outcomes. The frontend sends action intent and expected revision, then renders the server response.

See [DICEFRAME_FRONTEND_REFERENCE.md](DICEFRAME_FRONTEND_REFERENCE.md) for the reviewed DiceFrame patterns.
