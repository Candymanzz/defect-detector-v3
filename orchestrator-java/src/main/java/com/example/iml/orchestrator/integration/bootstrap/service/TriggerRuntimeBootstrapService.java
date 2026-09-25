package com.example.iml.orchestrator.integration.bootstrap.service;

import com.example.iml.orchestrator.integration.bootstrap.config.SimultaneousLineCaptureConfig;
import com.example.iml.orchestrator.integration.bootstrap.context.IntegrationRuntimeContext;
import com.example.iml.orchestrator.integration.capture.LineSynchronizedCaptureCoordinator;
import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.diagnostics.TwoPhaseCaptureDiagnostics;
import com.example.iml.orchestrator.integration.lighting.IntervalFlashConfig;
import com.example.iml.orchestrator.integration.lighting.IntervalFlashController;
import com.example.iml.orchestrator.integration.lighting.LightsShutdown;
import com.example.iml.orchestrator.integration.pipeline.bucket.BucketInspectionAggregator;
import com.example.iml.orchestrator.integration.pipeline.bucket.BucketInspectionConfig;
import com.example.iml.orchestrator.integration.pipeline.bucket.JointSeamPolicy;
import com.example.iml.orchestrator.integration.pipeline.session.InspectionCycleResumeService;
import com.example.iml.orchestrator.integration.pipeline.session.PerCameraInspectionGate;
import com.example.iml.orchestrator.integration.trigger.BucketLineTriggerBroadcaster;
import com.example.iml.orchestrator.integration.trigger.Di2CaptureWindow;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerRuntime;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerStrategy;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerStrategyFactory;
import com.example.iml.orchestrator.integration.trigger.config.InspectionTriggerConfig;
import com.example.iml.orchestrator.integration.trigger.strategy.BusTriggerStrategy;
import com.example.iml.orchestrator.integration.trigger.transport.IoInputMonitorUdpTriggerTransport;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Line-sync capture, inspection trigger runtime, interval flash, bucket broadcaster, trigger strategy.
 */
public final class TriggerRuntimeBootstrapService {

    private final Logger log;

    public TriggerRuntimeBootstrapService(Logger log) {
        this.log = log;
    }

