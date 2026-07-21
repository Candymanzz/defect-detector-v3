package com.example.iml.orchestrator.integration.fanout;

import com.example.iml.orchestrator.integration.clientws.ClientWebSocketServer;
import com.example.iml.orchestrator.integration.clientws.session.ClientWsSessionState;
import com.example.iml.orchestrator.integration.pipeline.session.PerCameraInspectionGate;
import com.example.iml.orchestrator.integration.plc.PlcBcd;
import com.example.iml.orchestrator.integration.plc.PlcFinsApi;
import com.example.iml.orchestrator.integration.plc.PlcFinsConfig;
import com.example.iml.orchestrator.integration.plc.PlcFinsPublisher;
import com.example.iml.orchestrator.integration.plc.PlcMemoryArea;
import com.example.iml.orchestrator.integration.plc.PlcRegisterMap;
import com.example.iml.orchestrator.integration.plc.PlcRegisterMapLoader;
import com.example.iml.orchestrator.integration.plc.PlcSignalDefinition;
import com.example.iml.orchestrator.integration.plc.PlcSignalState;
import com.example.iml.orchestrator.integration.plc.PlcTimeoutDefinition;
import com.example.iml.orchestrator.integration.plc.PlcTimeoutState;
import com.example.iml.orchestrator.integration.trigger.IoInputMonitorRejectClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Публикация итога инспекции по ведру: брак (дискретные DI через IoInputMonitor),
 * прочие сигналы ПЛК (FINS) и UI (WebSocket).
 */
public final class FanOutCoordinator implements AutoCloseable, BucketFanOutSink, PlcFinsApi {
    private static final Logger log = LogManager.getLogger(FanOutCoordinator.class);

    private final PlcFinsPublisher plcPublisher;
    private final IoInputMonitorRejectClient rejectClient;
    private final ClientWebSocketServer clientWsServer;
    private final PerCameraInspectionGate inspectionGate;
    private final PlcRegisterMap registerMap;

    private FanOutCoordinator(
            PlcFinsPublisher plcPublisher,
            IoInputMonitorRejectClient rejectClient,
            ClientWebSocketServer clientWsServer,
            PerCameraInspectionGate inspectionGate,
            PlcRegisterMap registerMap
    ) {
        this.plcPublisher = plcPublisher;
        this.rejectClient = rejectClient;
        this.clientWsServer = clientWsServer;
        this.inspectionGate = inspectionGate;
        this.registerMap = registerMap == null ? new PlcRegisterMap(Map.of()) : registerMap;
        if (plcPublisher != null && clientWsServer != null) {
            plcPublisher.setTrafficListener(clientWsServer::notifyPlcFinsTraffic);
        }
    }

    public static FanOutCoordinator fromConfig(
            Map<String, Object> root,
            Path projectRoot,
            ClientWebSocketServer clientWsServer
    ) {
        return fromConfig(root, projectRoot, clientWsServer, null);
    }

    @SuppressWarnings("unchecked")
    public static FanOutCoordinator fromConfig(
            Map<String, Object> root,
            Path projectRoot,
            ClientWebSocketServer clientWsServer,
            PerCameraInspectionGate inspectionGate
    ) {
        PlcFinsPublisher plcPublisher = null;
        PlcRegisterMap registerMap = null;
        PlcFinsConfig plcCfg = PlcFinsConfig.fromRoot(root, projectRoot);
        if (plcCfg.enabled()) {
            try {
                registerMap = PlcRegisterMapLoader.load(plcCfg.registerMapPath());
                plcPublisher = PlcFinsPublisher.create(log, plcCfg, registerMap);
                log.info(
                        "inspection result plc_fins enabled host={}:{} map={} pulse_ms={} timeouts={}",
                        plcCfg.host(),
                        plcCfg.port(),
                        plcCfg.registerMapPath(),
                        plcCfg.pulseMs(),
                        registerMap.timeouts().size()
                );
            } catch (IOException e) {
                throw new IllegalStateException("failed to start plc fins publisher", e);
            }
        } else {
            log.info("inspection result plc_fins disabled (plc_fins.enabled=false)");
            try {
                registerMap = PlcRegisterMapLoader.load(plcCfg.registerMapPath());
            } catch (IOException e) {
                log.debug("plc register map not loaded while disabled: {}", e.getMessage());
            }
        }
        Map<String, Object> integration = null;
        if (root != null) {
            Object raw = root.get("integration");
            if (raw instanceof Map<?, ?> map) {
                integration = (Map<String, Object>) map;
            }
        }
        IoInputMonitorRejectClient rejectClient = IoInputMonitorRejectClient.fromIntegration(log, integration);
        if (rejectClient.isEnabled()) {
            log.info(
                    "inspection result plc discrete DI (IoInputMonitor) — ready/fault/reject; FINS only D4400–D4404 (no CIO 240.15)"
            );
        } else {
            log.info("inspection result reject via plc_fins W0.xx (io_input_monitor_reject.enabled=false)");
        }
        if (clientWsServer == null) {
            log.warn("inspection result client_ws unavailable — bucket verdict will not be sent to UI");
        }
        return new FanOutCoordinator(plcPublisher, rejectClient, clientWsServer, inspectionGate, registerMap);
    }

