package com.example.iml.orchestrator.integration.hikrobot.mvio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MvIoInputLevelTest {

    @Test
    void structureSizeMatchesSdkLayout() {
        // 9 × byte + 8 × uint32 per MvIOInterfaceBoxDefine.h
        assertEquals(41, new MvIoInputLevel().size());
    }

    @Test
    void levelForPortMapsZeroBasedLevels() {
        MvIoInputLevel level = new MvIoInputLevel();
        level.nLevel0 = 1;
        level.nLevel2 = 1;
        assertEquals((byte) 1, level.levelForPort(1));
        assertEquals((byte) 0, level.levelForPort(2));
        assertEquals((byte) 1, level.levelForPort(3));
    }
}