    public TriggerWireResult wire(IntegrationRuntimeContext ctx) {
        IntegrationFeatureConfig.ContinuousInspectionConfig continuousInspection =
                IntegrationFeatureConfig.parseContinuousInspection(ctx.integration());
        InspectionTriggerConfig inspectionTriggerConfig = InspectionTriggerConfig.parse(ctx.integration());
        IntegrationFeatureConfig.InspectionTriggerMode triggerMode =
                inspectionTriggerConfig.ioInput().di3Only()
                        || inspectionTriggerConfig.ioInput().directionLatchOnWork()
                        || inspectionTriggerConfig.ioInput().armOnDirection()
                        ? IntegrationFeatureConfig.InspectionTriggerMode.EXTERNAL
                        : IntegrationFeatureConfig.resolveInspectionTriggerMode(ctx.integration());
        if (inspectionTriggerConfig.ioInput().armOnDirection()) {
            log.info(
                    "inspection_trigger arm_on_direction=true — DI{}↑ → di2_window (cameras×2 frames); "
                            + "софтовый DI{} отключён; на камеры только wait_frame (Line0 с железа)",
                    inspectionTriggerConfig.ioInput().directionPort(),
                    inspectionTriggerConfig.ioInput().triggerPort()
            );
        } else if (inspectionTriggerConfig.ioInput().di3Only()) {
            log.info(
                    "inspection_trigger di3_only=true — съёмка по фронту DI{}, направление по текущему DI{}",
                    inspectionTriggerConfig.ioInput().triggerPort(),
                    inspectionTriggerConfig.ioInput().directionPort()
            );
            if (IntegrationFeatureConfig.parseDevAutoTriggerStub(ctx.integration()).enabled()) {
                log.warn("di3_only=true: dev_auto_trigger_stub включён в конфиге, но игнорируется");
            }
            if (continuousInspection.enabled()) {
                log.warn("di3_only=true: continuous_inspection включён в конфиге, но игнорируется");
            }
        }
        if (inspectionTriggerConfig.ioInput().di3Only()
                && inspectionTriggerConfig.ioInput().requireDirection()
                && !inspectionTriggerConfig.ioInput().armOnDirection()) {
            log.info(
                    "inspection_trigger DI2→DI3: съёмка по DI{}↑ только при DI{}=1",
                    inspectionTriggerConfig.ioInput().triggerPort(),
                    inspectionTriggerConfig.ioInput().directionPort()
            );
        }
        if (inspectionTriggerConfig.ioInput().directionLatchOnWork()) {
            log.info(
                    "inspection_trigger direction_latch_on_work=true — DI2 фиксируется при DI1↑, съёмка только по DI{}",
                    inspectionTriggerConfig.ioInput().triggerPort()
            );
        }

        BucketInspectionConfig bucketInspectionConfig =
                BucketInspectionConfig.parse(ctx.integration(), ctx.workersByCamera().keySet());
        List<Integer> inspectionCameraIds = bucketInspectionConfig.enabled()
                ? bucketInspectionConfig.allCameraIds()
                : ctx.workersByCamera().keySet().stream().sorted().toList();
        if (bucketInspectionConfig.enabled()) {
            BucketInspectionAggregator bucketInspectionAggregator = new BucketInspectionAggregator(
                    log,
                    bucketInspectionConfig,
                    JointSeamPolicy.fromGeometryYaml(ctx.geometryCfg())
            );
            ctx.setBucketInspectionAggregator(bucketInspectionAggregator);
            ctx.inspectionGate().setInspectionEnabledOnlyFor(inspectionCameraIds);
            log.info(
                    "inspection bucket enabled groups={} cameras={} timeout_ms={} line_broadcast_interval_ms={}",
                    bucketInspectionConfig.groups(),
                    bucketInspectionConfig.allCameraIds(),
                    bucketInspectionConfig.timeoutMs(),
                    bucketInspectionConfig.lineBroadcastIntervalMs()
            );
        }

        SimultaneousLineCaptureConfig lineCaptureCfg =
                SimultaneousLineCaptureConfig.parse(ctx.integration(), ctx.root());
        wireLineCapture(ctx, lineCaptureCfg, inspectionCameraIds);

        AtomicBoolean softwareVisionReady = new AtomicBoolean(false);
        InspectionTriggerRuntime[] triggerRuntimeHolder = new InspectionTriggerRuntime[1];
        Runnable refreshVisionReady = () -> {
            // Ready держит FanOutCoordinator.onSessionState (эталон → FINS vision_ready sticky).
        };
        InspectionTriggerRuntime triggerRuntime = InspectionTriggerRuntime.start(
                log,
                ctx.integration(),
                inspectionCameraIds,
                triggerMode,
                ctx.bootConfig().captureTriggerStaggerMs(),
                refreshVisionReady,
                triggerRuntimeHolder,
                bucketInspectionConfig.enabled() ? bucketInspectionConfig.groups() : List.of(),
                ctx.manualLineDirection()
        );
        ctx.setTriggerRuntime(triggerRuntime);

        TwoPhaseCaptureDiagnostics phaseCaptureDiagnostics = new TwoPhaseCaptureDiagnostics(log);
        triggerRuntime.bus().setTwoPhaseCaptureDiagnostics(phaseCaptureDiagnostics);
        ctx.captureCoordinator().setTwoPhaseCaptureDiagnostics(phaseCaptureDiagnostics);

        boolean holdUntilFrames = shouldHoldCaptureUntilFrames(inspectionTriggerConfig);
        if (holdUntilFrames) {
            wireCaptureFrameHoldWindow(
                    ctx,
                    inspectionTriggerConfig,
                    inspectionCameraIds,
                    phaseCaptureDiagnostics
            );
        } else {
            wireDi2CaptureWindowGate(ctx, inspectionTriggerConfig);
        }

        wireIntervalFlash(ctx);

        if (ctx.lineCaptureCoordinator() != null) {
            LineSynchronizedCaptureCoordinator lineCaptureRef = ctx.lineCaptureCoordinator();
            triggerRuntime.bus().setLineTriggerListener((seq, at, cameraIds) ->
                    lineCaptureRef.prefireLineTrigger(seq, at.toEpochMilli(), cameraIds));
        }

        IntegrationFeatureConfig.DevAutoTriggerStubConfig devAutoTriggerStub =
                IntegrationFeatureConfig.parseDevAutoTriggerStub(ctx.integration());
        InspectionTriggerStrategy sharedTriggerStrategy;
        if (bucketInspectionConfig.enabled()) {
            if (triggerMode != IntegrationFeatureConfig.InspectionTriggerMode.EXTERNAL) {
                long broadcastIntervalMs = triggerMode == IntegrationFeatureConfig.InspectionTriggerMode.TIMER
                        ? devAutoTriggerStub.intervalMs()
                        : Math.max(
                                bucketInspectionConfig.lineBroadcastIntervalMs(),
                                continuousInspection.cycleDelayMs()
                        );
                BucketLineTriggerBroadcaster broadcaster = new BucketLineTriggerBroadcaster(
                        log,
                        triggerRuntime.bus(),
                        broadcastIntervalMs
                );
                broadcaster.start();
                ctx.setBucketLineTriggerBroadcaster(broadcaster);
            }
            sharedTriggerStrategy = new BusTriggerStrategy(triggerRuntime.bus());
        } else {
            sharedTriggerStrategy = InspectionTriggerStrategyFactory.create(
                    triggerMode,
                    triggerRuntime.bus(),
                    devAutoTriggerStub,
                    continuousInspection
            );
        }
        ctx.setSharedTriggerStrategy(sharedTriggerStrategy);

        if (ctx.clientApiMount() != null && ctx.clientApiMount().inspectionResumeHolder() != null) {
            ctx.clientApiMount().inspectionResumeHolder().set(new InspectionCycleResumeService(
                    log,
                    triggerRuntime.bus(),
                    ctx.inspectionGate(),
                    ctx.bucketInspectionAggregator(),
                    ctx.lineCaptureCoordinator()
            ));
        }

        logSaveAndTriggerInfo(ctx, continuousInspection, triggerMode, inspectionTriggerConfig, devAutoTriggerStub);

        return new TriggerWireResult(
                triggerMode,
                inspectionCameraIds,
                continuousInspection,
                devAutoTriggerStub,
                softwareVisionReady,
                refreshVisionReady
        );
    }