    @Override
    public void publishBucket(BucketFanOutResult result) {
        if (inspectionEnabled()) {
            if (rejectClient != null && rejectClient.isEnabled()) {
                rejectClient.publishBucket(result);
            } else if (plcPublisher != null) {
                plcPublisher.publishBucket(result);
            }
        } else {
            log.debug(
                    "plc reject skip seq={} group={} — эталон не задан (capture-only)",
                    result.triggerSequence(),
                    result.groupId()
            );
        }
        if (clientWsServer != null) {
            clientWsServer.notifyInspectBucketResult(result);
        }
    }

    public void signalVisionReady(boolean ready) {
        if (rejectClient != null && rejectClient.isEnabled()) {
            rejectClient.setVisionReady(ready);
        } else if (plcPublisher != null) {
            plcPublisher.setVisionReady(ready);
        }
    }

    public void signalVisionFault(boolean fault) {
        if (rejectClient != null && rejectClient.isEnabled()) {
            rejectClient.setVisionFault(fault);
        } else if (plcPublisher != null) {
            plcPublisher.setVisionFault(fault);
        }
    }

    @Override
    public boolean enabled() {
        return plcPublisher != null || (rejectClient != null && rejectClient.isEnabled());
    }

    @Override
    public boolean inspectionInFlight() {
        return inspectionGate != null && inspectionGate.hasAnyInspectionInFlight();
    }

    @Override
    public boolean inspectionEnabled() {
        // Для ПЛК «инспекция включена» = задан эталон (READY/OPERATIONAL), не Start/Stop gate камер.
        if (clientWsServer != null) {
            return clientWsServer.sessionState() != ClientWsSessionState.NO_REFERENCE;
        }
        return false;
    }

    @Override
    public boolean manualControlEditable() {
        return enabled() && !inspectionInFlight() && !inspectionEnabled();
    }

    @Override
    public List<PlcTimeoutDefinition> timeoutDefinitions() {
        return registerMap.timeouts();
    }

    @Override
    public List<PlcSignalState> listSignals() {
        List<PlcSignalState> signals = new ArrayList<>();
        for (PlcSignalDefinition signal : registerMap.signals()) {
            Boolean last = plcPublisher == null ? null : plcPublisher.lastSignalValue(signal.name());
            signals.add(new PlcSignalState(
                    signal.name(),
                    signal.description(),
                    signal.area().name(),
                    signal.address().word() + "." + signal.address().bit(),
                    signal.bucketGroupId(),
                    last
            ));
        }
        return signals;
    }

