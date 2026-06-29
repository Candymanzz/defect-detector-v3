package com.example.iml.orchestrator.integration.trigger;

import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.trigger.config.GpioTriggerConfig;
import com.example.iml.orchestrator.integration.trigger.config.InspectionTriggerConfig;
import com.example.iml.orchestrator.integration.trigger.config.UdpTriggerConfig;
import com.example.iml.orchestrator.integration.trigger.transport.GpioTriggerTransport;
import com.example.iml.orchestrator.integration.trigger.transport.TriggerTransport;
import com.example.iml.orchestrator.integration.trigger.transport.UdpTriggerTransport;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Жизненный цикл шины триггеров: GPIO (дискретка KROBOT) и/или UDP-транспорт.
 */
public final class InspectionTriggerRuntime implements AutoCloseable {

    private final InspectionTriggerBus bus;
    private final List<TriggerTransport> transports;
    private final GpioTriggerTransport gpioTransport;

    private InspectionTriggerRuntime(
            InspectionTriggerBus bus,
            List<TriggerTransport> transports,
            GpioTriggerTransport gpioTransport
    ) {
        this.bus = bus;
        this.transports = transports;
        this.gpioTransport = gpioTransport;
    }

    public InspectionTriggerBus bus() {
        return bus;
    }

    /** Конвейер в «Работа» (GPIO work=1). Если GPIO выключен — {@code true}. */
    public boolean isLineWorkActive() {
        return gpioTransport == null || gpioTransport.isLineWorkActive();
    }

    public static InspectionTriggerRuntime start(
            Logger log,
            Map<String, Object> integration,
            Collection<Integer> cameraIds,
            IntegrationFeatureConfig.InspectionTriggerMode mode,
            int captureTriggerStaggerMs,
            Runnable onLineWorkChanged
    ) {
        return start(log, integration, cameraIds, mode, captureTriggerStaggerMs, onLineWorkChanged, null);
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
        InspectionTriggerBus bus = new InspectionTriggerBus(cameraIds, captureTriggerStaggerMs);
        if (captureTriggerStaggerMs > 0) {
            log.info("inspection trigger stagger enabled delay_ms={} cameras={}", captureTriggerStaggerMs, cameraIds.size());
        }
        List<TriggerTransport> transports = new ArrayList<>();
        GpioTriggerTransport gpioTransport = null;
        if (mode == IntegrationFeatureConfig.InspectionTriggerMode.EXTERNAL) {
            InspectionTriggerConfig cfg = InspectionTriggerConfig.parse(integration);
            GpioTriggerConfig gpio = cfg.gpio();
            if (gpio.enabled()) {
                gpioTransport = new GpioTriggerTransport(log, gpio, bus, onLineWorkChanged);
                transports.add(gpioTransport);
            }
            UdpTriggerConfig udp = cfg.udp();
            if (udp.enabled()) {
                transports.add(new UdpTriggerTransport(log, udp, bus));
            }
            if (transports.isEmpty()) {
                log.warn("inspection_trigger mode=external but gpio and udp are both disabled");
            }
        }
        InspectionTriggerRuntime runtime = new InspectionTriggerRuntime(bus, transports, gpioTransport);
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
