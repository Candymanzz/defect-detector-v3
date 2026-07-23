package com.example.iml.orchestrator.integration.trigger;

import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IoInputMonitorRejectClientTest {

    @Test
    void fromIntegrationReadsDoEnableFlagsAndPlcWiring() {
        Map<String, Object> integration = new LinkedHashMap<>();
        integration.put("io_input_monitor_reject", Map.of(
                "enabled", true,
                "url", "http://127.0.0.1:9101",
                "ready_enabled", true,
                "fault_enabled", false,
                "line1_enabled", false,
                "line2_enabled", true,
                "ready_output_port", 1,
                "line2_output_port", 7,
                "line2_plc_input", "X99"
        ));

        IoInputMonitorRejectClient client = IoInputMonitorRejectClient.fromIntegration(
                LogManager.getLogger(IoInputMonitorRejectClientTest.class),
                integration
        );

        assertTrue(client.isEnabled());
        assertTrue(client.readyEnabled());
        assertFalse(client.faultEnabled());
        assertFalse(client.line1Enabled());
        assertTrue(client.line2Enabled());
    }

    @Test
    void defaultsDisableReadyAndFaultEnableRejectLines() {
        Map<String, Object> integration = new LinkedHashMap<>();
        integration.put("io_input_monitor_reject", Map.of(
                "enabled", true,
                "url", "http://127.0.0.1:9101"
        ));

        IoInputMonitorRejectClient client = IoInputMonitorRejectClient.fromIntegration(
                LogManager.getLogger(IoInputMonitorRejectClientTest.class),
                integration
        );

        assertFalse(client.readyEnabled());
        assertFalse(client.faultEnabled());
        assertTrue(client.line1Enabled());
        assertTrue(client.line2Enabled());
    }
}
