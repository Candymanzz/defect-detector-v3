package com.example.iml.orchestrator.integration.lighting;

import com.example.iml.orchestrator.integration.trigger.gpio.TriggerEdgeMode;
import com.example.iml.orchestrator.integration.trigger.parse.IoInputDiChange;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import com.example.iml.orchestrator.integration.camera.NativeCameraEvents;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntervalFlashControllerTest {

    private static IntervalFlashConfig delayedConfig(int delayMs, boolean exposureEnd) {
        return IntervalFlashConfig.fromRootYaml(Map.of("light_servers", Map.of("interval_flash", Map.of(
                "enabled", true, "on_delay_ms", delayMs, "off_on_exposure_end", exposureEnd,
                "exposure_end_validation_cycles", 1))));
    }

    @Test
    void onDelayDefaultsToZeroAndClampsNegativeYaml() {
        assertEquals(0, di2PulseConfig().onDelayMs());
        assertEquals(0, IntervalFlashConfig.disabled().onDelayMs());
        assertEquals(0, delayedConfig(-1, false).onDelayMs());
        assertEquals(2200, delayedConfig(2200, false).onDelayMs());
    }

    @Test
    void delayedOnWaitsWithoutBlockingDiAndKeepsLightingReserved() throws Exception {
        CountDownLatch on = new CountDownLatch(1);
        AtomicLong onNs = new AtomicLong();
        var lights = new IntervalFlashController.Lights() {
            public boolean lightAllOn(String phase) {
                onNs.set(System.nanoTime()); on.countDown(); return true;
            }
            public void forceAllOff() {}
        };
        try (var controller = new IntervalFlashController(LogManager.getLogger(getClass()), lights, delayedConfig(120, false))) {
            controller.onDiChange(new IoInputDiChange(2, false));
            controller.awaitLightTasks(500);
            long startedNs = System.nanoTime();
            controller.onDiChange(new IoInputDiChange(2, true));
            controller.awaitLightTasks(500);
            assertFalse(controller.lightsOn());
            assertTrue(controller.captureLightingActive(), "brightness stays reserved while waiting");
            assertTrue(on.await(2, TimeUnit.SECONDS));
            controller.awaitLightTasks(500);
            assertTrue(onNs.get() - startedNs >= TimeUnit.MILLISECONDS.toNanos(120));
            assertTrue(controller.lightsOn());
        }
    }

    @Test
    void shortPulseCancelsPendingOnAndNextPulseStartsItsOwnDelay() throws Exception {
        CountDownLatch on = new CountDownLatch(1);
        AtomicInteger count = new AtomicInteger();
        AtomicLong onNs = new AtomicLong();
        var lights = new IntervalFlashController.Lights() {
            public boolean lightAllOn(String phase) {
                count.incrementAndGet(); onNs.set(System.nanoTime()); on.countDown(); return true;
            }
            public void forceAllOff() {}
        };
        try (var controller = new IntervalFlashController(LogManager.getLogger(getClass()), lights, delayedConfig(100, false))) {
            controller.onDiChange(new IoInputDiChange(2, false));
            controller.awaitLightTasks(500);
            controller.onDiChange(new IoInputDiChange(2, true));
            controller.onDiChange(new IoInputDiChange(2, false));
            controller.awaitLightTasks(500);
            assertFalse(on.await(180, TimeUnit.MILLISECONDS), "closed window cannot light up later");
            assertFalse(controller.captureLightingActive());
            long nextNs = System.nanoTime();
            controller.onDiChange(new IoInputDiChange(2, true));
            assertTrue(on.await(2, TimeUnit.SECONDS));
            controller.awaitLightTasks(500);
            assertEquals(1, count.get());
            assertTrue(onNs.get() - nextNs >= TimeUnit.MILLISECONDS.toNanos(100));
            assertTrue(controller.lightsOn());
        }
    }

    @Test
    void validatedExposureEndCancelsOnBeforeDelayExpires() throws Exception {
        CountDownLatch on = new CountDownLatch(1);
        var lights = new IntervalFlashController.Lights() {
            public boolean lightAllOn(String phase) { on.countDown(); return true; }
            public void forceAllOff() {}
        };
        try (var controller = new IntervalFlashController(LogManager.getLogger(getClass()), lights, delayedConfig(100, true))) {
            controller.enableExposureEnd(Set.of(0));
            controller.onDiChange(new IoInputDiChange(2, false));
            controller.awaitLightTasks(500);
            controller.onDiChange(new IoInputDiChange(2, true));
            cameraPair(controller, 0, 100);
            controller.onDiChange(new IoInputDiChange(2, false));
            controller.onDiChange(new IoInputDiChange(2, true));
            cameraPair(controller, 0, 200);
            controller.awaitLightTasks(500);
            assertFalse(controller.captureLightingActive());
            assertFalse(on.await(180, TimeUnit.MILLISECONDS));
            assertFalse(controller.lightsOn());
        }
    }

    @Test
    void closeCancelsPendingOn() throws Exception {
        CountDownLatch on = new CountDownLatch(1);
        var lights = new IntervalFlashController.Lights() {
            public boolean lightAllOn(String phase) { on.countDown(); return true; }
            public void forceAllOff() {}
        };
        var controller = new IntervalFlashController(LogManager.getLogger(getClass()), lights, delayedConfig(100, false));
        controller.onDiChange(new IoInputDiChange(2, false));
        controller.awaitLightTasks(500);
        controller.onDiChange(new IoInputDiChange(2, true));
        controller.close();
        assertFalse(on.await(180, TimeUnit.MILLISECONDS));
        assertFalse(controller.lightsOn());
    }

    @Test
    void exposureEndTurnsOffOnlyAfterValidationAndEveryCameraEnds() throws Exception {
        AtomicInteger offs = new AtomicInteger();
        var config = IntervalFlashConfig.fromRootYaml(Map.of("light_servers", Map.of("interval_flash", Map.of(
                "enabled", true, "off_on_exposure_end", true, "exposure_end_validation_cycles", 1))));
        assertTrue(config.offOnExposureEnd());
        assertEquals(1, config.exposureEndValidationCycles());
        var lights = new IntervalFlashController.Lights() {
            public boolean lightAllOn(String phase) { return true; }
            public void forceAllOff() { offs.incrementAndGet(); }
        };
        try (var controller = new IntervalFlashController(LogManager.getLogger(getClass()), lights, config)) {
            controller.enableExposureEnd(Set.of(0, 1));
            controller.onDiChange(new IoInputDiChange(2, true));
            cameraPair(controller, 0, 100); cameraPair(controller, 1, 100);
            controller.awaitLightTasks(500);
            assertTrue(controller.lightsOn(), "first window only validates");
            controller.onDiChange(new IoInputDiChange(2, false));
            controller.awaitLightTasks(500);
            assertEquals(1, offs.get());
            controller.onDiChange(new IoInputDiChange(2, true));
            cameraPair(controller, 0, 200);
            controller.awaitLightTasks(500);
            assertTrue(controller.lightsOn(), "camera 1 has not finished");
            assertEquals(1, offs.get());
            cameraPair(controller, 1, 200);
            controller.awaitLightTasks(500);
            assertFalse(controller.lightsOn());
            assertFalse(controller.captureLightingActive());
            assertEquals(2, offs.get(), "one early bank Off");
            controller.onDiChange(new IoInputDiChange(2, false));
            controller.onDiChange(new IoInputDiChange(2, true));
            // Old events delivered after the next rising edge cannot turn off its light.
            controller.onCameraEvent(new NativeCameraEvents.Event(0, 2, 210, 1, 0));
            controller.awaitLightTasks(500);
            assertTrue(controller.lightsOn());
            controller.onDiChange(new IoInputDiChange(2, false));
            controller.awaitLightTasks(500);
            assertFalse(controller.lightsOn(), "missing end uses the DI falling fallback");
        }
    }

    private void cameraPair(IntervalFlashController controller, int camera, long tick) {
        controller.onCameraEvent(new NativeCameraEvents.Event(camera, 1, tick, System.nanoTime(), 0));
        controller.onCameraEvent(new NativeCameraEvents.Event(camera, 2, tick + 10, System.nanoTime(), 0));
    }

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
                delayedConfig(5000, false)
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