    private void wireLineCapture(
            IntegrationRuntimeContext ctx,
            SimultaneousLineCaptureConfig lineCaptureCfg,
            List<Integer> inspectionCameraIds
    ) {
        if (lineCaptureCfg.enabled() && inspectionCameraIds.size() > 1) {
            LineSynchronizedCaptureCoordinator lineCaptureCoordinator = new LineSynchronizedCaptureCoordinator(
                    inspectionCameraIds,
                    lineCaptureCfg.barrierWaitMs(),
                    lineCaptureCfg.postTriggerSettleMs(),
                    lineCaptureCfg.interWaitFrameMs(),
                    lineCaptureCfg.parallelWaitFrame(),
                    lineCaptureCfg.immediatePrefire(),
                    lineCaptureCfg.hardwareLineTrigger(),
                    lineCaptureCfg.transferWaitWaves(),
                    lineCaptureCfg.transferWaveGapMs()
            );
            lineCaptureCoordinator.bindWorkers(ctx.workersByCamera());
            ctx.setLineCaptureCoordinator(lineCaptureCoordinator);
            ctx.captureCoordinator().setLineCaptureCoordinator(lineCaptureCoordinator);
            lineCaptureCfg.logTopology(log, ctx.root());
            if (lineCaptureCfg.hardwareLineTrigger()) {
                log.info(
                        "hardware_line_trigger: экспозиция по DI3→Line0, Java только wait_frame (без trigger_only/settle/barrier)"
                );
                log.warn(
                        "hardware_line_trigger требует физическую разводку DI3→Line0 всех камер; "
                                + "без неё wait_frame будет timeout (0x80000007)"
                );
            }
        } else if (ctx.bootConfig().captureTriggerStaggerMs() > 0) {
            log.info(
                    "inspection trigger stagger enabled delay_ms={} cameras={}",
                    ctx.bootConfig().captureTriggerStaggerMs(),
                    inspectionCameraIds.size()
            );
        } else {
            log.info(
                    "line synchronized capture disabled (enabled={} cameras={})",
                    lineCaptureCfg.enabled(),
                    inspectionCameraIds.size()
            );
        }
    }

