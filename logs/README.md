# Runtime logs (local only, gitignored)

All process logs go under this tree so module folders stay clean.

| Directory | Contents |
|-----------|----------|
| `orchestrator/` | Log4j2: runtime, pipeline, plc, trigger, lighting, subprocess |
| `geometry/` | java-geometry-service runtime |
| `positioning/` | java-positioning-service runtime |
| `analisSurface/` | FastAPI session dirs (`requests/analysis/errors…`) + `svc-analis-surface-*.log` |
| `lightserver/` | LightServer.v3 session files |
| `camera-worker/` | camera-worker metrics / stderr |
| `frontend/` | Electron / UI process stdout |
| `io-input-monitor/` | IoInputMonitor process stdout |
| `jvm-crashes/` | `hs_err_pid*` / `replay_pid*` (JVM dumps, not app logs) |
| `services/` | other `svc-*.log` fallbacks |

Override: `-Diml.log.dir=…`, `IML_PROJECT_ROOT`, `ANALIS_SURFACE_LOG_DIR`, `LIGHTSERVER_LOGS_DIR`.
