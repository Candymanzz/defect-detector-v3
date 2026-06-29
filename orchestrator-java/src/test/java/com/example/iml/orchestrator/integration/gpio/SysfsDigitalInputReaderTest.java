package com.example.iml.orchestrator.integration.gpio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SysfsDigitalInputReaderTest {

    @Test
    void parsesZeroAndOne() {
        assertEquals(0, SysfsDigitalInputReader.parseValue("0"));
        assertEquals(1, SysfsDigitalInputReader.parseValue("1"));
        assertEquals(0, SysfsDigitalInputReader.parseValue("0\n"));
        assertEquals(1, SysfsDigitalInputReader.parseValue("1\n"));
    }

    @Test
    void rejectsUnexpectedValues() {
        assertThrows(IllegalStateException.class, () -> SysfsDigitalInputReader.parseValue(""));
        assertThrows(IllegalStateException.class, () -> SysfsDigitalInputReader.parseValue("x"));
    }
}
