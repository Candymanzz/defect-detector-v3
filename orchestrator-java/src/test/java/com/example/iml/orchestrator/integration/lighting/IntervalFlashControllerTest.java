package com.example.iml.orchestrator.integration.lighting;

import com.example.iml.orchestrator.integration.trigger.gpio.TriggerEdgeMode;
import com.example.iml.orchestrator.integration.trigger.parse.IoInputDiChange;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntervalFlashControllerTest {

    private static IntervalFlashConfig di2PulseConfig() {
        return new IntervalFlashConfig(
                true, 2, 3, TriggerEdgeMode.FALLING, TriggerEdgeMode.RISING, 0, 0, true, false, false
        );
    }

    @Test
    void parsesIntervalFlashFromRoot() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> ls = new LinkedHashMap<>();
        ls.put("interval_flash", Map.of(
                "enabled", true,
                "idle_port", 2,
                "start_dark", true
        ));
        root.put("light_servers", ls);

        IntervalFlashConfig cfg = IntervalFlashConfig.fromRootYaml(root);

        assertTrue(cfg.enabled());
        assertEquals(2, cfg.idlePort());
        assertTrue(cfg.startDark());
    }

    @Test
    void parsesLegacyKeysWithoutBreaking() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> ls = new LinkedHashMap<>();
        ls.put("interval_flash", Map.of(
                "enabled", true,
                "idle_port", 2,
                "trigger_port", 3,
                "off_delay_ms", 1000,
                "on_reengage_delay_ms", 5000,
                "idle_on", true,
                "off_on_first_frame", true
        ));
        root.put("light_servers", ls);

        IntervalFlashConfig cfg = IntervalFlashConfig.fromRootYaml(root);

        assertTrue(cfg.enabled());
        assertEquals(2, cfg.idlePort());
        assertEquals(3, cfg.triggerPort());
        assertEquals(1000, cfg.offDelayMs());
        assertTrue(cfg.idleOnEnabled());
        assertTrue(cfg.offOnFirstFrame());
    }

    @Test
    void risingEdgeDetection() {
        assertTrue(IntervalFlashController.isEdge(false, true, TriggerEdgeMode.RISING));
        assertFalse(IntervalFlashController.isEdge(true, false, TriggerEdgeMode.RISING));
        assertTrue(IntervalFlashController.isEdge(true, false, TriggerEdgeMode.FALLING));
    }

    @Test
    void di2RisingTurnsOnFallingTurnsOff() throws Exception {
        AtomicInteger onCount = new AtomicInteger();
        AtomicInteger offCount = new AtomicInteger();
        IntervalFlashController.Lights lights = new IntervalFlashController.Lights() {
            @Override
            public boolean lightAllOn(String phase) {
                onCount.incrementAndGet();
                return true;
            }

            @Override
            public void forceAllOff() {
                offCount.incrementAndGet();
            }
        };
        try (IntervalFlashController controller = new IntervalFlashController(
                LogManager.getLogger(IntervalFlashControllerTest.class),
                lights,
                di2PulseConfig()
        )) {
            controller.onDiChange(new IoInputDiChange(2, false));
            controller.awaitLightTasks(500);
            assertEquals(0, onCount.get());
            assertEquals(1, offCount.get(), "initial low → Off");

            controller.onDiChange(new IoInputDiChange(2, true));
            controller.awaitLightTasks(500);
            assertEquals(1, onCount.get());
            assertTrue(controller.lightsOn());
            assertTrue(controller.captureLightingActive());

            controller.onDiChange(new IoInputDiChange(2, false));
            controller.awaitLightTasks(500);
            assertEquals(2, offCount.get());
            assertFalse(controller.lightsOn());
            assertFalse(controller.captureLightingActive());
        }
    }

    @Test
    void di2AlreadyHighOnFirstSampleTurnsOn() throws Exception {
        AtomicInteger onCount = new AtomicInteger();
        IntervalFlashController.Lights lights = new IntervalFlashController.Lights() {
            @Override
            public boolean lightAllOn(String phase) {
                onCount.incrementAndGet();
                return true;
            }

            @Override
            public void forceAllOff() {
            }
        };
        try (IntervalFlashController controller = new IntervalFlashController(
                LogManager.getLogger(IntervalFlashControllerTest.class),
                lights,
                di2PulseConfig()
        )) {
            controller.onDiChange(new IoInputDiChange(2, true));
            controller.awaitLightTasks(500);
            assertEquals(1, onCount.get());
            assertTrue(controller.lightsOn());
        }
    }

    @Test
    void di3Ignored() throws Exception {
        AtomicInteger onCount = new AtomicInteger();
        AtomicInteger offCount = new AtomicInteger();
        IntervalFlashController.Lights lights = new IntervalFlashController.Lights() {
            @Override
            public boolean lightAllOn(String phase) {
                onCount.incrementAndGet();
                return true;
            }

            @Override
            public void forceAllOff() {
                offCount.incrementAndGet();
            }
        };
        try (IntervalFlashController controller = new IntervalFlashController(
                LogManager.getLogger(IntervalFlashControllerTest.class),
                lights,
                di2PulseConfig()
        )) {
            controller.onDiChange(new IoInputDiChange(3, true));
            controller.onDiChange(new IoInputDiChange(3, false));
            controller.awaitLightTasks(500);
            assertEquals(0, onCount.get());
            assertEquals(0, offCount.get());
        }
    }

    @Test
    void consecutiveDi2PulsesToggleEachTime() throws Exception {
        AtomicInteger onCount = new AtomicInteger();
        AtomicInteger offCount = new AtomicInteger();
        IntervalFlashController.Lights lights = new IntervalFlashController.Lights() {
            @Override
            public boolean lightAllOn(String phase) {
                onCount.incrementAndGet();
                return true;
            }

            @Override
            public void forceAllOff() {
                offCount.incrementAndGet();
            }
        };
        try (IntervalFlashController controller = new IntervalFlashController(
                LogManager.getLogger(IntervalFlashControllerTest.class),
                lights,
                di2PulseConfig()
        )) {
            controller.onDiChange(new IoInputDiChange(2, false));
            controller.awaitLightTasks(500);

            controller.onDiChange(new IoInputDiChange(2, true));
            controller.onDiChange(new IoInputDiChange(2, false));
            controller.onDiChange(new IoInputDiChange(2, true));
            controller.onDiChange(new IoInputDiChange(2, false));
            controller.awaitLightTasks(500);

            assertEquals(2, onCount.get());
            assertEquals(3, offCount.get(), "init Off + 2 pulse ends");
        }
    }

    @Test
    void firstFrameDoesNotExtinguish() throws Exception {
        AtomicInteger offCount = new AtomicInteger();
        IntervalFlashController.Lights lights = new IntervalFlashController.Lights() {
            @Override
            public boolean lightAllOn(String phase) {
                return true;
            }

            @Override
            public void forceAllOff() {
                offCount.incrementAndGet();
            }
        };
        try (IntervalFlashController controller = new IntervalFlashController(
                LogManager.getLogger(IntervalFlashControllerTest.class),
                lights,
                di2PulseConfig()
        )) {
            controller.onDiChange(new IoInputDiChange(2, true));
            controller.awaitLightTasks(500);
            int offBefore = offCount.get();

            controller.onFirstFrameCaptured(0);
            controller.awaitLightTasks(500);
            assertEquals(offBefore, offCount.get());
            assertTrue(controller.lightsOn());
        }
    }

    @Test
    void brightnessUpdateDoesNotToggleBank() throws Exception {
        AtomicInteger onCount = new AtomicInteger();
        AtomicInteger offCount = new AtomicInteger();
        IntervalFlashController.Lights lights = new IntervalFlashController.Lights() {
            @Override
            public boolean lightAllOn(String phase) {
                onCount.incrementAndGet();
                return true;
            }

            @Override
            public void forceAllOff() {
                offCount.incrementAndGet();
            }
        };
        try (IntervalFlashController controller = new IntervalFlashController(
                LogManager.getLogger(IntervalFlashControllerTest.class),
                lights,
                di2PulseConfig()
        )) {
            controller.onBrightnessUpdated();
            controller.awaitLightTasks(500);
            assertEquals(0, onCount.get());
            assertEquals(0, offCount.get());
        }
    }

    @Test
    void flushAfterDi2Falling() throws Exception {
        AtomicInteger flushCount = new AtomicInteger();
        IntervalFlashController.Lights lights = new IntervalFlashController.Lights() {
            @Override
            public boolean lightAllOn(String phase) {
                return true;
            }

            @Override
            public void forceAllOff() {
            }
        };
        try (IntervalFlashController controller = new IntervalFlashController(
                LogManager.getLogger(IntervalFlashControllerTest.class),
                lights,
                di2PulseConfig()
        )) {
            controller.setFlushDeferredBrightness(flushCount::incrementAndGet);
            controller.onDiChange(new IoInputDiChange(2, true));
            controller.awaitLightTasks(500);
            assertEquals(0, flushCount.get());

            controller.onDiChange(new IoInputDiChange(2, false));
            controller.awaitLightTasks(500);
            assertEquals(1, flushCount.get());
        }
    }

    @Test
    void closeForcesBankOff() throws Exception {
        AtomicInteger offCount = new AtomicInteger();
        IntervalFlashController.Lights lights = new IntervalFlashController.Lights() {
            @Override
            public boolean lightAllOn(String phase) {
                return true;
            }

            @Override
            public void forceAllOff() {
                offCount.incrementAndGet();
            }
        };
        IntervalFlashController controller = new IntervalFlashController(
                LogManager.getLogger(IntervalFlashControllerTest.class),
                lights,
                di2PulseConfig()
        );
        controller.onDiChange(new IoInputDiChange(2, true));
        controller.awaitLightTasks(500);
        assertTrue(controller.lightsOn());

        controller.close();
        assertEquals(1, offCount.get());
        assertFalse(controller.lightsOn());
    }
}
