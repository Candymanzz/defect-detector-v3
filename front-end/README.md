# Defect Detector Front-End

Vite renderer plus Electron shell.

## Environment

Default local addresses are stored in `.env`:

```text
VITE_API_BASE_URL=http://127.0.0.1:8099
VITE_WS_URL=ws://127.0.0.1:8765/
```

Use `.env.local` for machine-specific overrides.

## Commands

```powershell
npm install
npm run dev
```

`npm run dev` starts Vite and opens the Electron window.

For a static renderer build:

```powershell
npm run build
```

For a browser-only preview:

```powershell
npm run preview
```

## Front-End API Layer

HTTP methods for the orchestrator are in `src/api/orchestratorApi.ts`.
WebSocket methods and message types are in `src/ws`.
