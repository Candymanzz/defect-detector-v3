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
                "idle_edge", "falling",
                "trigger_edge", "rising"
        ));
        root.put("light_servers", ls);

        IntervalFlashConfig cfg = IntervalFlashConfig.fromRootYaml(root);

        assertTrue(cfg.enabled());
        assertEquals(2, cfg.idlePort());
        assertEquals(3, cfg.triggerPort());
        assertEquals(40, cfg.offDelayMs());
        assertEquals(TriggerEdgeMode.FALLING, cfg.idleEdge());
        assertEquals(TriggerEdgeMode.RISING, cfg.triggerEdge());
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
    }

    @Test
    void defaultsIdleFallingAndOffDelay300() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> ls = new LinkedHashMap<>();
        ls.put("interval_flash", Map.of("enabled", true));
        root.put("light_servers", ls);

        IntervalFlashConfig cfg = IntervalFlashConfig.fromRootYaml(root);

        assertEquals(TriggerEdgeMode.FALLING, cfg.idleEdge());
        assertEquals(300, cfg.offDelayMs());
    }

    @Test
    void risingEdgeDetection() {
        assertTrue(IntervalFlashController.isEdge(false, true, TriggerEdgeMode.RISING));
        assertFalse(IntervalFlashController.isEdge(true, true, TriggerEdgeMode.RISING));
        assertFalse(IntervalFlashController.isEdge(true, false, TriggerEdgeMode.RISING));
        assertTrue(IntervalFlashController.isEdge(true, false, TriggerEdgeMode.FALLING));
        assertTrue(IntervalFlashController.isIdleLevel(false, TriggerEdgeMode.FALLING));
        assertFalse(IntervalFlashController.isIdleLevel(true, TriggerEdgeMode.FALLING));
    }

    @Test
    void idleFallingTurnsOnDi3RisingTurnsOnThenOff() throws Exception {
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
                true, 2, 3, TriggerEdgeMode.FALLING, TriggerEdgeMode.RISING, 0, true
        );
        try (IntervalFlashController controller = new IntervalFlashController(
                LogManager.getLogger(IntervalFlashControllerTest.class),
                lights,
                cfg
        )) {
            // Init: DI2 high (не холостой), DI3 low — без On/Off.
            controller.onDiChange(new IoInputDiChange(2, true));
            controller.onDiChange(new IoInputDiChange(3, false));
            controller.awaitLightTasks(500);
            assertEquals(0, onCount.get());
            assertEquals(0, offCount.get());

            // Холостой DI2↓ → On
            controller.onDiChange(new IoInputDiChange(2, false));
            controller.awaitLightTasks(500);
            assertEquals(1, onCount.get());
            assertTrue(controller.lightsOn());

            // DI3↑ → On снова + Off (delay 0)
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
                true, 2, 3, TriggerEdgeMode.FALLING, TriggerEdgeMode.RISING, 0, true
        );
        try (IntervalFlashController controller = new IntervalFlashController(
                LogManager.getLogger(IntervalFlashControllerTest.class),
                lights,
                cfg
        )) {
            controller.onDiChange(new IoInputDiChange(2, true));
            controller.onDiChange(new IoInputDiChange(3, false));
            controller.awaitLightTasks(500);

            // Без холостого — всё равно On на DI3 (фикс «через цикл»).
            controller.onDiChange(new IoInputDiChange(3, true));
            controller.awaitLightTasks(500);
            assertEquals(1, onCount.get());
            assertEquals(1, offCount.get());
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
                true, 2, 3, TriggerEdgeMode.FALLING, TriggerEdgeMode.RISING, 0, true
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
}
