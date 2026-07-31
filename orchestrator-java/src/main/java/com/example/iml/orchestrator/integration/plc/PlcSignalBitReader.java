package com.example.iml.orchestrator.integration.plc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

/**
 * Groups PLC signal definitions into contiguous word ranges and reads bits.
 */
final class PlcSignalBitReader {

    @FunctionalInterface
    interface WordReader {
        int[] read(PlcMemoryArea area, int startWord, int count, String signal)
                throws IOException, InterruptedException, TimeoutException;
    }

    private final ConcurrentHashMap<String, Boolean> lastSignalValues;
    private final WordReader wordReader;

    PlcSignalBitReader(ConcurrentHashMap<String, Boolean> lastSignalValues, WordReader wordReader) {
        this.lastSignalValues = lastSignalValues;
        this.wordReader = wordReader;
    }

    Map<String, Boolean> readSignalBits(Collection<PlcSignalDefinition> signals)
            throws IOException, InterruptedException, TimeoutException {
        Map<String, Boolean> values = new java.util.LinkedHashMap<>();
        if (signals == null || signals.isEmpty()) {
            return values;
        }
        Map<PlcMemoryArea, TreeMap<Integer, List<PlcSignalDefinition>>> byArea = new EnumMap<>(PlcMemoryArea.class);
        for (PlcSignalDefinition signal : signals) {
            byArea
                    .computeIfAbsent(signal.area(), ignored -> new TreeMap<>())
                    .computeIfAbsent(signal.address().word(), ignored -> new ArrayList<>())
                    .add(signal);
        }
        for (Map.Entry<PlcMemoryArea, TreeMap<Integer, List<PlcSignalDefinition>>> areaEntry : byArea.entrySet()) {
            readArea(areaEntry.getKey(), areaEntry.getValue(), values);
        }
        return values;
    }

    private void readArea(
            PlcMemoryArea area,
            TreeMap<Integer, List<PlcSignalDefinition>> byWord,
            Map<String, Boolean> values
    ) throws IOException, InterruptedException, TimeoutException {
        if (byWord.isEmpty()) {
            return;
        }
        List<Integer> words = new ArrayList<>(byWord.keySet());
        int rangeStart = 0;
        while (rangeStart < words.size()) {
            int startWord = words.get(rangeStart);
            int rangeEnd = rangeStart;
            while (rangeEnd + 1 < words.size() && words.get(rangeEnd + 1) == words.get(rangeEnd) + 1) {
                rangeEnd++;
            }
            int endWord = words.get(rangeEnd);
            int count = endWord - startWord + 1;
            int[] raw = wordReader.read(
                    area, startWord, count, "signals_" + area.name() + "_" + startWord + "_" + endWord);
            for (int i = rangeStart; i <= rangeEnd; i++) {
                int wordAddr = words.get(i);
                int wordValue = raw[wordAddr - startWord] & 0xFFFF;
                for (PlcSignalDefinition signal : byWord.get(wordAddr)) {
                    boolean bit = ((wordValue >> signal.address().bit()) & 1) == 1;
                    values.put(signal.name(), bit);
                    lastSignalValues.put(signal.name(), bit);
                }
            }
            rangeStart = rangeEnd + 1;
        }
    }
}
