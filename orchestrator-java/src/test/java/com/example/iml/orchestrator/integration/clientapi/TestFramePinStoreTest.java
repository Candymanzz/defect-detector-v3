package com.example.iml.orchestrator.integration.clientapi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TestFramePinStoreTest {
    @TempDir Path tempDir;

    @Test
    void rePinForSameCameraOverwritesPrevious() throws Exception {
        TestFramePinStore store = new TestFramePinStore(tempDir);
        byte[] firstBytes = new byte[] {1, 2, 3};
        byte[] secondBytes = new byte[] {4, 5, 6};

        TestFramePinStore.Pin first = store.pin(3, 10L, firstBytes, "sha-first");
        TestFramePinStore.Pin second = store.pin(3, 11L, secondBytes, "sha-second");

        assertNotEquals(first.pinId(), second.pinId());
        assertTrue(store.get(first.pinId()).isEmpty());
        assertArrayEquals(secondBytes, Files.readAllBytes(store.get(second.pinId()).orElseThrow().jpegPath()));
        assertEquals(11L, store.get(second.pinId()).orElseThrow().frameId());
    }

    @Test
    void unknownPinDoesNotFallBackToCameraLatest() {
        TestFramePinStore store = new TestFramePinStore(tempDir);
        assertTrue(store.get("missing").isEmpty());
    }
}