    private void wireIntervalFlash(IntegrationRuntimeContext ctx) {
        IntervalFlashConfig intervalFlashCfg = IntervalFlashConfig.fromRootYaml(ctx.root());
        if (intervalFlashCfg.enabled() && ctx.lightClient().isEnabled()) {
            IntervalFlashController intervalFlashController =
                    new IntervalFlashController(log, ctx.lightClient(), intervalFlashCfg);
            ctx.setIntervalFlashController(intervalFlashController);
            LightsShutdown.bindIntervalFlash(intervalFlashController);
            ctx.triggerRuntime().addDiChangeListener(intervalFlashController::onDiChange);
            ctx.lightClient().setCaptureLightingActive(intervalFlashController::captureLightingActive);
            intervalFlashController.setFlushDeferredBrightness(ctx.lightClient()::flushDeferredBrightness);
            if (ctx.lineCaptureCoordinator() != null) {
                ctx.lineCaptureCoordinator().setOnFirstFrameCaptured(intervalFlashController::onFirstFrameCaptured);
            }
            intervalFlashController.armStartDark();
            log.info(
                    "interval_flash enabled — DI{}↑ → On, DI{}↓ → Off (импульс направления); hold_mode={}",
                    intervalFlashCfg.idlePort(),
                    intervalFlashCfg.idlePort(),
                    ctx.lightClient().isHoldMode()
            );
        } else if (intervalFlashCfg.enabled()) {
            log.warn("interval_flash enabled, но light_servers выключены — вспышки по DI не активны");
        } else if (ctx.lightClient().isEnabled()) {
            ctx.lightClient().setAfterBrightnessApplied(null);
            boolean alwaysOn = ctx.lightClient().bankAllOn("always-on-startup");
            log.info(
                    "always_on lighting startup bankOn={} hold_mode={} interval_flash={}",
                    alwaysOn,
                    ctx.lightClient().isHoldMode(),
                    false
            );
        }
    }

    /**
     * Держим wait_frame до cameras×2 кадров: DI2↓ не отменяет; закрытие по счётчику/таймауту.
     * Открытие окна: DI2↑ (arm_on_direction) или первый DI3↑ (обычный HW path).
     */
    private static boolean shouldHoldCaptureUntilFrames(InspectionTriggerConfig triggerCfg) {
        var io = triggerCfg.ioInput();
        return io.armOnDirection()
                || (io.externalHardwareCapture() && io.di3Only() && triggerCfg.twoPhase().enabled());
    }

    /**
     * Окно cameras×2 кадров: DI2↓ не отменяет wait; phase0/phase1 параллельно; prefetch 2-го кадра.
     */
    private void wireCaptureFrameHoldWindow(
            IntegrationRuntimeContext ctx,
            InspectionTriggerConfig triggerCfg,
            List<Integer> inspectionCameraIds,
            TwoPhaseCaptureDiagnostics phaseCaptureDiagnostics
    ) {
        IoInputMonitorUdpTriggerTransport transport = ctx.triggerRuntime() == null
                ? null
                : ctx.triggerRuntime().ioInputTransport();
        if (transport == null || ctx.inspectionGate() == null) {
            log.warn("capture frame hold: io_input transport/gate missing — window not armed");
            return;
        }
        int cameraCount = inspectionCameraIds == null || inspectionCameraIds.isEmpty()
                ? ctx.inspectionGate().cameraIds().size()
                : inspectionCameraIds.size();
        long timeoutMs = Math.max(
                3_500L,
                YamlScalars.toLong(ctx.integration().get("inspection_cycle_timeout_ms"), 3_500L) * 2L
        );
        Di2CaptureWindow window = new Di2CaptureWindow(
                log,
                ctx.inspectionGate(),
                cameraCount,
                2,
                timeoutMs,
                transport::isDirectionActive
        );
        transport.setDi2CaptureWindow(window);
        phaseCaptureDiagnostics.setCaptureOkListener(window::onCaptureOk);
        // One worker handles one binary command at a time. Serialize phase waits.
        // Phase 1 must not overtake phase 0 on the same worker/FIFO.
        ctx.inspectionGate().setAwaitPriorPhase(true);
        if (ctx.lineCaptureCoordinator() != null) {
            // Both physical pulses arrive on Line0. The receive FIFO retains
            // the second frame until the ordered phase-1 command consumes it.
            ctx.lineCaptureCoordinator().setPrefetchNextFrame(true);
        }
        String openBy = triggerCfg.ioInput().armOnDirection() ? "DI2↑" : "DI3↑/DI5↑";
        log.info(
                "inspection_trigger capture_window target_frames={} timeout_ms={} open_by={} "
                        + "(DI2↓ grace then close; new triggers only while DI2=1)",
                window.targetFrames(),
                timeoutMs,
                openBy
        );
    }

