package com.example.iml.orchestrator.integration.trigger.config;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GpioTriggerConfigTest {

    @Test
    void parsesHikrobotMvIoBackend() {
        Map<String, Object> integration = new LinkedHashMap<>();
        Map<String, Object> trigger = new LinkedHashMap<>();
        Map<String, Object> gpio = new LinkedHashMap<>();
        gpio.put("enabled", true);
        gpio.put("backend", "hikrobot_mv_io");
        gpio.put("com_port", "COM2");
        gpio.put("work_port", 1);
        gpio.put("direction_port", 2);
        gpio.put("trigger_port", 3);
        gpio.put("poll_interval_ms", 5);
        trigger.put("gpio", gpio);
        integration.put("inspection_trigger", trigger);

        GpioTriggerConfig cfg = GpioTriggerConfig.parse(integration);
        assertTrue(cfg.enabled());
        assertEquals("hikrobot_mv_io", cfg.backend());
        assertEquals("COM2", cfg.comPort());
        assertEquals(1, cfg.workPort());
        assertEquals(2, cfg.directionPort());
        assertEquals(3, cfg.triggerPort());
        assertEquals(5, cfg.pollIntervalMs());
        assertTrue(cfg.fullyConfigured());
    }

    @Test
    void parsesSysfsBackend() {
        Map<String, Object> integration = new LinkedHashMap<>();
        Map<String, Object> trigger = new LinkedHashMap<>();
        Map<String, Object> gpio = new LinkedHashMap<>();
        gpio.put("enabled", true);
        gpio.put("backend", "sysfs");
        gpio.put("work", Map.of("path", "/sys/a"));
        gpio.put("direction", Map.of("path", "/sys/b"));
        gpio.put("trigger", Map.of("path", "/sys/c"));
        trigger.put("gpio", gpio);
        integration.put("inspection_trigger", trigger);

        GpioTriggerConfig cfg = GpioTriggerConfig.parse(integration);
        assertEquals("sysfs", cfg.backend());
        assertTrue(cfg.fullyConfigured());
    }

    @Test
    void disabledByDefault() {
        GpioTriggerConfig cfg = GpioTriggerConfig.parse(Map.of());
        assertFalse(cfg.enabled());
    }
}
