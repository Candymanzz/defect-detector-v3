package com.example.iml.orchestrator.integration.trigger.impl;

import com.example.iml.orchestrator.integration.trigger.api.TriggerTransport;

import com.example.iml.orchestrator.integration.trigger.InspectionTriggerBus;
import com.example.iml.orchestrator.integration.trigger.ManualLineDirectionService;
import com.example.iml.orchestrator.integration.trigger.config.IoInputDiscreteConfig;
import com.example.iml.orchestrator.integration.trigger.config.UdpTriggerConfig;
import com.example.iml.orchestrator.integration.trigger.gpio.LineDiscreteTriggerEvaluator;
import com.example.iml.orchestrator.integration.trigger.parse.IoInputDiChange;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * UDP-слушатель DI: DI2 — текущее направление, DI3 — триггер съёмки.
 */
public final class IoInputMonitorUdpTriggerTransportImpl implements TriggerTransport {

    private final Logger log;
    private final UdpTriggerConfig udpConfig;
    private final IoInputDiscreteConfig ioInputConfig;
    private final ScheduledExecutorService directionWaitExecutor;
    private final ScheduledExecutorService captureDelayExecutor;
    private final IoInputDirectionWaiter directionWaiter;
    private final IoInputLiveState live = new IoInputLiveState();
    private final IoInputUdpListener udpListener;
    private final IoInputLineCapturePublisher capturePublisher;
    private final IoInputDiChangeProcessor diProcessor;
    private final AtomicBoolean lineWorkActive = new AtomicBoolean(false);
    /** Подписчики DI (например interval_flash) — не влияют на capture. */
    private final CopyOnWriteArrayList<Consumer<IoInputDiChange>> diChangeListeners = new CopyOnWriteArrayList<>();

    public IoInputMonitorUdpTriggerTransportImpl(
            Logger log,
            UdpTriggerConfig udpConfig,
            IoInputDiscreteConfig ioInputConfig,
            InspectionTriggerBus bus,
            Runnable onLineWorkChanged
    ) {
        this(log, udpConfig, ioInputConfig, bus, onLineWorkChanged, null);
    }

    public IoInputMonitorUdpTriggerTransportImpl(
            Logger log,
            UdpTriggerConfig udpConfig,
            IoInputDiscreteConfig ioInputConfig,
            InspectionTriggerBus bus,
            Runnable onLineWorkChanged,
            ManualLineDirectionService manualLineDirection
    ) {
        this.log = log;
        this.udpConfig = udpConfig;
        this.ioInputConfig = ioInputConfig;
        Runnable lineWorkChanged = onLineWorkChanged == null ? () -> { } : onLineWorkChanged;
        LineDiscreteTriggerEvaluator evaluator = new LineDiscreteTriggerEvaluator(ioInputConfig.triggerEdge());
        IoInputDirectionLatch directionLatch = new IoInputDirectionLatch();
        IoInputDirectionAutoCapture directionAutoCapture = new IoInputDirectionAutoCapture();
        IoInputWorkSessionDirection workSessionDirection = new IoInputWorkSessionDirection();
        this.directionWaitExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "io-input-direction-wait");
            t.setDaemon(true);
            return t;
        });
        this.captureDelayExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "io-input-capture-delay");
            t.setDaemon(true);
            return t;
        });
        AtomicReference<IoInputDiChangeProcessor> processorRef = new AtomicReference<>();
        this.capturePublisher = new IoInputLineCapturePublisher(
                log,
                ioInputConfig,
                bus,
                live,
                workSessionDirection,
                directionAutoCapture,
                manualLineDirection,
                captureDelayExecutor,
                () -> processorRef.get().usesAutoDirection()
        );
        this.directionWaiter = new IoInputDirectionWaiter(
                log,
                directionWaitExecutor,
                ioInputConfig.directionWaitMs(),
                ioInputConfig.directionPollMs(),
                () -> live.directionActive,
                () -> !ioInputConfig.requireWork() || live.isEffectiveWork(ioInputConfig),
                () -> capturePublisher.tryCommitLineCapture("direction wait", false),
                () -> processorRef.get().logDirectionWaitTimeout()
        );
        this.diProcessor = new IoInputDiChangeProcessor(
                log,
                ioInputConfig,
                live,
                directionLatch,
                directionAutoCapture,
                workSessionDirection,
                directionWaiter,
                evaluator,
                manualLineDirection,
                lineWorkChanged,
                capturePublisher,
                lineWorkActive,
                this::notifyDiChangeListeners
        );
        processorRef.set(diProcessor);
        this.udpListener = new IoInputUdpListener(log, udpConfig, ioInputConfig, diProcessor::applyDiChange);
        if (ioInputConfig.stubWorkActive()) {
            live.workActive = true;
            lineWorkActive.set(true);
            if (ioInputConfig.directionLatchOnWork()) {
                workSessionDirection.onWorkStarted(live.triggerActive, log);
                workSessionDirection.onDirectionChange(
                        live.directionActive, live.directionRawActive, true, live.triggerActive, log);
            }
        }
    }

    public boolean isLineWorkActive() {
        return lineWorkActive.get();
    }

    /** При {@code require_work: true} vision_ready следует за DI1; иначе — нет. */
    public boolean gatesVisionReadyByLineWork() {
        return ioInputConfig.requireWork();
    }

    @Override
    public void start() {
        if (!udpConfig.enabled()) {
            return;
        }
        udpListener.start(() -> {
            if (ioInputConfig.directionLatchOnWork()) {
                if (diProcessor.usesAutoDirection()) {
                    log.info(
                            "io_input_trigger autonomous: direction_latch_on_work + require_work=false — DI2 idle latch, instant DI3 capture (DI1 не требуется)"
                    );
                } else {
                    log.info(
                            "io_input_trigger direction_latch_on_work: await DI1↑ (заведение) to latch DI2, then DI3 capture"
                    );
                }
            }
        });
    }

    /**
     * Подписка на сырые DI-события (до логики съёмки). Ошибки слушателя не ломают capture.
     */
    public void addDiChangeListener(Consumer<IoInputDiChange> listener) {
        if (listener != null) {
            diChangeListeners.add(listener);
        }
    }

    private void notifyDiChangeListeners(IoInputDiChange change) {
        for (Consumer<IoInputDiChange> listener : diChangeListeners) {
            try {
                listener.accept(change);
            } catch (Exception e) {
                log.warn("io_input_trigger di listener failed: {}", e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        udpListener.stop();
        capturePublisher.cancelDelayedCapture();
        directionWaiter.close();
        captureDelayExecutor.shutdown();
        directionWaitExecutor.shutdown();
        try {
            if (!captureDelayExecutor.awaitTermination(500L, TimeUnit.MILLISECONDS)) {
                captureDelayExecutor.shutdownNow();
            }
            if (!directionWaitExecutor.awaitTermination(500L, TimeUnit.MILLISECONDS)) {
                directionWaitExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            captureDelayExecutor.shutdownNow();
            directionWaitExecutor.shutdownNow();
        }
        udpListener.close();
        log.info("io_input_trigger stopped");
    }
}