    /**
     * DI2↓: отменяем только phase0 in-flight (режим без hold-окна на 20 кадров).
     */
    private void wireDi2CaptureWindowGate(
            IntegrationRuntimeContext ctx,
            InspectionTriggerConfig triggerCfg
    ) {
        if (ctx.triggerRuntime() == null || ctx.inspectionGate() == null) {
            return;
        }
        if (!triggerCfg.usesIoInputMonitor() || !triggerCfg.ioInput().requireDirection()) {
            return;
        }
        if (shouldHoldCaptureUntilFrames(triggerCfg)) {
            return;
        }
        int directionPort = triggerCfg.ioInput().directionPort();
        PerCameraInspectionGate gate = ctx.inspectionGate();
        ctx.triggerRuntime().addDiChangeListener(change -> {
            if (change.diPort() != directionPort || change.active()) {
                return;
            }
            var cancelled = gate.requestCancelAllInFlight(true);
            if (!cancelled.isEmpty()) {
                log.info(
                        "io_input_trigger DI{}↓ — drop in-flight phase0 captures (phase1 kept) cameras={}",
                        directionPort,
                        cancelled
                );
            }
        });
        log.info(
                "inspection_trigger DI{}↓ → cancel phase0 wait_frame only (phase1 waits after DI2=0)",
                directionPort
        );
    }

    private void logSaveAndTriggerInfo(
            IntegrationRuntimeContext ctx,
            IntegrationFeatureConfig.ContinuousInspectionConfig continuousInspection,
            IntegrationFeatureConfig.InspectionTriggerMode triggerMode,
            InspectionTriggerConfig triggerCfg,
            IntegrationFeatureConfig.DevAutoTriggerStubConfig devAutoTriggerStub
    ) {
        IntegrationFeatureConfig.SaveCapturesConfig saveCaptures =
                IntegrationFeatureConfig.parseSaveCaptures(ctx.integration());
        if (saveCaptures.enabled()) {
            log.info("save_captures enabled dir={} (от корня проекта)", saveCaptures.relativeDir());
        }
        if (devAutoTriggerStub.enabled()) {
            log.info("dev_auto_trigger_stub enabled interval_ms={}", devAutoTriggerStub.intervalMs());
        } else if (continuousInspection.enabled()) {
            log.info("continuous_inspection enabled cycle_delay_ms={}", continuousInspection.cycleDelayMs());
        } else if (triggerMode == IntegrationFeatureConfig.InspectionTriggerMode.EXTERNAL) {
            if (triggerCfg.usesIoInputMonitor()) {
                String logDir = System.getProperty("iml.log.dir", "logs");
                LogManager.getLogger("com.example.iml.orchestrator.integration.ioinput.external")
                        .info(
                                "=== di-capture-timeline session: orchestrator (DI/capture filter) + IoInputMonitor stdout → {}/di-capture-timeline.log ===",
                                logDir
                        );
                log.info(
                        "inspection_trigger external io_input {}:{} di={}/{}/{} shutdown_di={} trigger_edge={} di3_only={} arm_on_direction={} direction_latch_on_work={} direction_arm_next_di3={} require_direction={} require_work={} direction_invert={} direction_wait_ms={} direction_poll_ms={} debounce_ms={} stub_work={}",
                        triggerCfg.udp().bindHost(),
                        triggerCfg.udp().bindPort(),
                        triggerCfg.ioInput().workPort(),
                        triggerCfg.ioInput().directionPort(),
                        triggerCfg.ioInput().triggerPort(),
                        triggerCfg.ioInput().shutdownPort(),
                        triggerCfg.ioInput().triggerEdge(),
                        triggerCfg.ioInput().di3Only(),
                        triggerCfg.ioInput().armOnDirection(),
                        triggerCfg.ioInput().directionLatchOnWork(),
                        triggerCfg.ioInput().directionArmNextDi3(),
                        triggerCfg.ioInput().requireDirection(),
                        triggerCfg.ioInput().requireWork(),
                        triggerCfg.ioInput().directionInvert(),
                        triggerCfg.ioInput().directionWaitMs(),
                        triggerCfg.ioInput().directionPollMs(),
                        triggerCfg.ioInput().debounceMs(),
                        triggerCfg.ioInput().stubWorkActive()
                );
            } else if (triggerCfg.udp().enabled()) {
                log.info(
                        "inspection_trigger external udp {}:{} format={}",
                        triggerCfg.udp().bindHost(),
                        triggerCfg.udp().bindPort(),
                        triggerCfg.udp().format()
                );
            } else {
                log.warn("inspection_trigger external mode but udp.enabled=false");
            }
        }
    }

    public record TriggerWireResult(
            IntegrationFeatureConfig.InspectionTriggerMode triggerMode,
            List<Integer> inspectionCameraIds,
            IntegrationFeatureConfig.ContinuousInspectionConfig continuousInspection,
            IntegrationFeatureConfig.DevAutoTriggerStubConfig devAutoTriggerStub,
            AtomicBoolean softwareVisionReady,
            Runnable refreshVisionReady
    ) {
    }
}
