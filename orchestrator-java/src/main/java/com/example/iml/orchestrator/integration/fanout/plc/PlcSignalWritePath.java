package com.example.iml.orchestrator.integration.fanout.plc;

import com.example.iml.orchestrator.integration.plc.PlcFinsPublisher;
import com.example.iml.orchestrator.integration.plc.PlcRegisterMap;
import com.example.iml.orchestrator.integration.plc.PlcSignalDefinition;
import com.example.iml.orchestrator.integration.plc.PlcSignalState;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Ручная запись / импульс битовых сигналов ПЛК из register-map.
 */
public final class PlcSignalWritePath {
    private final Logger log;
    private final PlcFinsPublisher plcPublisher;
    private final PlcRegisterMap registerMap;

    public PlcSignalWritePath(Logger log, PlcFinsPublisher plcPublisher, PlcRegisterMap registerMap) {
        this.log = log;
        this.plcPublisher = plcPublisher;
        this.registerMap = registerMap;
    }

    public List<PlcSignalState> listSignals() {
        Map<String, Boolean> live = Map.of();
        if (plcPublisher != null) {
            try {
                live = plcPublisher.readSignalBits(registerMap.signals());
            } catch (Exception e) {
                log.debug("plc fins signal read failed: {}", e.getMessage());
            }
        }
        List<PlcSignalState> signals = new ArrayList<>();
        for (PlcSignalDefinition signal : registerMap.signals()) {
            Boolean last = live.get(signal.name());
            if (last == null && plcPublisher != null) {
                last = plcPublisher.lastSignalValue(signal.name());
            }
            signals.add(toState(signal, last));
        }
        return signals;
    }

    public List<PlcSignalState> writeSignals(
            Map<String, Boolean> valuesByName,
            Map<String, Boolean> pulseByName,
            boolean manualControlEditable
    ) throws IOException, InterruptedException, TimeoutException {
        if (!manualControlEditable) {
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
            PlcSignalDefinition def = registerMap.find(name)
                    .orElseThrow(() -> new IllegalArgumentException("unknown signal: " + name));
            if (!def.writable()) {
                throw new IllegalArgumentException(
                        "signal is read-only (direction=plc_to_pc): " + name
                );
            }
            boolean value = Boolean.TRUE.equals(entry.getValue());
            boolean pulse = Boolean.TRUE.equals(pulses.get(name));
            ensurePlc();
            plcPublisher.writeSignal(name, value, pulse);
        }
        return listSignals();
    }

    private void ensurePlc() {
        if (plcPublisher == null) {
            throw new IllegalStateException("plc_fins disabled");
        }
    }

    private static PlcSignalState toState(PlcSignalDefinition signal, Boolean last) {
        return new PlcSignalState(
                signal.name(),
                signal.description(),
                signal.area().name(),
                signal.address().word() + "." + signal.address().bit(),
                signal.bucketGroupId(),
                last,
                signal.direction(),
                signal.writable()
        );
    }
}