    @Override
    public List<PlcSignalState> writeSignals(Map<String, Boolean> valuesByName, Map<String, Boolean> pulseByName)
            throws IOException, InterruptedException, TimeoutException {
        if (!manualControlEditable()) {
            throw new IllegalStateException(
                    "PLC signals are locked while reference is set or inspection is in flight"
            );
        }
        if (valuesByName == null || valuesByName.isEmpty()) {
            throw new IllegalArgumentException("signals body is empty");
        }
        Map<String, Boolean> pulses = pulseByName == null ? Map.of() : pulseByName;
        for (Map.Entry<String, Boolean> entry : valuesByName.entrySet()) {
            String name = entry.getKey() == null ? "" : entry.getKey().trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("signal name required");
            }
            registerMap.find(name).orElseThrow(() -> new IllegalArgumentException("unknown signal: " + name));
            boolean value = Boolean.TRUE.equals(entry.getValue());
            boolean pulse = Boolean.TRUE.equals(pulses.get(name));
            if (IoInputMonitorRejectClient.isDiscreteSignal(name)
                    && rejectClient != null
                    && rejectClient.isEnabled()) {
                if (IoInputMonitorRejectClient.isRejectSignal(name)) {
                    if (value || pulse) {
                        rejectClient.pulseSignal(name);
                    }
                } else {
                    rejectClient.writeSignalLevel(name, value);
                }
                continue;
            }
            ensurePlc();
            plcPublisher.writeSignal(name, value, pulse);
        }
        return listSignals();
    }

    @Override
    public List<PlcTimeoutState> readTimeouts() throws IOException, InterruptedException, TimeoutException {
        ensurePlc();
        List<PlcTimeoutDefinition> defs = sortedTimeouts();
        if (defs.isEmpty()) {
            return List.of();
        }
        int start = defs.get(0).wordAddress();
        int end = defs.get(defs.size() - 1).wordAddress();
        int count = end - start + 1;
        int[] raw = plcPublisher.readWords(PlcMemoryArea.DM, start, count, "timeouts_D" + start + "_D" + end);
        List<PlcTimeoutState> states = new ArrayList<>(defs.size());
        for (PlcTimeoutDefinition def : defs) {
            int rawWord = raw[def.wordAddress() - start] & 0xFFFF;
            states.add(toState(def, rawWord));
        }
        return states;
    }

    @Override
    public List<PlcTimeoutState> writeTimeouts(Map<String, Integer> unitsByKey)
            throws IOException, InterruptedException, TimeoutException {
        ensurePlc();
        if (!manualControlEditable()) {
            throw new IllegalStateException(
                    "PLC timeouts are locked while reference is set or inspection is in flight"
            );
        }
        if (unitsByKey == null || unitsByKey.isEmpty()) {
            throw new IllegalArgumentException("timeouts body is empty");
        }
        List<PlcTimeoutDefinition> defs = sortedTimeouts();
        if (defs.isEmpty()) {
            throw new IllegalStateException("PLC timeouts not configured in register map");
        }
        Map<Integer, Integer> unitsByWord = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : unitsByKey.entrySet()) {
            PlcTimeoutDefinition def = registerMap.findTimeout(entry.getKey())
                    .orElseThrow(() -> new IllegalArgumentException("unknown timeout key: " + entry.getKey()));
            int units = entry.getValue() == null ? 0 : entry.getValue();
            if (units < 0 || units > 9999) {
                throw new IllegalArgumentException(def.displayAddress() + " out of range 0..9999 units");
            }
            unitsByWord.put(def.wordAddress(), units);
        }
        int start = defs.get(0).wordAddress();
        int end = defs.get(defs.size() - 1).wordAddress();
        int count = end - start + 1;
        int[] current = plcPublisher.readWords(PlcMemoryArea.DM, start, count, "timeouts_read_before_write");
        int[] next = current.clone();
        for (Map.Entry<Integer, Integer> entry : unitsByWord.entrySet()) {
            int index = entry.getKey() - start;
            PlcTimeoutDefinition def = registerMap.findTimeout("D" + entry.getKey()).orElseThrow();
            if ("bcd".equalsIgnoreCase(def.encoding())) {
                next[index] = PlcBcd.toBcdWord(entry.getValue());
            } else {
                next[index] = entry.getValue() & 0xFFFF;
            }
        }
        plcPublisher.writeWords(PlcMemoryArea.DM, start, next, "timeouts_D" + start + "_D" + end);
        List<PlcTimeoutState> states = new ArrayList<>(defs.size());
        for (PlcTimeoutDefinition def : defs) {
            states.add(toState(def, next[def.wordAddress() - start] & 0xFFFF));
        }
        return states;
    }

    public String metricsSummary() {
        String plcPart = plcPublisher == null
                ? "plc=disabled"
                : ("plc.dropped=" + plcPublisher.droppedTotal());
        String rejectPart = rejectClient != null && rejectClient.isEnabled()
                ? " reject=discrete_di"
                : " reject=fins";
        return plcPart + rejectPart + " client_ws=" + (clientWsServer == null ? "disabled" : "enabled");
    }

    private void ensurePlc() {
        if (plcPublisher == null) {
            throw new IllegalStateException("plc_fins disabled");
        }
    }

    private List<PlcTimeoutDefinition> sortedTimeouts() {
        List<PlcTimeoutDefinition> defs = new ArrayList<>(registerMap.timeouts());
        defs.sort(Comparator.comparingInt(PlcTimeoutDefinition::wordAddress));
        return defs;
    }

    private static PlcTimeoutState toState(PlcTimeoutDefinition def, int rawWord) {
        int units;
        if ("bcd".equalsIgnoreCase(def.encoding())) {
            units = PlcBcd.fromBcdWord(rawWord);
        } else {
            units = rawWord & 0xFFFF;
        }
        return new PlcTimeoutState(
                def.name(),
                def.description(),
                def.displayAddress(),
                units,
                PlcBcd.unitsToMs(units),
                rawWord & 0xFFFF,
                def.encoding(),
                def.unit()
        );
    }

    @Override
    public void close() {
        if (rejectClient != null) {
            rejectClient.close();
        }
        if (plcPublisher != null) {
            plcPublisher.close();
        }
    }
}
