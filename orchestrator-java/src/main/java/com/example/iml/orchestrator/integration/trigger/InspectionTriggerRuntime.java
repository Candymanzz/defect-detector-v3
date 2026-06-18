package com.example.iml.orchestrator.integration.trigger;

import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.trigger.config.InspectionTriggerConfig;
import com.example.iml.orchestrator.integration.trigger.config.UdpTriggerConfig;
import com.example.iml.orchestrator.integration.trigger.transport.TriggerTransport;
import com.example.iml.orchestrator.integration.trigger.transport.UdpTriggerTransport;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Жизненный цикл шины триггеров и UDP-транспорта.
 */
public final class InspectionTriggerRuntime implements AutoCloseable {

    private final InspectionTriggerBus bus;
    private final List<TriggerTransport> transports;

    private InspectionTriggerRuntime(InspectionTriggerBus bus, List<TriggerTransport> transports) {
        this.bus = bus;
        this.transports = transports;
    }

    public InspectionTriggerBus bus() {
        return bus;
    }

    public static InspectionTriggerRuntime start(
            Logger log,
            Map<String, Object> integration,
            Collection<Integer> cameraIds,
            IntegrationFeatureConfig.InspectionTriggerMode mode,
            int captureTriggerStaggerMs
    ) {
        InspectionTriggerBus bus = new InspectionTriggerBus(cameraIds, captureTriggerStaggerMs);
        if (captureTriggerStaggerMs > 0) {
            log.info("inspection trigger stagger enabled delay_ms={} cameras={}", captureTriggerStaggerMs, cameraIds.size());
        }
        List<TriggerTransport> transports = new ArrayList<>();
        if (mode == IntegrationFeatureConfig.InspectionTriggerMode.EXTERNAL) {
            InspectionTriggerConfig cfg = InspectionTriggerConfig.parse(integration);
            UdpTriggerConfig udp = cfg.udp();
            if (udp.enabled()) {
                transports.add(new UdpTriggerTransport(log, udp, bus));
            } else {
                log.warn("inspection_trigger mode=external but udp.enabled=false — no transport started");
            }
        }
        for (TriggerTransport transport : transports) {
            transport.start();
        }
        return new InspectionTriggerRuntime(bus, transports);
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
