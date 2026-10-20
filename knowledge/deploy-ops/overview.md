# Deploy & ops (`deploy/`, root `*.sh`, `plk/`, `config.exemple/`)

Back to [global_architecture.md](../global_architecture.md) · [Why](./architecture_why.md)

Current hardware context **[owner: 2026-10-20]**: **six CPU cores and RT**. Read [production environment](./production_environment.md) before using older CPU masks below; current per-service allocation is not verified by those defaults. The current [pipeline target](../performance/latency_requirements.md) is 500 ms from trigger to verdict.

## 1. Scripts at repo root
| Script | What it does (exact) |
|---|---|
| `rebuild-and-run.sh [-c|--skip-camera-worker] [-o|--skip-orchestrator] [-f|--skip-frontend] [--restart-frontend]` | **build only.** Orchestrator: `mvn --batch-mode package -Dmaven.test.skip=true` → `orchestrator-java/target/orchestrator-0.1.0-SNAPSHOT.jar` (Maven found in PATH/`MAVEN_HOME`/wrapper/IntelliJ; override `MAVEN_CMD`). Camera-worker: CMake Release in `camera-worker/build-linux` with `-DMVS_ROOT=${MVS_ROOT:-/opt/MVS}`, binary replaced **atomically** in `camera-worker/build/camera_worker`. Frontend: `npm ci` if needed, `npm run build`, then copy `dist/`, `electron/`, `package.json` into `FRONTEND_INSTALL_DIR` (default `/opt/defect-detector-ui`; old hashed assets kept); `--restart-frontend` calls `sudo -n /usr/local/sbin/defect-detector-restart-ui`. Legacy flags (`-r`, `--build-only`, `--config`, `--rt-interval`) accepted and ignored with a message. Python/geometry/positioning are *not* rebuilt (geometry/positioning JARs are built by Maven separately: `mvn -f java-geometry-service/pom.xml package`) |
| `run-supervised.sh [-f] [-r] [--rt-interval N]` | stops the previous stack (`stop-dev.sh --skip-kiosk`), creates `IML_LOG_SESSION_DIR=logs/<date>-<ns>-<pid>`, honours `IML_FRONTEND_AUTOSTART`; if `.kiosk-paused.json` exists resumes the kiosk and disables the dev frontend; exports `IML_ORCHESTRATOR_JAR`; runs `java -cp <jar> …supervisor.StackSupervisorMain $CONFIG` and traps INT/TERM/EXIT → `stop-dev.sh --processes-only --skip-kiosk`. `CONFIG` default `config/config.yaml` |
| `run-rt.sh` | validates `IML_CPUSET` (default `2-5,8-11`, regex-checked) is available (`taskset … python3 -c sched_getaffinity`), then `exec taskset -c $IML_CPUSET bash run-supervised.sh "$@"`; `-r` delegates to `rt-logs.sh` |
| `rt-logs.sh` | runs `deploy/linux/rt_diagnostics.py` (read-only) |
| `stop-dev.sh` | `python3 deploy/linux/stop_stack.py --root <repo> [--processes-only] [--skip-kiosk] [--resume-kiosk]` |
| `run-all-tests.sh [integration]` | `mvn -q test` in orchestrator and geometry; pytest in `analisSurface/backend` (venv or system python; `-m "not integration"` unless argument given); `npm test` in front-end; C tests are run separately |
| `test-timer6.sh` (orchestrator-java) | bench script for Timer6/DO6 |
Docs: `RUN.md`, `RT-LINUX.md`, `LOGGING.md`, `ЗАПУСК.md` (long Russian runbook incl. ports, flags, errors; includes a prompt for another chat), `deploy/linux/README.md`, `deploy/remote-access/README.md`.

