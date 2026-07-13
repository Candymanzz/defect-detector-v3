package com.example.iml.orchestrator.integration.trigger.parse;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IoInputDiChangeParserTest {

    @Test
    void parsesJsonPayload() {
        byte[] payload = "{\"di\":3,\"value\":1}".getBytes(StandardCharsets.UTF_8);
        Optional<IoInputDiChange> change = IoInputDiChangeParser.parse(payload, payload.length, "json");
        assertTrue(change.isPresent());
        assertEquals(3, change.get().diPort());
        assertTrue(change.get().active());
    }

    @Test
    void parsesTextDiPayload() {
        byte[] payload = "2:0".getBytes(StandardCharsets.UTF_8);
        Optional<IoInputDiChange> change = IoInputDiChangeParser.parse(payload, payload.length, "text_di");
        assertTrue(change.isPresent());
        assertEquals(2, change.get().diPort());
        assertEquals(false, change.get().active());
    }

    @Test
    void parsesByteDiPayload() {
        byte[] payload = new byte[] {1, 1};
        Optional<IoInputDiChange> change = IoInputDiChangeParser.parse(payload, payload.length, "byte_di");
        assertTrue(change.isPresent());
        assertEquals(1, change.get().diPort());
        assertTrue(change.get().active());
    }
}
