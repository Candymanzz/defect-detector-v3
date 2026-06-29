package com.example.iml.orchestrator.integration.gpio;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Чтение GPIO через sysfs value-файл ({@code /sys/class/gpio/gpioN/value}). */
public final class SysfsDigitalInputReader implements DigitalInputReader {

    private final Path path;
    private final int activeValue;

    public SysfsDigitalInputReader(String path, int activeValue) {
        this.path = Path.of(path);
        this.activeValue = activeValue != 0 ? 1 : 0;
    }

    @Override
    public boolean readActive() throws Exception {
        if (!Files.isReadable(path)) {
            throw new IllegalStateException("GPIO path not readable: " + path);
        }
        String raw = Files.readString(path, StandardCharsets.US_ASCII).trim();
        int value = parseValue(raw);
        return value == activeValue;
    }

    static int parseValue(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalStateException("empty GPIO value");
        }
        char first = raw.charAt(0);
        if (first == '0') {
            return 0;
        }
        if (first == '1') {
            return 1;
        }
        throw new IllegalStateException("unexpected GPIO value: " + raw);
    }

    public Path path() {
        return path;
    }
}