## 2. `deploy/linux/`
| File | Role |
|---|---|
| `stack_processes.py` | `/proc` scanner: recognises *our* services by cwd/args under the repo root (java markers `StackSupervisorMain, OrchestratorMain, java-geometry-service, java-positioning-service, GeometryRunnerMain, PositioningRunnerMain`; python `uvicorn app.main:app`; `camera_worker`; node/electron under `/front-end`; launcher shells `run-supervised.sh/run-rt.sh`), kiosk controller detection; `select_services` expands children transitively |
| `stop_stack.py` | ordered stop: optional systemd stop (`defect-detector.service`), pause kiosk (snapshot to `.kiosk-paused.json`), SIGTERM supervisors (≤10 s) then SIGKILL, SIGTERM all targets (timeout 15 s) → re-scan (EXIT traps may spawn cleanup) → SIGKILL → verify no survivors (raises) |
| `rt_diagnostics.py` (+tests) | read-only collector: pins itself to a housekeeping CPU (not in `IML_CPUSET`/isolated/nohz_full), `nice +10`; `RotatingFileHandler` 10 MB × 5 per channel `rt-{samples,configuration,threads,kernel,events}.jsonl`; CPU deltas every 1 s, threads/sensors every 5 s, IRQ affinity every 30 s; PSI, schedstat, cgroup throttling, C-state residency, dmesg/journal stream |
| `system-profile.py` (+tests) | `export`/`install [--check]`/`verify` of *factory host settings* (services `defect-detector, tailscaled, ssh, irqbalance`, kiosk script, xsession, GRUB RT keys `isolcpus, nohz_full, rcu_nocbs, irqaffinity, kthread_cpus`, governor); never copies machine identity/secrets; backups in `/var/backups/defect-detector/<date>/` |
| `setup-frontend-updates.sh <user>` / `defect-detector-restart-ui` | one-time root setup: grants the deploy user write only to `dist/ electron/ package.json` in `/opt/defect-detector-ui` and NOPASSWD sudo on the fixed restart helper (kills only `kiosk` Electron processes) |
Kiosk: `deploy/kiosk/session.sh` (`IML_KIOSK=1`, `IML_ELECTRON_LOG_PATH`, xset dpms off, loop: `electron --ozone-platform=x11 --password-store=basic .` then `sleep 5`), `defect-detector.desktop` (xsession). Remote screen: `deploy/remote-access/*` (Tailscale + OpenSSH tunnel + x11vnc/TigerVNC to `100.122.121.36:5900`; `connect-kiosk.cmd/.ps1`, `kiosk-vnc.service`, `kiosk.tigervnc`, `ssh-config.example`).

## 3. Linux/RT environment
**Recorded profile [doc], not a fresh production measurement:** Ubuntu 24.04, kernel `6.8.1-1015-realtime`, CPUs 0/6,1/7,… as SMT siblings; app mask `2-5,8-11` (= four physical cores on that topology). Boot parameters in `tools/90-iml-cpu-isolation.cfg` (not in git, `/tools/` ignored) → `/etc/default/grub.d` (flags: `isolcpus` without `domain`, `nohz_full=2-5,8-11`, `rcu_nocbs`, IRQ default affinity and kthreads to `0,1,6,7`); irqbalance `IRQBALANCE_BANNED_CPULIST=2-5,8-11`. The owner-confirmed machine has six cores and RT, but its effective current allocation must be checked; see [production environment](./production_environment.md). Historical setup/rollback: `RT-LINUX.md`. Verify: `/proc/cmdline`, `/sys/devices/system/cpu/{isolated,nohz_full}`, `taskset -apc PID`.
systemd unit `defect-detector.service` is **not in git** (exported by `system-profile.py`).

