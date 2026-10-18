# legacy hardware — architecture "WHY"

Back to [overview](./overview.md) · [orchestrator why §2](../orchestrator/architecture_why.md)

| Decision | Reason | Evidence |
|---|---|---|
| Separate C# **LightServer** with HTTP API (May 2026) | MV‑LE controllers had a .NET SDK; HTTP made the first orchestrator integration trivial (`5b05aa4b` “добавлена интеграция lightserver.v3”, `9ad1bded` link points) | **[doc]** commits |
| Isolated COM bank with per-port lock + barrier | MVS serial SDK is not safe to share; simultaneous flash needed all controllers to fire together (“вспышки работают одновременно”, `2fd3e9e8`) | **[doc]** `GUIDE.md` “Почему два контура” |
| Flash sync modes (Direct/Hold/Deferred/Broadcast) | MV‑LE cannot always switch 4 channels at once; Timer1 + software trigger gives a hardware-simultaneous edge | **[doc]** `MvLeFlashSync` section of GUIDE |
| Separate **IoInputMonitor** process | The IO box SDK is the only way to read DI (callbacks); isolating it avoided blocking the JVM and allowed Windows-only DLLs | **[doc]** `IoInputMonitor/docs/GUIDE.md` “SDK или просто GPIO” |
| UDP :9100 between monitor and orchestrator | simplest decoupling; payload `{"di":N,"value":V}`; later extended to UI direction HTTP and DO5 pulse | **[doc]** guide; `5bfe41ed` “дискретка и финс” (DO/UDP reject client added), `5f7ab035` “фикс разброса съёмки” |
| `IoSiblingProcessKiller` | duplicate monitors (second instance) kept sending signals and held the COM port (`0x80000004`) | **[doc]** commit `ab3c234b` “killer”, guide “Порт занят” |
| Merge into the JVM (Oct 2026) | one SDK handle for DI + DO + timers on Linux; remove .NET and an extra hop on the reject path; removal of IO box DO code from monitor (“Код reject/DO1–4/DO6 удалён”) while Java gained hold-mode DO | **[doc]** `e58636e1`, `MVS-IO.md`, `LINUX-PIPELINE-AUDIT.md` |
| Keep legacy code in repo | reference for Windows fall-back and for the exact SDK call sequences (`MvLeFlashSync`), tests encode edge cases | ⚠️ [GUESS/CONJECTURE] |
| `mvs-io-service` in Python | fastest way to probe SDK nodes on Linux (`TriggerSoftware` vs `TimerTriggerSoftware`) before porting to JNA | ⚠️ [GUESS/CONJECTURE]; README states the finding |

Do **not** start these services together with the orchestrator on the same machine: the orchestrator opens the same serial devices exclusively (`MV_ACCESS_Exclusive`/COM), so a second owner fails with busy errors.
