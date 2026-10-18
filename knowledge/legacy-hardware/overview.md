# Legacy hardware helpers (`LightServer.v3/`, `IoInputMonitor/`, `mvs-io-service/`)

Back to [global_architecture.md](../global_architecture.md) · [Why](./architecture_why.md) · Replacement code: [orchestrator overview §3.3 / §6](../orchestrator/overview.md)

These were the original separate hardware services. Since `e58636e1` (2026‑10‑08) their functions run **inside the orchestrator** (JNA to `MvCameraControl`). They stay in git as reference implementations, Windows fall-backs and bench tools. **None is started by the Linux production stack.** Contracts they used are still emulated by the orchestrator (HTTP `/api/camera-flash/*`, `/api/com/*`).

## 1. LightServer.v3 (C# / ASP.NET, Kestrel `http://127.0.0.1:5080`, Swagger `/swagger`)
**Purpose:** HTTP control of **MV‑LE flash controllers** (Hikrobot) – COM and Ethernet – and routing “camera → (device, channels)”. 11 kLOC across both C# projects incl. tests.
Stack: .NET (net10.0 in bin paths), `MvCameraControl.Net` (SDK DLLs under `ThirdParty/MVS/{DotNet,Runtime/win64}`), xUnit tests (`LightServer.Tests`).
| Area | Files |
|---|---|
| Config | `Configuration/LightConfigLoader.cs` (walks up from cwd/exe to find `config/blocks/51-light-hardware.yaml`), `appsettings.json` (SDK fine tuning: `SerialLight`, `FlashSyncMode`, `BankFlashMode`, `DisableSdkLock` …), `LightHardwareRegistry`, `LightHardwareBindingPostConfigure`, `ComLightDevicesOptions`, `IoControllerOptions` |
| HTTP | `CameraFlashBankController` (`POST|GET /api/camera-flash/bank` → whole bank on/off), `CameraFlashController` (`POST /api/camera-flash/pair|single`, `GET /routes`), `ComLightApplyController` (`POST|GET /api/com/light`), `ComLightController` (`GET /api/com/devices`), `LightController` (`GET /api/devices`, `POST /api/light` network) |
| COM bank (mass on/off) | `ComLightBankService` → `ComLightIsolatedBank` → `IsolatedComPortLight` ×N (one `IDevice` + lock per COM; parallel prepare + `Barrier` so all fire together) ; hosted init `ComLightBankHostedService` (background, partial open OK, `skippedPorts`) |
| Sessions (per camera) | `LightControlService` (1 233), `MvLeSerialLightSessions` (long-lived sessions, cached `EnumDevices`), `MvLeApplyState` (remember “hardware armed”), `CameraFlashBrightnessCache` |
| Ethernet | `EthernetMvLeBank`, `IsolatedEthernetLight`, `EthernetMvLeBankHostedService` |
| Sync modes | `MvLeFlashSync.cs` (1 319): **Direct** (selector→brightness→source On), **Hold** (phase 1 arm Timer1+brightness for all channels, phase 2 one software trigger, optional sustain), **Deferred** (timer pulse), **Broadcast** (selector `All`) |
| Logging | `Logging/*` (session file logger, HTTP exchange middleware) |
Docs: `LightServer.v3/docs/GUIDE.md` (architecture “два контура COM”, troubleshooting table: `0x800000FF` = COM busy by MVS Client/second server; `/pair` brightness raw 0..255 vs `/api/com/light` percent).
Linux fix `e58636e1`: `MvsComPortEnumerator` normalises `COM_Port#ttyS4` / `ttyS4` / `/dev/ttyUSB0`.
**Replacement in Java:** `O:lighting/nativeio/{NativeLightBank,JnaLightDevice,NativeLightConfig}`, `O:lighting/LightTriggerClient`, `O:http/controller/LightHttpController` (same paths, in-process dispatch).

## 2. IoInputMonitor (C# console)
**Purpose:** reads **DI** of the Hikrobot **MV IO Box** via official `MvIOInterfaceBox.dll`/`MvSerial.dll` edge callbacks (`MV_IO_RegisterEdgeDetectionCallBack`; “only official SDK works — no raw GPIO/serial protocol”) and publishes `{"di":N,"value":0|1}` over **UDP :9100** to the orchestrator; also drove **DO5** (camera Line0 pulse) in the DI3↑ chain.
Files: `Program.cs` (modes: monitor, `--list-ports`, `--probe`, `--simulate-di3`, `--hw-di3-do5`, `--pulse-port`), `IoBoxSession` (SDK session, reconnect), `IoDiEdgeTracker` (“SDK holds one edge filter per port → re-arm opposite edge after each event”), `IoCaptureGate` / `IoCapturePulseScheduler` (gate and one-in-flight DO5 pulse; direction latch variants), `IoDoExecutor`, `MvIoTimerTrigger`, `IoInputUdpPublisher` (formats `json|text_di|byte_di|byte|text`), `IoLineDirectionHttpServer` (UI forward/reverse), `IoSiblingProcessKiller` (kills duplicate monitor instances; commit `ab3c234b`), `IoInputConfigLoader` (YAML `io_input` from `config/blocks/52-io-input.yaml`), tests `IoInputMonitor.Tests`.
Docs: `IoInputMonitor/docs/GUIDE.md` (why signals keep coming: `direction_latch` not released, `debounce_ms 0`, UDP not gated by capture gate, overlapping `Task.Run` fixed by `IoCapturePulseScheduler`).
**Replacement in Java:** `O:io/mvs/*` (direct SDK via `MvCameraControl` on Linux), `O:trigger/transport/IoInputTriggerTransport` (same DI semantics), UDP transport kept but **disabled** (`inspection_trigger.udp.enabled:false`).

## 3. mvs-io-service (Python, Linux bench/diagnostics)
Purpose: prove MVS SDK control of the MV‑VC controller from Linux without the JVM: `keyboard.py` (Space → Timer6 → DO6; flags `--port 'COM_Port#ttyS4' --serial DA8813541 --duration-ms --watch --check --list --debounce-ms`), `service.py` (older diagnostic HTTP service for the same path), tests `test_keyboard.py`, `test_service.py`. Uses `/opt/MVS/Samples/64/Python/MvImport` structures + `libMvCameraControl.so` via `ctypes`. Reads Out6→Timer6 routing & polarity & timer duration, optionally temporarily sets duration, restores on exit; never rewrites the MVS routing. Finding: the controller exposes **`TriggerSoftware`** (not `TimerTriggerSoftware`). Doc: `mvs-io-service/README.md`. The Java equivalent is `MvsTimerCommand.trigger` + `MvsIoDiagnosticMain` / `MvsTimerSmokeMain` (`O:io/mvs/`).
