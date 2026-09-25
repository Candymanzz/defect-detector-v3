package com.example.iml.orchestrator.integration.trigger.transport;

import com.example.iml.orchestrator.integration.pipeline.bucket.BucketGroup;
import com.example.iml.orchestrator.integration.trigger.Di2CaptureWindow;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerBus;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerEvent;
import com.example.iml.orchestrator.integration.trigger.ManualLineDirectionService;
import com.example.iml.orchestrator.integration.trigger.TwoPhaseTriggerCorrelator;
import com.example.iml.orchestrator.integration.trigger.config.IoInputDiscreteConfig;
import com.example.iml.orchestrator.integration.trigger.config.TwoPhaseTriggerConfig;
import com.example.iml.orchestrator.integration.trigger.config.UdpTriggerConfig;
import com.example.iml.orchestrator.integration.trigger.gpio.LineDiscreteTriggerEvaluator;
import com.example.iml.orchestrator.integration.trigger.gpio.TriggerEdgeMode;
import com.example.iml.orchestrator.integration.trigger.parse.IoInputDiChange;
import com.example.iml.orchestrator.integration.trigger.parse.IoInputDiChangeParser;
import org.apache.logging.log4j.Logger;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * UDP-слушатель DI: DI2 — направление, DI3 — триггер съёмки, DI4 — безопасное выключение (listener).
 */
public final class IoInputMonitorUdpTriggerTransport implements TriggerTransport {

    private final Logger log;
    private final UdpTriggerConfig udpConfig;
    private final IoInputDiscreteConfig ioInputConfig;
    private final TwoPhaseTriggerConfig twoPhaseConfig;
    private final TwoPhaseTriggerCorrelator twoPhaseCorrelator;
    private final InspectionTriggerBus bus;
    private final Runnable onLineWorkChanged;
    private final LineDiscreteTriggerEvaluator evaluator;
    private final IoInputDirectionLatch directionLatch = new IoInputDirectionLatch();
    private final IoInputDirectionAutoCapture directionAutoCapture = new IoInputDirectionAutoCapture();
    private final IoInputWorkSessionDirection workSessionDirection = new IoInputWorkSessionDirection();
    private final List<BucketGroup> bucketGroups;
    private final ManualLineDirectionService manualLineDirection;
    private final ScheduledExecutorService directionWaitExecutor;
    private final IoInputDirectionWaiter directionWaiter;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean lineWorkActive = new AtomicBoolean(false);
    /** Подписчики DI (например interval_flash) — не влияют на capture. */
    private final CopyOnWriteArrayList<Consumer<IoInputDiChange>> diChangeListeners = new CopyOnWriteArrayList<>();

    private volatile boolean workActive;
    private volatile boolean directionRawActive;
    private volatile boolean directionActive;
    /** Первый DI2=1 при direction_latch — дальше DI2 холостой для съёмки. */
    private volatile boolean directionLatched;
    private volatile boolean directionInitialized;
    private volatile boolean triggerActive;
    private volatile boolean captureFiredThisPulse;
    /** Один кадр на окно DI2=1: повторный DI3↑ при DI2=1 — холостой. */
    private volatile boolean captureFiredThisDi2Window;
    private volatile ScheduledFuture<?> delayedCaptureTask;
    private volatile long di3RiseEpochMs;
    private final ScheduledExecutorService captureDelayExecutor;
    private final ScheduledExecutorService autoSecondPhaseExecutor;
    private volatile ScheduledFuture<?> autoSecondPhaseTask;
    private volatile long autoSecondPhaseParentCycle = -1L;
    private volatile long autoSecondPhasePhase0EpochMs;
    private final IoInputMonitorSyntheticCaptureClient syntheticCaptureClient;
    private long lastFireMs;
    private Thread listenerThread;
    private DatagramSocket socket;
    private volatile Di2CaptureWindow di2CaptureWindow;

    public IoInputMonitorUdpTriggerTransport(
            Logger log,
            UdpTriggerConfig udpConfig,
            IoInputDiscreteConfig ioInputConfig,
            InspectionTriggerBus bus,
            Runnable onLineWorkChanged
    ) {
        this(log, udpConfig, ioInputConfig, bus, onLineWorkChanged, List.of());
    }

    public IoInputMonitorUdpTriggerTransport(
            Logger log,
            UdpTriggerConfig udpConfig,
            IoInputDiscreteConfig ioInputConfig,
            InspectionTriggerBus bus,
            Runnable onLineWorkChanged,
            List<BucketGroup> bucketGroups
    ) {
        this(log, udpConfig, ioInputConfig, bus, onLineWorkChanged, bucketGroups, null);
    }

    public IoInputMonitorUdpTriggerTransport(
            Logger log,
            UdpTriggerConfig udpConfig,
            IoInputDiscreteConfig ioInputConfig,
            InspectionTriggerBus bus,
            Runnable onLineWorkChanged,
            List<BucketGroup> bucketGroups,
            ManualLineDirectionService manualLineDirection
    ) {
        this(
                log,
                udpConfig,
                ioInputConfig,
                TwoPhaseTriggerConfig.defaults(),
                bus,
                onLineWorkChanged,
                bucketGroups,
                manualLineDirection
        );
    }

