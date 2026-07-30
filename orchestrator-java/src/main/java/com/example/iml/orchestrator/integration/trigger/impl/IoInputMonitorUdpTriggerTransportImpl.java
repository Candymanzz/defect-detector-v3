package com.example.iml.orchestrator.integration.trigger.impl;

import com.example.iml.orchestrator.integration.trigger.api.TriggerTransport;

import com.example.iml.orchestrator.integration.pipeline.bucket.BucketGroup;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerBus;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerEvent;
import com.example.iml.orchestrator.integration.trigger.ManualLineDirectionService;
import com.example.iml.orchestrator.integration.trigger.config.IoInputDiscreteConfig;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * UDP-слушатель DI: DI2 — текущее направление, DI3 — триггер съёмки.
 */
public final class IoInputMonitorUdpTriggerTransportImpl implements TriggerTransport {

    private final Logger log;
    private final UdpTriggerConfig udpConfig;
    private final IoInputDiscreteConfig ioInputConfig;
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
    private long lastFireMs;
    private Thread listenerThread;
    private DatagramSocket socket;

    public IoInputMonitorUdpTriggerTransportImpl(
            Logger log,
            UdpTriggerConfig udpConfig,
            IoInputDiscreteConfig ioInputConfig,
            InspectionTriggerBus bus,
            Runnable onLineWorkChanged
    ) {
        this(log, udpConfig, ioInputConfig, bus, onLineWorkChanged, List.of());
    }

    public IoInputMonitorUdpTriggerTransportImpl(
            Logger log,
            UdpTriggerConfig udpConfig,
            IoInputDiscreteConfig ioInputConfig,
            InspectionTriggerBus bus,
            Runnable onLineWorkChanged,
            List<BucketGroup> bucketGroups
    ) {
        this(log, udpConfig, ioInputConfig, bus, onLineWorkChanged, bucketGroups, null);
    }

    public IoInputMonitorUdpTriggerTransportImpl(
            Logger log,
            UdpTriggerConfig udpConfig,
            IoInputDiscreteConfig ioInputConfig,
            InspectionTriggerBus bus,
            Runnable onLineWorkChanged,
            List<BucketGroup> bucketGroups,
            ManualLineDirectionService manualLineDirection
    ) {
        this.log = log;
        this.udpConfig = udpConfig;
        this.ioInputConfig = ioInputConfig;
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
                    "io_input_trigger listening {}:{} payload_format={} di={}/{}/{} trigger_edge={} require_direction={} require_work={} di3_only={} direction_latch={} direction_latch_on_work={} direction_arm_next_di3={} direction_invert={} direction_wait_ms={} direction_poll_ms={} capture_delay_ms={} debounce_ms={} stub_work={}",
                    udpConfig.bindHost(),
                    udpConfig.bindPort(),
                    ioInputConfig.payloadFormat(),
                    ioInputConfig.workPort(),
                    ioInputConfig.directionPort(),
                    ioInputConfig.triggerPort(),
                    ioInputConfig.triggerEdge(),
                    ioInputConfig.requireDirection(),
                    ioInputConfig.requireWork(),
                    ioInputConfig.di3Only(),
                    ioInputConfig.directionLatch(),
                    ioInputConfig.directionLatchOnWork(),
                    ioInputConfig.directionArmNextDi3(),
                    ioInputConfig.directionInvert(),
                    ioInputConfig.directionWaitMs(),
                    ioInputConfig.directionPollMs(),
                    ioInputConfig.captureDelayMs(),
                    ioInputConfig.debounceMs(),
                    ioInputConfig.stubWorkActive()
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
            if (previousMapped != mapped) {
                captureFiredThisDi2Window = false;
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
        if (port == ioInputConfig.triggerPort()) {
            if (usesAutoDirection() && !directionAutoCapture.isDirectionArmed()) {
                log.info("io_input_trigger phase1: DI3 ignored until DI2=1 arms direction");
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
                    boolean directionOk = !ioInputConfig.requireDirection()
                            || (ioInputConfig.directionLatch() ? directionLatched : directionActive);
                    if (!directionOk) {
                        log.info(
                                "io_input_trigger skip DI3↑: направление ещё не зафиксировано (жди DI2=1), source={}",
                                directionSourceLabel()
                        );
                    } else if (directionActive && captureFiredThisDi2Window) {
                        log.info(
                                "io_input_trigger skip DI3↑: холостой (уже сняли при DI2=1), source={}",
                                directionSourceLabel()
                        );
                    } else {
                        log.info(
                                "io_input_trigger DI3↑ capture — direction={} latched={} source={}",
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
        if (directionActive && captureFiredThisDi2Window) {
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
        if (!allowsCaptureForSelectedDirection()) {
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
            if (directionActive) {
                captureFiredThisDi2Window = true;
            }
            long dispatchMs = System.currentTimeMillis() - triggerReceivedMs;
            log.info(
                    "io_input_trigger DI3 capture direction={} source={} cameras={} target={} dispatch_ms={} hardware={}",
                    effectiveDirectionWire(),
                    directionSourceLabel(),
                    published,
                    formatCameraTarget(targetCameras),
                    dispatchMs,
                    ioInputConfig.externalHardwareCapture()
            );
        }
    }

    private int publishLineCapture(List<Integer> targetCameras) {
        if (ioInputConfig.externalHardwareCapture()) {
            return bus.dispatchLineBroadcastWithoutPrefire("io_input", targetCameras);
        }
        long seq = bus.prefireLineBroadcast("io_input", targetCameras);
        return bus.dispatchLineBroadcast("io_input", seq, targetCameras);
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
        int published = bus.publishBroadcast(InspectionTriggerEvent.lineBroadcast("io_input"));
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
        int published = bus.publishBroadcast(InspectionTriggerEvent.lineBroadcast("io_input"));
        if (published > 0) {
            long dispatchMs = System.currentTimeMillis() - triggerReceivedMs;
            log.info("io_input_trigger line broadcast cameras={} dispatch_ms={}", published, dispatchMs);
        }
    }

    @Override
    public void close() {
        running.set(false);
        cancelDelayedCapture();
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
