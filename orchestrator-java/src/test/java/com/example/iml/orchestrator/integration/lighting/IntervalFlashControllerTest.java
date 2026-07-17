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

    @Test
    void parsesIntervalFlashFromRoot() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> ls = new LinkedHashMap<>();
        ls.put("interval_flash", Map.of(
                "enabled", true,
                "idle_port", 2,
                "trigger_port", 3,
                "off_delay_ms", 40,
                "on_reengage_delay_ms", 5000,
                "idle_edge", "falling",
                "trigger_edge", "rising",
                "idle_on", true
        ));
        root.put("light_servers", ls);

        IntervalFlashConfig cfg = IntervalFlashConfig.fromRootYaml(root);

        assertTrue(cfg.enabled());
        assertEquals(2, cfg.idlePort());
        assertEquals(3, cfg.triggerPort());
        assertEquals(40, cfg.offDelayMs());
        assertEquals(5000, cfg.onReengageDelayMs());
        assertEquals(TriggerEdgeMode.FALLING, cfg.idleEdge());
        assertEquals(TriggerEdgeMode.RISING, cfg.triggerEdge());
        assertTrue(cfg.idleOnEnabled());
    }

    @Test
    void idleOnDefaultsToFalse() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> ls = new LinkedHashMap<>();
        ls.put("interval_flash", Map.of("enabled", true));
        root.put("light_servers", ls);

        IntervalFlashConfig cfg = IntervalFlashConfig.fromRootYaml(root);

        assertFalse(cfg.idleOnEnabled());
        assertEquals(TriggerEdgeMode.FALLING, cfg.idleEdge());
        assertEquals(300, cfg.offDelayMs());
    }

    @Test
    void parsesLegacyOnOffKeys() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> ls = new LinkedHashMap<>();
        ls.put("interval_flash", Map.of(
                "enabled", true,
                "on_port", 2,
                "off_port", 3,
                "on_edge", "rising",
                "off_edge", "rising",
                "off_delay_ms", 300
        ));
        root.put("light_servers", ls);

        IntervalFlashConfig cfg = IntervalFlashConfig.fromRootYaml(root);

        assertEquals(2, cfg.idlePort());
        assertEquals(3, cfg.triggerPort());
        assertEquals(TriggerEdgeMode.RISING, cfg.idleEdge());
        assertEquals(TriggerEdgeMode.RISING, cfg.triggerEdge());
        assertEquals(300, cfg.offDelayMs());
        assertFalse(cfg.idleOnEnabled());
    }

    @Test
    void risingEdgeDetection() {
        assertTrue(IntervalFlashController.isEdge(false, true, TriggerEdgeMode.RISING));
        assertFalse(IntervalFlashController.isEdge(true, false, TriggerEdgeMode.RISING));
        assertTrue(IntervalFlashController.isEdge(true, false, TriggerEdgeMode.FALLING));
    }

    @Test
    void idleThenDi3OnWithDelayedOff() throws Exception {
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
        IntervalFlashConfig cfg = new IntervalFlashConfig(
                true, 2, 3, TriggerEdgeMode.FALLING, TriggerEdgeMode.RISING, 0, 0, true, true
        );
        try (IntervalFlashController controller = new IntervalFlashController(
                LogManager.getLogger(IntervalFlashControllerTest.class),
                lights,
                cfg
        )) {
            controller.onDiChange(new IoInputDiChange(2, true));
            controller.onDiChange(new IoInputDiChange(3, false));
            controller.awaitLightTasks(500);
            assertEquals(0, onCount.get());
            assertEquals(0, offCount.get());

            controller.onDiChange(new IoInputDiChange(2, false));
            controller.awaitLightTasks(500);
            assertEquals(1, onCount.get());
            assertTrue(controller.lightsOn());

            controller.onDiChange(new IoInputDiChange(3, true));
            controller.awaitLightTasks(500);
            assertEquals(2, onCount.get());
            assertEquals(1, offCount.get());
            assertFalse(controller.lightsOn());
        }
    }

    @Test
    void di3AloneTurnsOnEvenWithoutIdle() throws Exception {
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
        IntervalFlashConfig cfg = new IntervalFlashConfig(
                true, 2, 3, TriggerEdgeMode.FALLING, TriggerEdgeMode.RISING, 0, 0, true, false
        );
        try (IntervalFlashController controller = new IntervalFlashController(
                LogManager.getLogger(IntervalFlashControllerTest.class),
                lights,
                cfg
        )) {
            controller.onDiChange(new IoInputDiChange(2, true));
            controller.onDiChange(new IoInputDiChange(3, false));
            controller.awaitLightTasks(500);

            controller.onDiChange(new IoInputDiChange(3, true));
            controller.awaitLightTasks(500);
            assertEquals(1, onCount.get());
            assertEquals(1, offCount.get());
        }
    }

    @Test
    void idleDoesNotCancelPendingOff() throws Exception {
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
        IntervalFlashConfig cfg = new IntervalFlashConfig(
                true, 2, 3, TriggerEdgeMode.FALLING, TriggerEdgeMode.RISING, 80, 0, true, true
        );
        try (IntervalFlashController controller = new IntervalFlashController(
                LogManager.getLogger(IntervalFlashControllerTest.class),
                lights,
                cfg
        )) {
            controller.onDiChange(new IoInputDiChange(2, true));
            controller.onDiChange(new IoInputDiChange(3, false));
            controller.awaitLightTasks(500);

            controller.onDiChange(new IoInputDiChange(3, true));
            controller.onDiChange(new IoInputDiChange(2, false));
            Thread.sleep(150);
            controller.awaitLightTasks(500);

            assertTrue(offCount.get() >= 1, "Off must run even if DI2 idle arrives early");
            assertTrue(onCount.get() >= 2, "DI3 On + idle On after Off");
        }
    }

    @Test
    void brightnessUpdateLatchesWithOffOnOff() throws Exception {
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
        IntervalFlashConfig cfg = new IntervalFlashConfig(
                true, 2, 3, TriggerEdgeMode.FALLING, TriggerEdgeMode.RISING, 0, 0, true, false
        );
        try (IntervalFlashController controller = new IntervalFlashController(
                LogManager.getLogger(IntervalFlashControllerTest.class),
                lights,
                cfg
        )) {
            controller.onBrightnessUpdated();
            Thread.sleep(120);
            controller.awaitLightTasks(500);
            assertEquals(1, onCount.get());
            assertTrue(offCount.get() >= 2, "Off before and after On");
            assertFalse(controller.lightsOn());
        }
    }

    @Test
    void idleOnDisabledIgnoresDi2() throws Exception {
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
        IntervalFlashConfig cfg = new IntervalFlashConfig(
                true, 2, 3, TriggerEdgeMode.FALLING, TriggerEdgeMode.RISING, 0, 0, true, false
        );
        try (IntervalFlashController controller = new IntervalFlashController(
                LogManager.getLogger(IntervalFlashControllerTest.class),
                lights,
                cfg
        )) {
            controller.onDiChange(new IoInputDiChange(2, false));
            controller.awaitLightTasks(500);
            assertEquals(0, onCount.get());
        }
    }

    @Test
    void alreadyIdleOnFirstSampleTurnsOn() throws Exception {
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
        IntervalFlashConfig cfg = new IntervalFlashConfig(
                true, 2, 3, TriggerEdgeMode.FALLING, TriggerEdgeMode.RISING, 0, 0, true, true
        );
        try (IntervalFlashController controller = new IntervalFlashController(
                LogManager.getLogger(IntervalFlashControllerTest.class),
                lights,
                cfg
        )) {
            controller.onDiChange(new IoInputDiChange(2, false));
            controller.awaitLightTasks(500);
            assertEquals(1, onCount.get());
        }
    }

    @Test
    void di3OffThenAutoReengageAfterDelay() throws Exception {
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
        IntervalFlashConfig cfg = new IntervalFlashConfig(
                true, 2, 3, TriggerEdgeMode.FALLING, TriggerEdgeMode.RISING, 0, 80, true, false
        );
        try (IntervalFlashController controller = new IntervalFlashController(
                LogManager.getLogger(IntervalFlashControllerTest.class),
                lights,
                cfg
        )) {
            controller.onDiChange(new IoInputDiChange(2, true));
            controller.onDiChange(new IoInputDiChange(3, false));
            controller.awaitLightTasks(500);

            controller.onDiChange(new IoInputDiChange(3, true));
            controller.awaitLightTasks(500);
            assertEquals(1, onCount.get());
            assertEquals(1, offCount.get());
            assertFalse(controller.lightsOn());

            Thread.sleep(50);
            assertEquals(1, onCount.get());

            Thread.sleep(50);
            controller.awaitLightTasks(500);
            assertEquals(2, onCount.get());
            assertTrue(controller.lightsOn());
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
        IntervalFlashConfig cfg = new IntervalFlashConfig(
                true, 2, 3, TriggerEdgeMode.FALLING, TriggerEdgeMode.RISING, 0, 0, true, true
        );
        IntervalFlashController controller = new IntervalFlashController(
                LogManager.getLogger(IntervalFlashControllerTest.class),
                lights,
                cfg
        );
        controller.onDiChange(new IoInputDiChange(2, false));
        controller.awaitLightTasks(500);
        assertTrue(controller.lightsOn());

        controller.close();
        assertEquals(1, offCount.get());
        assertFalse(controller.lightsOn());
    }
}
