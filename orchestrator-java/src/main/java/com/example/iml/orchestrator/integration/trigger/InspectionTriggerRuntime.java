package com.example.iml.orchestrator.integration.trigger;

import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.pipeline.bucket.BucketGroup;
import com.example.iml.orchestrator.integration.trigger.config.InspectionTriggerConfig;
import com.example.iml.orchestrator.integration.trigger.config.UdpTriggerConfig;
import com.example.iml.orchestrator.integration.trigger.ManualLineDirectionService;
import com.example.iml.orchestrator.integration.trigger.parse.IoInputDiChange;
import com.example.iml.orchestrator.integration.trigger.impl.IoInputMonitorUdpTriggerTransportImpl;
import com.example.iml.orchestrator.integration.trigger.api.TriggerTransport;
import com.example.iml.orchestrator.integration.trigger.impl.UdpTriggerTransportImpl;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Жизненный цикл шины триггеров: IoInputMonitor (UDP) и/или прочие UDP-транспорты.
 */
public final class InspectionTriggerRuntime implements AutoCloseable {

    private final InspectionTriggerBus bus;
    private final List<TriggerTransport> transports;
    private final IoInputMonitorUdpTriggerTransportImpl ioInputTransport;

    private InspectionTriggerRuntime(
            InspectionTriggerBus bus,
            List<TriggerTransport> transports,
            IoInputMonitorUdpTriggerTransportImpl ioInputTransport
    ) {
        this.bus = bus;
        this.transports = transports;
        this.ioInputTransport = ioInputTransport;
    }

    public InspectionTriggerBus bus() {
        return bus;
    }

    /** Конвейер в «Работа» (DI work=1). Если IoInputMonitor выключен — {@code true}. */
    public boolean isLineWorkActive() {
        return ioInputTransport == null || ioInputTransport.isLineWorkActive();
    }

    /**
     * Нужно ли держать {@code vision_ready} только при DI1=1.
     * При {@code require_work: false} съёмка идёт без DI1 — ready тоже не должен ждать DI1.
     */
    public boolean gatesVisionReadyByLineWork() {
        return ioInputTransport != null && ioInputTransport.gatesVisionReadyByLineWork();
    }

    /**
     * Подписка на DI от IoInputMonitor (для interval_flash и т.п.).
     * Не меняет логику съёмки; no-op если io_input транспорт не запущен.
     */
    public void addDiChangeListener(Consumer<IoInputDiChange> listener) {
        if (ioInputTransport != null) {
            ioInputTransport.addDiChangeListener(listener);
        }
    }

    public static InspectionTriggerRuntime start(
            Logger log,
            Map<String, Object> integration,
            Collection<Integer> cameraIds,
            IntegrationFeatureConfig.InspectionTriggerMode mode,
            int captureTriggerStaggerMs
    ) {
        return start(log, integration, cameraIds, mode, captureTriggerStaggerMs, null, null);
    }

    public static InspectionTriggerRuntime start(
            Logger log,
            Map<String, Object> integration,
            Collection<Integer> cameraIds,
            IntegrationFeatureConfig.InspectionTriggerMode mode,
            int captureTriggerStaggerMs,
            Runnable onLineWorkChanged,
            InspectionTriggerRuntime[] holder
    ) {
        return start(log, integration, cameraIds, mode, captureTriggerStaggerMs, onLineWorkChanged, holder, List.of(), null);
    }

    public static InspectionTriggerRuntime start(
            Logger log,
            Map<String, Object> integration,
            Collection<Integer> cameraIds,
            IntegrationFeatureConfig.InspectionTriggerMode mode,
            int captureTriggerStaggerMs,
            Runnable onLineWorkChanged,
            InspectionTriggerRuntime[] holder,
            List<BucketGroup> bucketGroups
    ) {
        return start(
                log,
                integration,
                cameraIds,
                mode,
                captureTriggerStaggerMs,
                onLineWorkChanged,
                holder,
                bucketGroups,
                null
        );
    }

    public static InspectionTriggerRuntime start(
            Logger log,
            Map<String, Object> integration,
            Collection<Integer> cameraIds,
            IntegrationFeatureConfig.InspectionTriggerMode mode,
            int captureTriggerStaggerMs,
            Runnable onLineWorkChanged,
            InspectionTriggerRuntime[] holder,
            List<BucketGroup> bucketGroups,
            ManualLineDirectionService manualLineDirection
    ) {
        InspectionTriggerBus bus = new InspectionTriggerBus(cameraIds, captureTriggerStaggerMs);
        if (captureTriggerStaggerMs > 0) {
            log.info("inspection trigger stagger enabled delay_ms={} cameras={}", captureTriggerStaggerMs, cameraIds.size());
        }
        List<TriggerTransport> transports = new ArrayList<>();
        IoInputMonitorUdpTriggerTransportImpl ioInputTransport = null;
        if (mode == IntegrationFeatureConfig.InspectionTriggerMode.EXTERNAL) {
            InspectionTriggerConfig cfg = InspectionTriggerConfig.parse(integration);
            UdpTriggerConfig udp = cfg.udp();
            if (cfg.usesIoInputMonitor()) {
                ioInputTransport = new IoInputMonitorUdpTriggerTransportImpl(
                        log,
                        udp,
                        cfg.ioInput(),
                        bus,
                        onLineWorkChanged,
                        bucketGroups,
                        manualLineDirection
                );
                transports.add(ioInputTransport);
            } else if (udp.enabled()) {
                transports.add(new UdpTriggerTransportImpl(log, udp, bus));
            } else {
                log.warn("inspection_trigger mode=external but udp.enabled=false — no transport started");
            }
        }
        InspectionTriggerRuntime runtime = new InspectionTriggerRuntime(bus, transports, ioInputTransport);
        if (holder != null && holder.length > 0) {
            holder[0] = runtime;
        }
        for (TriggerTransport transport : transports) {
            transport.start();
        }
        return runtime;
    }

    @Override
    public void close() {
        for (TriggerTransport transport : transports) {
            try {
                transport.close();
            } catch (Exception ignored) {
            }
        }
        try {
            bus.close();
        } catch (Exception ignored) {
        }
    }
}