## 4. PLC register map (`plk/`)
`register-map.yaml` — single source for FINS (area, `word.bit`, direction, `bucket_group_id`, timeouts); loaded at orchestrator start (`PlcRegisterMapLoader`). Reserved names: `vision_ready, vision_fault, reject_line_1, reject_line_2`. `25_247_MAP - *.csv` are the Omron MAP exports (zones 1–3, shared zone, inputs, outputs, memory, settings, states/faults/control). Sheet “Входы” → W0.04‥W0.07 (PC→PLC), “Состояния/Неисправности/Управление” → CIO 140…/191…/240…, “Настройки” → DM D4400‥D4405. Mapping table in [orchestrator/api_contracts.md](../orchestrator/api_contracts.md#plc-fins).

## 5. Config templates (`config.exemple/`)
`config.yaml` imports `blocks/01-core, 10-cameras, 20-detectors, 30-ui-and-client, 45-plc-fins, 50-lighting, 51-light-hardware, 52-io`; `config.json` (camera-worker); `data/{camera_runtime_settings,geometry_runtime_settings,light_brightness_settings}.json`. Production `config/` must be created from it (**not versioned**): set camera IPs in *both* `10-cameras.yaml` and `config.json`, flash `com_port_linux` (e.g. `COM_Port#ttyUSB0`), IO `port_linux` (`COM_Port#ttyS4`) and `serial`, PLC host. The YAML aggregator is also read by Python (`ANALIS_SURFACE_CONFIG[_DIR]`) for `python_detector.file_logging`.

## 6. Logging (`LOGGING.md`)
Per-launch directory; Java `java-requests/responses.log`, `pipeline.log`, `orchestrator-runtime.log`, `supervisor-runtime.log`, `geometry-runtime.log`, `positioning-runtime.log`; Python `python-*-<pid>.log`; frontend `frontend-*.log`; plus PLC/lighting/trigger/startup logs; `logs/pipeline-stages.jsonl` timing; RT folder `logs/<ts>-rt-<pid>/`. Writes are async via bounded queues (overflow drops diagnostics, never blocks the pipeline); HTTP logs carry metadata only (no bodies/images).

## 7. Current update and launch commands

Reviewed 2026-10-20 against `rebuild-and-run.sh`, `run-supervised.sh`, `run-rt.sh`, `RUN.md` and `deploy/linux/setup-frontend-updates.sh`. Run application commands **on Linux, from the repository root**. Stop inspection/the line before stopping or updating production services. The installed `defect-detector.service` must point to the intended checkout; the unit itself is machine-specific and not in git.

### Full rebuild and systemd launch

Despite its name, `rebuild-and-run.sh` does not stop/start the backend. For an already provisioned systemd/kiosk host:

```bash
sudo systemctl stop defect-detector.service &&
bash ./rebuild-and-run.sh --restart-frontend &&
sudo systemctl start defect-detector.service
```

The `&&` chain starts the backend only after successful build/installation. A failed build leaves it stopped; inspect the error before deciding how to recover. Do not run the entire build as root just to avoid a service-control password prompt.

Verify the service and readiness after starting:

```bash
sudo systemctl status defect-detector.service --no-pager
curl --fail http://127.0.0.1:8099/health
```

A running supervisor/service is not proof that every camera, reference, light or reject mechanism is operational; confirm application/device readiness before resuming production.

### Kiosk restart setup and frontend-only refresh

`--restart-frontend` requires the installed kiosk and the restricted helper. If not configured, an administrator runs this **once** for deployment user `o`:

```bash
sudo bash deploy/linux/setup-frontend-updates.sh o
```

This changes only the documented UI asset permissions and grants passwordless access to the fixed kiosk-restart helper; it does not grant passwordless backend service control.

For the ROI-preserving reference button, only the frontend changed:

```bash
bash ./rebuild-and-run.sh -c -o --restart-frontend
```

Here `-c` skips camera-worker, `-o` skips orchestrator; both together build/install only the frontend. `-f` skips frontend build/installation and cannot be combined with `--restart-frontend`. Frontend installation alone does not reload an already running Electron. The helper restarts the installed `/opt/defect-detector-ui` kiosk, not the backend or a custom installation directory.

### Python-only changes, manual launch and diagnostics

- Python source changes are loaded after restarting its processes; changing branches while processes run does not reliably activate the new code. For the supervised machine stack use `sudo systemctl restart defect-detector.service` after stopping inspection. Source-only Python changes do not require Maven/CMake rebuilding; changed dependencies may require a separate venv update.
- For an intentional interactive launch instead of systemd: `bash ./run-supervised.sh`. It stops the previous managed stack first; do not try to maintain a second competing stack. Ctrl+C/terminal disconnection can end the foreground launch, so prefer systemd for normal SSH operation.
- `bash ./run-rt.sh -r` or `bash ./run-supervised.sh -r --rt-interval 2` collects metrics from the existing system **only**; `-r` does not start the application. Without `-r`, `run-rt.sh` applies/validates the configured affinity before starting; do not assume its default CPU set is valid on another host.
- Legacy rebuild flags `-r`, `--build-only`, `--config`, `--rt-interval` are accepted but ignored by the rebuild script. `--restart-frontend` is the explicitly supported kiosk-only restart exception to build-only behavior.

## 8. Export machine logs to Windows

Shell redirection is local to the shell where it is written. At a Linux prompt such as `o@o:~$`, `> C:\Users\...` does not save into Windows Downloads. `sudo` grants journal access; it does not change where redirection writes the file. No `cd` to the project is needed to read the systemd journal.

On the Linux machine, export with precise timestamps and without a pager:

```bash
sudo journalctl -u defect-detector.service --since '10 min ago' --no-pager -o short-iso-precise > /home/o/defect_detector.log
```

Then in a **local Windows PowerShell** prompt (`PS C:\...`), download it:

```powershell
scp o@100.122.121.36:/home/o/defect_detector.log "C:\Users\ShablinskiyM\Downloads\defect_detector.log"
```

The host/account/path are examples from the reviewed installation. Use a timestamped filename for each archived experiment: these simple commands overwrite the previous export at that filename, not the original systemd journal. Never infer which experiment a file contains solely from its reusable name; record the journal interval, size, host/config and cycle range.

Analysis rules and the latest retained figures: [machine log review](../performance/2026-10-16-log-review.md). They distinguish DI2-window opening, frame availability, final writes and early reject writes; none alone establishes the exact mechanical-actuation time.
