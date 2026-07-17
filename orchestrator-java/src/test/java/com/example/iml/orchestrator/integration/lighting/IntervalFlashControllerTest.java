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
                "on_port", 2,
                "off_port", 3,
                "off_delay_ms", 40,
                "on_reengage_delay_ms", 5000,
                "on_edge", "rising",
                "off_edge", "rising"
        ));
        root.put("light_servers", ls);

        IntervalFlashConfig cfg = IntervalFlashConfig.fromRootYaml(root);

        assertTrue(cfg.enabled());
        assertEquals(2, cfg.onPort());
        assertEquals(3, cfg.offPort());
        assertEquals(40, cfg.offDelayMs());
        assertEquals(5000, cfg.onReengageDelayMs());
        assertEquals(TriggerEdgeMode.RISING, cfg.onEdge());
    }

    @Test
    void risingEdgeDetection() {
        assertTrue(IntervalFlashController.isEdge(false, true, TriggerEdgeMode.RISING));
        assertFalse(IntervalFlashController.isEdge(true, true, TriggerEdgeMode.RISING));
        assertFalse(IntervalFlashController.isEdge(true, false, TriggerEdgeMode.RISING));
        assertTrue(IntervalFlashController.isEdge(true, false, TriggerEdgeMode.FALLING));
    }

    @Test
    void di2RisingTurnsOnDi3RisingTurnsOff() {
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
                true, 2, 3, TriggerEdgeMode.RISING, TriggerEdgeMode.RISING, 0, 0, true
        );
        try (IntervalFlashController controller = new IntervalFlashController(
                LogManager.getLogger(IntervalFlashControllerTest.class),
                lights,
                cfg
        )) {
            controller.onDiChange(new IoInputDiChange(2, false));
            controller.onDiChange(new IoInputDiChange(3, false));
            assertEquals(0, onCount.get());
            assertEquals(0, offCount.get());

            controller.onDiChange(new IoInputDiChange(2, true));
            assertEquals(1, onCount.get());
            assertTrue(controller.lightsOn());

            controller.onDiChange(new IoInputDiChange(3, true));
            assertEquals(1, offCount.get());
            assertFalse(controller.lightsOn());
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
                true, 2, 3, TriggerEdgeMode.RISING, TriggerEdgeMode.RISING, 0, 80, true
        );
        try (IntervalFlashController controller = new IntervalFlashController(
                LogManager.getLogger(IntervalFlashControllerTest.class),
                lights,
                cfg
        )) {
            controller.onDiChange(new IoInputDiChange(2, false));
            controller.onDiChange(new IoInputDiChange(3, false));
            controller.onDiChange(new IoInputDiChange(2, true));
            assertEquals(1, onCount.get());
            assertTrue(controller.lightsOn());

            controller.onDiChange(new IoInputDiChange(3, true));
            assertEquals(1, offCount.get());
            assertFalse(controller.lightsOn());

            Thread.sleep(50);
            assertEquals(1, onCount.get());

            Thread.sleep(50);
            assertEquals(2, onCount.get());
            assertTrue(controller.lightsOn());
        }
    }
}