    public IoInputMonitorUdpTriggerTransport(
            Logger log,
            UdpTriggerConfig udpConfig,
            IoInputDiscreteConfig ioInputConfig,
            TwoPhaseTriggerConfig twoPhaseConfig,
            InspectionTriggerBus bus,
            Runnable onLineWorkChanged,
            List<BucketGroup> bucketGroups,
            ManualLineDirectionService manualLineDirection
    ) {
        this.log = log;
        this.udpConfig = udpConfig;
        this.ioInputConfig = ioInputConfig;
        this.twoPhaseConfig = twoPhaseConfig == null ? TwoPhaseTriggerConfig.defaults() : twoPhaseConfig;
        this.twoPhaseCorrelator = new TwoPhaseTriggerCorrelator(this.twoPhaseConfig);
        this.syntheticCaptureClient = this.twoPhaseConfig.enabled()
                && this.twoPhaseConfig.autoSecondPhaseDelayMs() > 0
                ? new IoInputMonitorSyntheticCaptureClient(
                        log,
                        this.twoPhaseConfig.ioControlHttpHost(),
                        this.twoPhaseConfig.ioControlHttpPort())
                : null;
        this.bus = bus;
        this.onLineWorkChanged = onLineWorkChanged == null ? () -> { } : onLineWorkChanged;
        this.bucketGroups = bucketGroups == null ? List.of() : List.copyOf(bucketGroups);
        this.manualLineDirection = manualLineDirection;
        this.evaluator = new LineDiscreteTriggerEvaluator(ioInputConfig.triggerEdge());
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
        this.autoSecondPhaseExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "io-input-auto-phase1");
            t.setDaemon(true);
            return t;
        });
        this.directionWaiter = new IoInputDirectionWaiter(
                log,
                directionWaitExecutor,
                ioInputConfig.directionWaitMs(),
                ioInputConfig.directionPollMs(),
                () -> directionActive,
                () -> !ioInputConfig.requireWork() || isEffectiveWork(),
                () -> tryCommitLineCapture("direction wait", false),
                this::logDirectionWaitTimeout
        );
        if (ioInputConfig.stubWorkActive()) {
            workActive = true;
            lineWorkActive.set(true);
            if (ioInputConfig.directionLatchOnWork()) {
                workSessionDirection.onWorkStarted(directionActive, directionRawActive, triggerActive, log);
                workSessionDirection.onDirectionChange(
                        directionActive,
                        directionRawActive,
                        true,
                        triggerActive,
                        log
                );
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
        if (!udpConfig.enabled() || !running.compareAndSet(false, true)) {
            return;
        }
        if (ioInputConfig.directionLatchOnWork()) {
            if (usesAutoDirection()) {
                log.info(
                        "io_input_trigger autonomous: direction_latch_on_work + require_work=false — DI2 idle latch, instant DI3 capture (DI1 не требуется)"
                );
            } else {
                log.info(
                        "io_input_trigger direction_latch_on_work: await DI1↑ (заведение) to latch DI2, then DI3 capture"
                );
            }
        }
        listenerThread = new Thread(this::listenLoop, "io-input-trigger");
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    private void listenLoop() {
        try {
            InetAddress bindAddress = InetAddress.getByName(udpConfig.bindHost());
            socket = new DatagramSocket(new InetSocketAddress(bindAddress, udpConfig.bindPort()));
            socket.setReuseAddress(true);
            log.info(
                    "io_input_trigger listening {}:{} payload_format={} di={}/{}/{} shutdown_di={} trigger_edge={} require_direction={} require_work={} di3_only={} arm_on_direction={} direction_latch={} direction_latch_on_work={} direction_arm_next_di3={} direction_invert={} direction_wait_ms={} direction_poll_ms={} capture_delay_ms={} debounce_ms={} stub_work={} two_phase={} expected_delay_ms={} tolerance_ms={} auto_second_phase_delay_ms={} fallback_physical_grace_ms={} single_di3_burst={}",
                    udpConfig.bindHost(),
                    udpConfig.bindPort(),
                    ioInputConfig.payloadFormat(),
                    ioInputConfig.workPort(),
                    ioInputConfig.directionPort(),
                    ioInputConfig.formatTriggerPorts(),
                    ioInputConfig.shutdownPort(),
                    ioInputConfig.triggerEdge(),
                    ioInputConfig.requireDirection(),
                    ioInputConfig.requireWork(),
                    ioInputConfig.di3Only(),
                    ioInputConfig.armOnDirection(),
                    ioInputConfig.directionLatch(),
                    ioInputConfig.directionLatchOnWork(),
                    ioInputConfig.directionArmNextDi3(),
                    ioInputConfig.directionInvert(),
                    ioInputConfig.directionWaitMs(),
                    ioInputConfig.directionPollMs(),
                    ioInputConfig.captureDelayMs(),
                    ioInputConfig.debounceMs(),
                    ioInputConfig.stubWorkActive(),
                    twoPhaseConfig.enabled(),
                    twoPhaseConfig.expectedDelayMs(),
                    twoPhaseConfig.toleranceMs(),
                    twoPhaseConfig.autoSecondPhaseDelayMs(),
                    twoPhaseConfig.fallbackPhysicalGraceMs(),
                    twoPhaseConfig.singleDi3Burst()
            );
            byte[] buffer = new byte[2048];
            while (running.get() && !socket.isClosed()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                handlePacket(packet);
            }
        } catch (Exception e) {
            if (running.get()) {
                log.error("io_input_trigger listener failed: {}", e.getMessage(), e);
            }
        } finally {
            closeSocket();
        }
    }

    private void handlePacket(DatagramPacket packet) {
        InetSocketAddress remote = new InetSocketAddress(packet.getAddress(), packet.getPort());
        if (!isRemoteAllowed(remote.getAddress().getHostAddress())) {
            log.debug("io_input_trigger ignored sender {}", remote);
            return;
        }
        IoInputDiChangeParser.parse(packet.getData(), packet.getLength(), ioInputConfig.payloadFormat())
                .ifPresent(this::applyDiChange);
    }

    private boolean isRemoteAllowed(String host) {
        List<String> allowed = udpConfig.allowedRemoteHosts();
        if (allowed == null || allowed.isEmpty()) {
            return true;
        }
        return allowed.contains(host);
    }

    /**
     * Подписка на сырые DI-события (до логики съёмки). Ошибки слушателя не ломают capture.
     */
    public void addDiChangeListener(Consumer<IoInputDiChange> listener) {
        if (listener != null) {
            diChangeListeners.add(listener);
        }
    }

    public void setDi2CaptureWindow(Di2CaptureWindow di2CaptureWindow) {
        this.di2CaptureWindow = di2CaptureWindow;
    }

    public Di2CaptureWindow di2CaptureWindow() {
        return di2CaptureWindow;
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

    private void applyDiChange(IoInputDiChange change) {
        notifyDiChangeListeners(change);
        int port = change.diPort();
        boolean active = change.active();
        if (port == ioInputConfig.workPort()) {
            boolean previousWork = workActive;
            workActive = active;
            updateLineWork(active);
            // Как IoInputMonitor disarm_on_work_low: DI1↓ снимает direction latch.
            // Иначе Java ждёт wait_frame без DO5 → timeout / чужие кадры с Line0-шума.
            if (!active && previousWork && ioInputConfig.directionLatch() && directionLatched) {
                directionLatched = false;
                directionActive = false;
                log.info("io_input_trigger direction unlatched (DI{}↓) — жди DI2=1 снова", port);
            }
            if (ioInputConfig.directionLatchOnWork()) {
                if (active && !previousWork) {
                    workSessionDirection.onWorkStarted(directionActive, directionRawActive, triggerActive, log);
                    workSessionDirection.onDirectionChange(
                            directionActive,
                            directionRawActive,
                            true,
                            triggerActive,
                            log
                    );
                } else if (!active && previousWork) {
                    workSessionDirection.onWorkStopped(log);
                }
            } else if (!ioInputConfig.di3Only() && ioInputConfig.requireDirection()) {
                evaluateTriggerDecision(port, active);
            }
            return;
        }
        if (port == ioInputConfig.directionPort()) {
            boolean previousRaw = directionRawActive;
            if (usesAutoDirection()) {
                directionRawActive = active;
                if (directionAutoCapture.isDirectionArmed()) {
                    return;
                }
                directionActive = mapDirection(active);
                if (active) {
                    directionAutoCapture.tryArmOnDi2(active, ioInputConfig.directionInvert());
                }
                if (directionAutoCapture.isDirectionArmed()) {
                    log.info(
                            "io_input_trigger phase1 done: DI2=1 latched — phase2: DI3 + capture_delay_ms={}",
                            ioInputConfig.captureDelayMs()
                    );
                } else {
                    log.info("io_input_trigger phase1: listening DI2 only, await DI2=1 (DI3 ignored)");
                }
                return;
            }
            if (ioInputConfig.directionLatchOnWork()) {
                if (!directionInitialized) {
                    directionInitialized = true;
                    directionRawActive = active;
                    directionActive = mapDirection(active);
                    log.info(
                            "io_input_trigger direction initial raw={} forward={}",
                            active ? 1 : 0,
                            directionActive ? 1 : 0
                    );
                    workSessionDirection.onDirectionChange(directionActive, active, workActive, triggerActive, log);
                    return;
                }
                if (previousRaw != active) {
                    log.info(
                            "io_input_trigger direction raw {} -> {} (forward {} -> {})",
                            previousRaw ? 1 : 0,
                            active ? 1 : 0,
                            mapDirection(previousRaw) ? 1 : 0,
                            mapDirection(active) ? 1 : 0
                    );
                    directionRawActive = active;
                    directionActive = mapDirection(active);
                    if (!triggerActive) {
                        workSessionDirection.onDirectionChange(directionActive, active, workActive, false, log);
                    }
                }
                return;
            }
            boolean mapped = mapDirection(active);
            directionRawActive = active;

            // direction_latch: первый DI2=1 фиксирует ход навсегда; дальше DI2 холостой.
            if (ioInputConfig.directionLatch() && directionLatched) {
                if (previousRaw != active) {
                    captureFiredThisDi2Window = false;
                    cancelAutoSecondPhase();
                    twoPhaseCorrelator.resetDirectionWindow();
                    log.info(
                            "io_input_trigger DI2 idle {} -> {} (направление зафиксировано={})",
                            previousRaw ? 1 : 0,
                            active ? 1 : 0,
                            directionActive ? 1 : 0
                    );
                }
                return;
            }

            boolean previousMapped = directionActive;
            if (directionActive != mapped) {
                if (ioInputConfig.directionInvert()) {
                    log.info(
                            "io_input_trigger direction raw {} -> {} (effective {} -> {})",
                            previousRaw ? 1 : 0,
                            active ? 1 : 0,
                            directionActive ? 1 : 0,
                            mapped ? 1 : 0
                    );
                } else {
                    log.info("io_input_trigger direction {} -> {}", directionActive ? 1 : 0, mapped ? 1 : 0);
                }
            }
            directionActive = mapped;
            if (ioInputConfig.armOnDirection()) {
                // DI2↑ открывает окно; DI2↓ закрывает и дропает late wait_frame (только кадры при DI2=1).
                if (mapped && !previousMapped) {
                    captureFiredThisPulse = false;
                    captureFiredThisDi2Window = false;
                    Di2CaptureWindow window = di2CaptureWindow;
                    if (window != null) {
                        if (!window.tryOpen()) {
                            return;
                        }
                    }
                    cancelAutoSecondPhase();
                    twoPhaseCorrelator.resetDirectionWindow();
                    log.info(
                            "io_input_trigger DI2↑ — di2_window open, arm wait_frame×2 (софт на камеры не шлёт, Line0 с железа)"
                    );
                    fireLineCapture();
                } else if (!mapped && previousMapped) {
                    closeCaptureWindowOnDi2Low();
                }
                return;
            }
            if (previousMapped != mapped) {
                if (!mapped) {
                    closeCaptureWindowOnDi2Low();
                } else {
                    captureFiredThisDi2Window = false;
                    cancelAutoSecondPhase();
                    twoPhaseCorrelator.resetDirectionWindow();
                }
            }
            if (ioInputConfig.directionLatch() && mapped) {
                directionLatched = true;
                if (manualLineDirection != null) {
                    manualLineDirection.setDirection(ManualLineDirectionService.Direction.FORWARD);
                }
                log.info(
                        "io_input_trigger direction latched (DI2=1) — дальнейшие смены DI2 холостые для съёмки"
                );
                if (ioInputConfig.di3Only()) {
                    return;
                }
            }
            directionLatch.onDirectionChange(mapped, triggerActive);
            if (ioInputConfig.di3Only()) {
                // DI2 — только направление; съёмка только по DI3↑
                return;
            }
            if (ioInputConfig.requireDirection()) {
                directionWaiter.onDirectionReadyEvent();
                if (triggerActive) {
                    evaluateTriggerDecision(port, active);
                }
            }
            return;
        }
        if (ioInputConfig.isTriggerPort(port)) {
            if (ioInputConfig.armOnDirection()) {
                // Софтовый DI3/DI5 временно не стартует цикл — только железный DI→Line0.
                if (active && ioInputConfig.triggerEdge() == TriggerEdgeMode.RISING) {
                    triggerActive = false;
                    captureFiredThisPulse = false;
                } else {
                    triggerActive = active;
                }
                log.info(
                        "io_input_trigger DI{} ignored (arm_on_direction): wait_frame уже от DI2, value={}",
                        port,
                        active ? 1 : 0
                );
                return;
            }
            if (usesAutoDirection() && !directionAutoCapture.isDirectionArmed()) {
                log.info("io_input_trigger phase1: DI{} ignored until DI2=1 arms direction", port);
                return;
            }
            // Rising-only photoeye: IoInputMonitor шлёт только UDP DI3=1, без DI3=0.
            // Повторный 3:1 при triggerActive=true — новый импульс, не «залипший HIGH».
            boolean risingEdge = active && !triggerActive;
            boolean risingOnlyRetrigger = active
                    && triggerActive
                    && ioInputConfig.triggerEdge() == TriggerEdgeMode.RISING;
            if (risingEdge || risingOnlyRetrigger) {
                if (usesAutoDirection()) {
                    captureFiredThisPulse = false;
                    directionAutoCapture.onDi3Rising(directionRawActive);
                    scheduleCaptureAfterDi3Open();
                } else if (ioInputConfig.directionLatchOnWork()) {
                    log.info(
                            "io_input_trigger DI3 capture edge session_forward={} session_raw={} known={}",
                            workSessionDirection.sessionDirectionActive() ? 1 : 0,
                            workSessionDirection.sessionDirectionKnown()
                                    ? (workSessionDirection.sessionDirectionRaw() ? 1 : 0)
                                    : -1,
                            workSessionDirection.sessionDirectionKnown() ? 1 : 0
                    );
                    tryInstantCaptureWithWorkSession();
                } else if (ioInputConfig.di3Only()) {
                    captureFiredThisPulse = false;
                    di3RiseEpochMs = System.currentTimeMillis();
                    // Строго DI2=1: поздний DI5 после DI2↓ не армит wait (иначе чужие Line0).
                    boolean directionOk = !ioInputConfig.requireDirection()
                            || (ioInputConfig.directionLatch() ? directionLatched : directionActive);
                    if (!directionOk) {
                        log.info(
                                "io_input_trigger skip DI{}↑: направление ещё не зафиксировано (жди DI2=1), source={}",
                                port,
                                directionSourceLabel()
                        );
                    } else if (!twoPhaseConfig.enabled() && directionActive && captureFiredThisDi2Window) {
                        log.info(
                                "io_input_trigger skip DI{}↑: холостой (уже сняли при DI2=1), source={}",
                                port,
                                directionSourceLabel()
                        );
                    } else {
                        log.info(
                                "io_input_trigger DI{}↑ capture — direction={} latched={} source={}",
                                port,
                                effectiveDirectionWire(),
                                directionLatched ? 1 : 0,
                                directionSourceLabel()
                        );
                        fireLineCapture();
                    }
                } else {
                    log.info("io_input_trigger DI3 capture edge direction={}", directionActive ? 1 : 0);
                }
            } else if (!active && triggerActive) {
                if (usesAutoDirection()) {
                    cancelDelayedCapture();
                    if (!captureFiredThisPulse) {
                        log.info("io_input_trigger DI3 pulse end — capture missed (delay {} ms?)",
                                ioInputConfig.captureDelayMs());
                    } else {
                        log.info("io_input_trigger DI3 pulse end — capture done");
                    }
                } else if (ioInputConfig.directionLatchOnWork()) {
                    log.info(
                            "io_input_trigger DI3 release session_forward={}",
                            workSessionDirection.sessionDirectionActive() ? 1 : 0
                    );
                    if (workActive && !workSessionDirection.sessionDirectionKnown()) {
                        workSessionDirection.onDirectionChange(
                                directionActive,
                                directionRawActive,
                                true,
                                false,
                                log
                        );
                    }
                } else if (ioInputConfig.di3Only()) {
                    cancelDelayedCapture();
                    if (!captureFiredThisPulse) {
                        log.info("io_input_trigger DI3 pulse end — capture missed (delay {} ms?)",
                                ioInputConfig.captureDelayMs());
                    }
                } else {
                    log.info("io_input_trigger DI3 release direction={}", directionActive ? 1 : 0);
                }
                captureFiredThisPulse = false;
                if (directionWaiter.isWaiting()) {
                    directionWaiter.cancel("DI3 released before direction");
                }
            }
            // Rising-only: не держим triggerActive=true — иначе следующий UDP 3:1 молча игнорируется.
            // captureFiredThisDi2Window НЕ сбрасываем — повторный DI3 при DI2=1 остаётся холостым.
            if (active && ioInputConfig.triggerEdge() == TriggerEdgeMode.RISING) {
                triggerActive = false;
                captureFiredThisPulse = false;
            } else {
                triggerActive = active;
            }
            if (ioInputConfig.directionLatchOnWork()) {
                if (active && workActive && !workSessionDirection.sessionDirectionKnown()) {
                    workSessionDirection.onDirectionChange(
                            directionActive,
                            directionRawActive,
                            true,
                            false,
                            log
                    );
                }
            } else if (ioInputConfig.triggerEdge() == TriggerEdgeMode.RISING && active) {
                handleDi3RisingCapture();
            } else if (ioInputConfig.triggerEdge() == TriggerEdgeMode.FALLING && !active) {
                evaluateTriggerDecision(port, active);
                directionLatch.onTriggerRelease();
            }
            return;
        }
        if (ioInputConfig.shutdownPort() >= 1 && port == ioInputConfig.shutdownPort()) {
            log.info(
                    "io_input_trigger DI{}={} (shutdown port — handled by DiShutdownController)",
                    port,
                    active ? 1 : 0
            );
            return;
        }
        if (port > 0) {
            log.debug("io_input_trigger ignored di={} value={}", port, active ? 1 : 0);
        } else {
            log.debug("io_input_trigger legacy payload without di port ignored");
        }
    }

    /**
     * Latch-режим (DI2=1 вооружает навсегда) отключён: при {@code require_direction}
     * проверяем уровень DI2 на фронте DI3.
     */
    private boolean usesAutoDirection() {
        return false;
    }

    private void handleDi3RisingCapture() {
        if (usesAutoDirection() || ioInputConfig.di3Only()) {
            return;
        }
        if (directionActive || !ioInputConfig.requireDirection()) {
            tryCommitLineCapture("DI3 edge", false);
        } else {
            handleMissingDirection();
        }
    }

    private void evaluateTriggerDecision(int changedPort, boolean changedActive) {
        if (ioInputConfig.directionLatchOnWork()) {
            boolean effectiveWork = isEffectiveWork();
            if (!workSessionDirection.allowsCapture(
                    ioInputConfig.requireWork(),
                    effectiveWork,
                    ioInputConfig.requireDirection()
            )) {
                if (ioInputConfig.requireWork() && !effectiveWork) {
                    log.info("io_input_trigger skip: conveyor not running (work=0)");
                } else if (!workSessionDirection.sessionDirectionKnown()) {
                    log.info("io_input_trigger skip: session direction not latched yet (await DI2 after DI1)");
                } else {
                    log.info("io_input_trigger skip: session direction=0 (latched at work start)");
                }
                return;
            }
            LineDiscreteTriggerEvaluator.Decision sessionDecision = evaluator.evaluate(
                    true,
                    true,
                    triggerActive,
                    false,
                    false
            );
            if (sessionDecision == LineDiscreteTriggerEvaluator.Decision.FIRE) {
                log.info(
                        "io_input_trigger capture on DI3 edge session_direction={}",
                        workSessionDirection.sessionDirectionActive() ? 1 : 0
                );
                publishDebounced();
            }
            return;
        }

        boolean effectiveWork = isEffectiveWork();
        boolean effectiveDirection = ioInputConfig.requireDirection() ? directionActive : true;
        LineDiscreteTriggerEvaluator.Decision decision = evaluator.evaluate(
                effectiveWork,
                effectiveDirection,
                triggerActive,
                ioInputConfig.requireDirection(),
                ioInputConfig.requireWork()
        );
        switch (decision) {
            case NONE -> { }
            case SKIP_NOT_READY -> log.info("io_input_trigger skip: conveyor not running (work=0)");
            case SKIP_WRONG_DIRECTION -> handleMissingDirection();
            case FIRE -> {
                log.info("io_input_trigger capture on DI3 edge direction={}", directionActive ? 1 : 0);
                publishDebounced();
            }
        }
    }

    private void handleMissingDirection() {
        if (ioInputConfig.requireDirection() && ioInputConfig.directionWaitMs() > 0) {
            directionWaiter.begin("DI3 edge, polling DI2");
            return;
        }
        log.info(
                "io_input_trigger skip: DI2=0 at DI3 edge — await DI2=1 during pulse (up to {} ms)",
                ioInputConfig.directionWaitMs() > 0 ? ioInputConfig.directionWaitMs() : "pulse end"
        );
    }

    private void tryInstantCaptureWithWorkSession() {
        if (captureFiredThisPulse) {
            return;
        }
        if (!allowsCaptureForSelectedDirection()) {
            return;
        }
        if (!workSessionDirection.allowsCapture(
                ioInputConfig.requireWork(),
                isEffectiveWork(),
                // при UI-направлении сессия не режет reverse: фильтр уже в allowsCaptureForSelectedDirection
                ioInputConfig.requireDirection() && manualLineDirection == null
        )) {
            if (ioInputConfig.requireWork() && !isEffectiveWork()) {
                log.info("io_input_trigger skip: conveyor not running (work=0)");
            } else if (!workSessionDirection.sessionDirectionKnown()) {
                log.info("io_input_trigger skip: no session direction — DI1↑ (заведение) required after restart/stop");
            } else {
                log.info(
                        "io_input_trigger skip: reverse session latched at work start (raw={})",
                        workSessionDirection.sessionDirectionRaw() ? 1 : 0
                );
            }
            return;
        }
        if (ioInputConfig.debounceMs() > 0) {
            long now = System.currentTimeMillis();
            if (now - lastFireMs < ioInputConfig.debounceMs()) {
                log.debug("io_input_trigger debounced");
                return;
            }
            lastFireMs = now;
        }
        long triggerReceivedMs = System.currentTimeMillis();
        List<Integer> targetCameras = resolveTargetCameras(true);
        int published = publishLineCapture(targetCameras);
        if (published > 0) {
            captureFiredThisPulse = true;
            long dispatchMs = System.currentTimeMillis() - triggerReceivedMs;
            log.info(
                    "io_input_trigger instant capture session_forward={} session_raw={} cameras={} target={} dispatch_ms={}",
                    workSessionDirection.sessionDirectionActive() ? 1 : 0,
                    workSessionDirection.sessionDirectionRaw() ? 1 : 0,
                    published,
                    formatCameraTarget(targetCameras),
                    dispatchMs
            );
        }
    }

    private void scheduleCaptureAfterDi3Open() {
        cancelDelayedCapture();
        di3RiseEpochMs = System.currentTimeMillis();
        int delayMs = ioInputConfig.captureDelayMs();
        if (delayMs <= 0) {
            log.info("io_input_trigger DI3 pulse open — immediate capture");
            fireLineCapture();
            return;
        }
        log.info("io_input_trigger DI3 pulse open — capture scheduled in {} ms", delayMs);
        delayedCaptureTask = captureDelayExecutor.schedule(
                () -> {
                    if (!triggerActive || captureFiredThisPulse) {
                        return;
                    }
                    fireLineCapture();
                },
                delayMs,
                TimeUnit.MILLISECONDS
        );
    }

    private void cancelDelayedCapture() {
        ScheduledFuture<?> task = delayedCaptureTask;
        if (task != null) {
            task.cancel(false);
            delayedCaptureTask = null;
        }
    }

    private void fireLineCapture() {
        if (captureFiredThisPulse) {
            return;
        }
        // Импульс и wait_frame только при DI2=1.
        if (ioInputConfig.requireDirection() && !allowsCaptureForSelectedDirection()) {
            log.info("io_input_trigger skip fire — DI2=0 (нужен импульс при DI2=1)");
            return;
        }
        Di2CaptureWindow window = di2CaptureWindow;
        if (ioInputConfig.armOnDirection()) {
            if (window != null && !window.isOpen()) {
                log.info("io_input_trigger skip fire — di2_window closed (wait next DI2↑)");
                return;
            }
        } else if (window != null && !window.isOpen()) {
            if (window.tryOpen()) {
                log.info(
                        "io_input_trigger capture_window open on DI3/DI5 — accept frames only while DI2=1 "
                                + "(target {})",
                        window.targetFrames()
                );
            }
        }
        if (!twoPhaseConfig.enabled() && directionActive && captureFiredThisDi2Window) {
            log.info("io_input_trigger skip: холостой DI3 (уже сняли при DI2=1)");
            return;
        }
        if (ioInputConfig.requireWork() && !isEffectiveWork()) {
            log.info("io_input_trigger skip: conveyor not running (work=0)");
            return;
        }
        if (ioInputConfig.requireDirection() && usesAutoDirection() && !directionAutoCapture.isDirectionArmed()) {
            log.info("io_input_trigger skip: await DI2=1 before capture (direction not armed)");
            return;
        }
        if (ioInputConfig.debounceMs() > 0) {
            long now = System.currentTimeMillis();
            if (now - lastFireMs < ioInputConfig.debounceMs()) {
                log.debug("io_input_trigger debounced");
                return;
            }
            lastFireMs = now;
        }
        long triggerReceivedMs = System.currentTimeMillis();
        List<Integer> targetCameras = resolveTargetCameras(false);
        int published = publishLineCapture(targetCameras);
        if (published > 0) {
            captureFiredThisPulse = true;
            if (directionActive && !twoPhaseConfig.enabled()) {
                captureFiredThisDi2Window = true;
            }
            long dispatchMs = System.currentTimeMillis() - triggerReceivedMs;
            log.info(
                    "io_input_trigger capture direction={} source={} cameras={} target={} dispatch_ms={} hardware={} arm_on_direction={}",
                    effectiveDirectionWire(),
                    directionSourceLabel(),
                    published,
                    formatCameraTarget(targetCameras),
                    dispatchMs,
                    ioInputConfig.externalHardwareCapture(),
                    ioInputConfig.armOnDirection()
            );
        }
    }

    private int publishLineCapture(List<Integer> targetCameras) {
        return publishLineCapture(targetCameras, Instant.now());
    }

    int publishLineCapture(List<Integer> targetCameras, Instant receivedAt) {
        Di2CaptureWindow window = di2CaptureWindow;
        if (window != null && !window.isOpen()) {
            log.info("io_input_trigger discard dispatch — capture_window closed (нужен DI2=1 + DI3/DI5)");
            return 0;
        }
        if (ioInputConfig.requireDirection() && !directionActive && !ioInputConfig.directionLatch()) {
            log.info("io_input_trigger discard dispatch — DI2=0");
            return 0;
        }
        long rawSequence = ioInputConfig.externalHardwareCapture()
                ? bus.reserveLineBroadcastSequence("io_input")
                : bus.prefireLineBroadcast("io_input", targetCameras);
        TwoPhaseTriggerCorrelator.PhaseAssignment phase =
                twoPhaseCorrelator.correlate(rawSequence, receivedAt);
        if (phase == null) {
            log.info(
                    "io_input_trigger discard DI3 raw_sequence={}: {} in current DI2=1 window",
                    rawSequence,
                    twoPhaseConfig.singleDi3Burst()
                            ? "burst already started (one DI3 per window)"
                            : "already accepted two pulses"
            );
            return 0;
        }
        int published = bus.dispatchLineBroadcast("io_input", rawSequence, receivedAt, targetCameras, phase);
        if (published > 0) {
            onTwoPhaseDispatched(phase);
        }
        return published;
    }

    /** DI2↓: grace на доставку кадра с Line0 (импульс был при DI2=1); потом cancel. */
    private void closeCaptureWindowOnDi2Low() {
        captureFiredThisDi2Window = false;
        cancelAutoSecondPhase();
        twoPhaseCorrelator.resetDirectionWindow();
        Di2CaptureWindow window = di2CaptureWindow;
        if (window != null && window.isOpen()) {
            // ~400 ms: GigE transfer; reverse photoeye обычно +700–1000 ms после DI2↓.
            window.scheduleCloseAfterDi2Low(400L);
            log.info(
                    "io_input_trigger DI2↓ — capture_window grace 400ms "
                            + "(принять кадры от DI3/DI5, затем закрыть; новые импульсы при DI2=0 skip)"
            );
        } else {
            log.info("io_input_trigger DI2↓ — no open capture_window");
        }
    }

    public boolean isDirectionActive() {
        return directionActive;
    }

    private void onTwoPhaseDispatched(TwoPhaseTriggerCorrelator.PhaseAssignment phase) {
        if (!twoPhaseConfig.enabled()) {
            return;
        }
        if (phase.phaseId() == 1) {
            cancelAutoSecondPhase();
            return;
        }
        if (phase.phaseId() != 0) {
            return;
        }
        // arm_on_direction: сразу ставим phase1 wait (параллельно phase0); 2-й Line0 с железа.
        long delayMs = twoPhaseConfig.autoSecondPhaseDelayMs();
        if (ioInputConfig.armOnDirection()) {
            delayMs = 1L;
        }
        if (delayMs <= 0) {
            return;
        }
        scheduleAutoSecondPhase(phase.parentCycleId(), delayMs);
    }

    private void scheduleAutoSecondPhase(long parentCycleId) {
        long delayMs = twoPhaseConfig.autoSecondPhaseDelayMs();
        if (delayMs <= 0) {
            return;
        }
        scheduleAutoSecondPhase(parentCycleId, delayMs);
    }

    private void scheduleAutoSecondPhase(long parentCycleId, long delayMs) {
        cancelAutoSecondPhase();
        autoSecondPhaseParentCycle = parentCycleId;
        autoSecondPhasePhase0EpochMs = System.currentTimeMillis();
        String mode = ioInputConfig.armOnDirection()
                ? "arm_on_direction: software wait_frame for 2nd Line0 (no soft DI3)"
                : twoPhaseConfig.singleDi3Burst()
                        ? (ioInputConfig.externalHardwareCapture()
                                ? "burst phase1: line0-pulse + dispatch"
                                : "burst phase1: software dispatch")
                        : (ioInputConfig.externalHardwareCapture()
                                ? "fallback: synthetic DI3+DO5 if no 2nd physical DI3"
                                : "fallback: software wait_frame if no 2nd DI3");
        log.info(
                "io_input_trigger phase1 scheduled parent_cycle={} delay_ms={} grace_ms={} ({})",
                parentCycleId,
                delayMs,
                twoPhaseConfig.fallbackPhysicalGraceMs(),
                mode
        );
        autoSecondPhaseTask = autoSecondPhaseExecutor.schedule(
                () -> fireFallbackSecondPhase(parentCycleId),
                delayMs,
                TimeUnit.MILLISECONDS
        );
    }

    private void fireFallbackSecondPhase(long parentCycleId) {
        if (autoSecondPhaseParentCycle != parentCycleId) {
            return;
        }
        long elapsedMs = System.currentTimeMillis() - autoSecondPhasePhase0EpochMs;
        if (twoPhaseConfig.singleDi3Burst()) {
            publishBurstPhase1(parentCycleId, elapsedMs);
            return;
        }
        if (twoPhaseCorrelator.acceptedPulseCount() >= 2) {
            log.info(
                    "io_input_trigger fallback phase1 skipped parent_cycle={}: physical DI3 phase1 already received",
                    parentCycleId
            );
            return;
        }
        int graceMs = twoPhaseConfig.fallbackPhysicalGraceMs();
        if (graceMs > 0 && elapsedMs < graceMs) {
            log.info(
                    "io_input_trigger fallback phase1 skipped parent_cycle={}: elapsed_ms={} < grace_ms={}",
                    parentCycleId,
                    elapsedMs,
                    graceMs
            );
            return;
        }
        if (ioInputConfig.externalHardwareCapture() && !ioInputConfig.armOnDirection()) {
            log.info(
                    "io_input_trigger fallback phase1 synthetic DO5 parent_cycle={} elapsed_ms={} (no second physical DI3)",
                    parentCycleId,
                    elapsedMs
            );
            if (syntheticCaptureClient == null
                    || !syntheticCaptureClient.triggerSyntheticDi3Capture()) {
                log.warn(
                        "io_input_trigger fallback phase1 synthetic DO5 failed parent_cycle={} — phase1 not dispatched",
                        parentCycleId
                );
            }
            return;
        }
        log.info(
                "io_input_trigger fallback phase1 wait_frame parent_cycle={} elapsed_ms={} ({})",
                parentCycleId,
                elapsedMs,
                ioInputConfig.armOnDirection()
                        ? "arm_on_direction: 2nd Line0 from hardware"
                        : "no second physical DI3"
        );
        int published = publishLineCapture(null, Instant.now());
        if (published <= 0) {
            log.warn(
                    "io_input_trigger fallback phase1 produced no dispatch parent_cycle={}",
                    parentCycleId
            );
        }
    }

    private void publishBurstPhase1(long parentCycleId, long elapsedMs) {
        Instant receivedAt = Instant.now();
        long rawSequence = ioInputConfig.externalHardwareCapture()
                ? bus.reserveLineBroadcastSequence("io_input_burst")
                : bus.prefireLineBroadcast("io_input_burst", null);
        TwoPhaseTriggerCorrelator.PhaseAssignment phase =
                twoPhaseCorrelator.assignBurstPhase1(rawSequence, receivedAt);
        if (phase == null) {
            log.warn(
                    "io_input_trigger burst phase1 skipped parent_cycle={} raw_seq={} (correlator rejected)",
                    parentCycleId,
                    rawSequence
            );
            return;
        }
        log.info(
                "io_input_trigger burst phase1 dispatch parent_cycle={} raw_seq={} elapsed_ms={}",
                parentCycleId,
                rawSequence,
                elapsedMs
        );
        int published = bus.dispatchLineBroadcast("io_input_burst", rawSequence, receivedAt, null, phase);
        if (published <= 0) {
            log.warn("io_input_trigger burst phase1 produced no dispatch parent_cycle={}", parentCycleId);
            return;
        }
        if (ioInputConfig.externalHardwareCapture()) {
            if (syntheticCaptureClient == null || !syntheticCaptureClient.triggerLine0Pulse()) {
                log.warn(
                        "io_input_trigger burst phase1 line0-pulse failed parent_cycle={} — cameras may miss frame",
                        parentCycleId
                );
            }
        }
    }

    private void cancelAutoSecondPhase() {
        ScheduledFuture<?> task = autoSecondPhaseTask;
        autoSecondPhaseTask = null;
        autoSecondPhaseParentCycle = -1L;
        if (task != null) {
            task.cancel(false);
        }
    }

    /**
     * UI «Прямой/Обратный» не фильтрует. При {@code require_direction} — DI2=1
     * (или уже latched после первого DI2=1).
     */
    private boolean allowsCaptureForSelectedDirection() {
        if (!ioInputConfig.requireDirection()) {
            return true;
        }
        if (ioInputConfig.directionLatch() ? directionLatched : directionActive) {
            return true;
        }
        log.info("io_input_trigger skip: DI2=0 (need direction before DI3 capture)");
        return false;
    }

    private String effectiveDirectionWire() {
        if (ioInputConfig.directionLatch() && directionLatched) {
            return "forward";
        }
        return directionActive ? "forward" : "reverse";
    }

    private String directionSourceLabel() {
        return "di2";
    }

    /** Всегда все камеры; фильтр — только направление хода (UI ↔ DI2), не группа. */
    private List<Integer> resolveTargetCameras(boolean sessionMode) {
        return null;
    }

    private static String formatCameraTarget(List<Integer> targetCameras) {
        if (targetCameras == null || targetCameras.isEmpty()) {
            return "all";
        }
        return targetCameras.toString();
    }

    private static String cycleLabel(IoInputDirectionAutoCapture.CycleDirection direction) {
        return switch (direction) {
            case FORWARD -> "forward";
            case UNKNOWN -> "unknown";
        };
    }

    private void tryCommitLineCapture(String source, boolean ignoreDirectionCheck) {
        if (captureFiredThisPulse) {
            return;
        }
        if (ioInputConfig.triggerEdge() == TriggerEdgeMode.RISING && !triggerActive) {
            return;
        }
        if (ioInputConfig.requireWork() && !isEffectiveWork()) {
            log.info("io_input_trigger skip: conveyor not running (work=0)");
            return;
        }
        if (!ignoreDirectionCheck && !allowsCaptureForSelectedDirection()) {
            return;
        }
        if (ioInputConfig.debounceMs() > 0) {
            long now = System.currentTimeMillis();
            if (now - lastFireMs < ioInputConfig.debounceMs()) {
                log.debug("io_input_trigger debounced");
                return;
            }
            lastFireMs = now;
        }
        long triggerReceivedMs = System.currentTimeMillis();
        int published = twoPhaseConfig.enabled()
                ? publishLineCapture(null)
                : bus.publishBroadcast(InspectionTriggerEvent.lineBroadcast("io_input"));
        if (published > 0) {
            captureFiredThisPulse = true;
            long dispatchMs = System.currentTimeMillis() - triggerReceivedMs;
            log.info(
                    "io_input_trigger capture direction={} cameras={} ({}) dispatch_ms={}",
                    directionActive ? 1 : 0,
                    published,
                    source,
                    dispatchMs
            );
        }
    }

    private void logDirectionWaitTimeout() {
        log.info(
                "io_input_trigger skip: direction timeout after {} ms (current DI2={})",
                ioInputConfig.directionWaitMs(),
                directionActive ? 1 : 0
        );
    }

    private boolean isEffectiveWork() {
        return ioInputConfig.stubWorkActive() || workActive;
    }

    private boolean mapDirection(boolean rawDiActive) {
        return ioInputConfig.directionInvert() ? !rawDiActive : rawDiActive;
    }

    private boolean directionRawActive() {
        return directionRawActive;
    }

    private void updateLineWork(boolean work) {
        boolean previous = lineWorkActive.getAndSet(work);
        if (previous != work) {
            log.info("io_input_trigger line work {} -> {}", previous ? 1 : 0, work ? 1 : 0);
            onLineWorkChanged.run();
        }
    }

    private void publishDebounced() {
        long triggerReceivedMs = System.currentTimeMillis();
        if (ioInputConfig.debounceMs() > 0) {
            long now = System.currentTimeMillis();
            if (now - lastFireMs < ioInputConfig.debounceMs()) {
                log.debug("io_input_trigger debounced");
                return;
            }
            lastFireMs = now;
        }
        int published = twoPhaseConfig.enabled()
                ? publishLineCapture(null)
                : bus.publishBroadcast(InspectionTriggerEvent.lineBroadcast("io_input"));
        if (published > 0) {
            long dispatchMs = System.currentTimeMillis() - triggerReceivedMs;
            log.info("io_input_trigger line broadcast cameras={} dispatch_ms={}", published, dispatchMs);
        }
    }

    @Override
    public void close() {
        running.set(false);
        cancelDelayedCapture();
        cancelAutoSecondPhase();
        directionWaiter.close();
        captureDelayExecutor.shutdown();
        autoSecondPhaseExecutor.shutdown();
        directionWaitExecutor.shutdown();
        Di2CaptureWindow window = di2CaptureWindow;
        if (window != null) {
            window.close();
        }
        try {
            if (!captureDelayExecutor.awaitTermination(500L, TimeUnit.MILLISECONDS)) {
                captureDelayExecutor.shutdownNow();
            }
            if (!autoSecondPhaseExecutor.awaitTermination(500L, TimeUnit.MILLISECONDS)) {
                autoSecondPhaseExecutor.shutdownNow();
            }
            if (!directionWaitExecutor.awaitTermination(500L, TimeUnit.MILLISECONDS)) {
                directionWaitExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            captureDelayExecutor.shutdownNow();
            autoSecondPhaseExecutor.shutdownNow();
            directionWaitExecutor.shutdownNow();
        }
        closeSocket();
        if (listenerThread != null) {
            listenerThread.interrupt();
            try {
                listenerThread.join(1500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("io_input_trigger stopped");
    }

    private void closeSocket() {
        DatagramSocket s = socket;
        if (s != null && !s.isClosed()) {
            s.close();
        }
    }
}
