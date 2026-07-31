package com.example.iml.orchestrator.integration.fanout.plc;

import com.example.iml.orchestrator.integration.plc.PlcBcd;
import com.example.iml.orchestrator.integration.plc.PlcFinsPublisher;
import com.example.iml.orchestrator.integration.plc.PlcMemoryArea;
import com.example.iml.orchestrator.integration.plc.PlcRegisterMap;
import com.example.iml.orchestrator.integration.plc.PlcTimeoutDefinition;
import com.example.iml.orchestrator.integration.plc.PlcTimeoutState;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Чтение / запись таймаутов и флагов DM из register-map.
 */
public final class PlcTimeoutWritePath {
    private final PlcFinsPublisher plcPublisher;
    private final PlcRegisterMap registerMap;

    public PlcTimeoutWritePath(PlcFinsPublisher plcPublisher, PlcRegisterMap registerMap) {
        this.plcPublisher = plcPublisher;
        this.registerMap = registerMap;
    }

    public List<PlcTimeoutDefinition> timeoutDefinitions() {
        return registerMap.timeouts();
    }

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

    public List<PlcTimeoutState> writeTimeouts(Map<String, Integer> unitsByKey, boolean timeoutsEditable)
            throws IOException, InterruptedException, TimeoutException {
        ensurePlc();
        if (!timeoutsEditable) {
            throw new IllegalStateException("PLC timeouts require plc_fins enabled");
        }
        if (unitsByKey == null || unitsByKey.isEmpty()) {
            throw new IllegalArgumentException("timeouts body is empty");
        }
        List<PlcTimeoutDefinition> defs = sortedTimeouts();
        if (defs.isEmpty()) {
            throw new IllegalStateException("PLC timeouts not configured in register map");
        }
        Map<Integer, Integer> encodedByWord = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : unitsByKey.entrySet()) {
            PlcTimeoutDefinition def = registerMap.findTimeout(entry.getKey())
                    .orElseThrow(() -> new IllegalArgumentException("unknown timeout key: " + entry.getKey()));
            int units = entry.getValue() == null ? 0 : entry.getValue();
            encodedByWord.put(def.wordAddress(), encodeTimeoutWord(def, units));
        }
        // Пишем только изменённые слова — флаги DM (0/1) не затираются при сохранении таймаутов.
        for (Map.Entry<Integer, Integer> entry : encodedByWord.entrySet()) {
            int wordAddress = entry.getKey();
            plcPublisher.writeWords(
                    PlcMemoryArea.DM,
                    wordAddress,
                    new int[]{entry.getValue()},
                    "timeout_D" + wordAddress
            );
        }
        return readTimeouts();
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

    private static int encodeTimeoutWord(PlcTimeoutDefinition def, int units) {
        if (isFlag(def)) {
            if (units != 0 && units != 1) {
                throw new IllegalArgumentException(def.displayAddress() + " flag must be 0 or 1");
            }
            return units & 0xFFFF;
        }
        if (units < 0 || units > 9999) {
            throw new IllegalArgumentException(def.displayAddress() + " out of range 0..9999 units");
        }
        if ("bcd".equalsIgnoreCase(def.encoding())) {
            return PlcBcd.toBcdWord(units);
        }
        return units & 0xFFFF;
    }

    private static boolean isFlag(PlcTimeoutDefinition def) {
        return "flag".equalsIgnoreCase(def.unit()) || "bool".equalsIgnoreCase(def.unit());
    }

    private static PlcTimeoutState toState(PlcTimeoutDefinition def, int rawWord) {
        int units;
        if ("bcd".equalsIgnoreCase(def.encoding())) {
            units = PlcBcd.fromBcdWord(rawWord);
        } else if (isFlag(def)) {
            units = (rawWord & 0xFFFF) != 0 ? 1 : 0;
        } else {
            units = rawWord & 0xFFFF;
        }
        int valueMs = isFlag(def) ? 0 : PlcBcd.unitsToMs(units);
        return new PlcTimeoutState(
                def.name(),
                def.description(),
                def.displayAddress(),
                units,
                valueMs,
                rawWord & 0xFFFF,
                def.encoding(),
                def.unit()
        );
    }
}
