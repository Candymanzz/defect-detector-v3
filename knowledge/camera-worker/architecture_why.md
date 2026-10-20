# camera-worker — architecture "WHY"

Back to [overview](./overview.md) · [orchestrator why](../orchestrator/architecture_why.md)
Evidence: **[doc]**, **[code]**, `⚠️ [GUESS/CONJECTURE]`.

## 1. Why native acquisition inside the orchestrator JVM
- **[code: 2026-10-20]** The camera path now calls the existing C acquisition code through JNI. `NativeCameraClient` owns each camera state, settings and stream within the JVM; metadata crosses JNI and pixels stay in SHM. This removes camera subprocesses and stdio transport. `WorkerProcessSupervisor` reopens a native camera after command failure. Native crashes now affect the JVM; the outer stack supervisor handles JVM restart.
- SDK initialization/finalization is shared with IO and lights via `MvsSdkLease`, keyed by the actual loaded vendor library. Enumeration/open uses the same SDK enumeration lock; frame capture remains per camera. Cancellation precedes close so an SDK wait cannot use freed camera memory. See [CAMERA-JNI](../../orchestrator-java/CAMERA-JNI.md).
- No cross-camera locks: commit `bb80fcbd` (2026‑06‑18) “убрал глобальную синхронизацию на воркерах” **[doc]**. Per-camera state is private; only the stream thread shares a mutex with the command loop.
- Startup: the orchestrator opens cameras with the configured startup stagger (`worker_startup_stagger_ms`) and 30 s command timeout — parallel `MV_CC_OpenDevice` for ten GigE cameras caused false “startup timeout” failures **[doc: `01-core.yaml` comments]**.
- `MV_CC_OpenDevice` is retried 4× at 250 ms and the camera is selected by **IP** (`nCurrentIp`) rather than enumeration order — enumeration order is not stable across boots, while IPs map to physical positions **[code]** `W:main.c:1696-1735`.

## 2. Why BGR8 in SHM and no analysis in the worker
- Colour needed by the UI and the detector; conversion once in the worker (`hik_copy_frame_to_bgr`) so every consumer reads the same bytes. Mono8 would cut GigE traffic 3× but loses colour (config comment suggests it as an option) **[doc]**.
- SHM instead of pipe payloads: the orchestrator, geometry (Java) and analisSurface (Python) all read the same pixels with zero copies; `IMLB` payload length is always 0 **[code]**.
- Ring of 8 slots: prevents overwriting a frame still being read when the next trigger arrives; the orchestrator additionally copies to a per-cycle pin (`iml_line_pin_*`) so the guarantee does not depend on the 8‑slot lifetime **[doc: `LineFramePinService` javadoc]**.

## 3. Hardware trigger & buffer discipline
- **Line0 + RisingEdge** (`line0_trigger_activation`, `LineInverter`): “Полярность DO5 (ПЛК/IO) ↔ Line0: один edge на обоих концах” **[doc: comment `W:main.c:~2864`]**. Mode locked while streaming because streaming forces `continuous` and restores on stop (`stream_restore_trigger_mode`).
- `hik_flush_image_buffer` = `ClearImageBuffer` only. The code comment (**[doc]** `W:main.c:1119-1124`): draining with `GetOneFrameTimeout(30)×N` “на HW Line0 съедает кадр с DO5, если flush ещё идёт когда приходит фронт → «рандомные» кадры со следующего импульса / шума”. Known residual risk (audit §5): a frame already buffered when `wait_frame` starts is dropped.
- **Capture window** (`capture_window_*`): hardware triggers also fire outside the DI2 inspection window; the worker discards buffered frames and aborts the wait when the window closes. ⚠️ [GUESS/CONJECTURE] added in `006b242e` (2026‑10‑12 “рабочая версия для линукса”) together with `test_capture_window.c`; reason inferred from the code comment “Hardware triggers also produce frames outside the DI2 inspection window”.
- Frame validation rejects incomplete frames (`nLostPacket>0`) instead of passing partially-filled SHM to the detector: a corrupt frame would otherwise be a false reject or, worse, a false pass.

## 4. Why GigE shaping lives in the worker
- Ten cameras exposed at the same electrical edge would transmit 10 × 3.7 MB (BGR8) at once into five 1 Gb uplinks with 448 KB switch buffers → packet loss/resends. Shaping (GevSCPD + GevSCFTD slots) spreads transmissions: slot‑0 cameras first, slot‑1 after one frame time; **[doc]** `01-core.yaml` comments (“Если resend/потери — поднять до 15000–20000; если медленно — снизить до 8000”, “BGR8 трафик ~3× Mono8”).
- `gige_transfer_slot` is explicit per camera (YAML `10-cameras.yaml`) because physical pairing of cameras on switches doesn't follow `id % 2` (comment: “фактические пары id: 0+3, 1+4, 2+8, 5+6, 7+9”); `id % per_link` is only the fallback **[code]** `hik_frame_transfer_slot_index`.
- Defaults auto-adapt to small switch buffers (≤96 KB): ⚠️ [GUESS/CONJECTURE] legacy hardware generation (older switches had 48 KB) – the project moved to 448 KB and kept both branches.

## 5. Why binning on the sensor + center-crop fallback
- Binning 2×2 `Sum` halves resolution on the camera → less GigE traffic and CPU on PC; “Downscale на ПК отключён: разрешение уже уменьшено binning 2×2 на камере” **[doc]**. If the SDK refuses (0x80000109) a centred 1224×1024 ROI is used so frame size stays constant for all consumers (`hik_apply_roi_center_crop`).
- Consequence: `pixels_to_mm` in geometry is 0.02 (≈ ×2 of unbinned) and must be recalibrated if binning changes **[doc: `20-detectors.yaml`]**.

## 6. Config format
The worker reads a separate flat `config/config.json` with a tiny jsmn tokenizer; the YAML aggregator header says so explicitly (“camera-worker по-прежнему читает config/config.json”). ⚠️ [GUESS/CONJECTURE] a C JSON reader is simpler than YAML; cost: camera IPs/exposure must be edited in two places.

## 7. Known gaps (from `LINUX-PIPELINE-AUDIT.md` 2026‑10‑08)
Errors setting `TriggerMode/TriggerSource` are ignored (return values cast to `void` in `hik_configure_trigger_mode`); successful process start does not prove correct hardware trigger configuration – check SDK logs/frames. The parser `json_find_*` returns the first occurrence of a key, so nested/duplicate keys in a header could be mis-read.
