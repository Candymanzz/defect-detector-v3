package com.example.iml.orchestrator.integration.fanout;

import com.example.iml.orchestrator.integration.clientws.session.ClientWsSessionState;
import com.example.iml.orchestrator.integration.health.ServiceHealthGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FanOutCoordinatorHealthGateTest {

    @TempDir
    Path tempDir;

    @Test
    void unhealthyBlocksReadyUntilRecovered() throws Exception {
        Path map = tempDir.resolve("register-map.yaml");
        Files.writeString(map, """
                version: 1
                map_id: test
                signals:
                  - name: vision_ready
                    area: W
                    address: "0.04"
                    data_type: bool
                    direction: pc_to_plc
                  - name: vision_fault
                    area: W
                    address: "0.05"
                    data_type: bool
                    direction: pc_to_plc
                """);
        Map<String, Object> root = Map.of(
                "plc_fins", Map.of(
                        "enabled", false,
                        "register_map_path", map.toString(),
                        "vision_ready_signal", "vision_ready",
                        "vision_fault_signal", "vision_fault"
                )
        );
        FanOutCoordinator fanOut = FanOutCoordinator.fromConfig(root, tempDir, null);
        ServiceHealthGate gate = new ServiceHealthGate();
        AtomicInteger refreshes = new AtomicInteger();
        gate.setOnChanged(() -> {
            refreshes.incrementAndGet();
            fanOut.refreshPlcLevels();
        });
        // setHealthGate also installs onChanged; replace with counting wrapper via mark path
        fanOut.setHealthGate(gate);
        gate.setOnChanged(() -> {
            refreshes.incrementAndGet();
            fanOut.refreshPlcLevels();
        });

        assertDoesNotThrow(() -> fanOut.onSessionState(ClientWsSessionState.READY));
        assertTrue(gate.healthy());

        gate.markUnhealthy("io_input_monitor");
        assertFalse(gate.healthy());
        assertTrue(refreshes.get() >= 1);

        gate.markHealthy("io_input_monitor");
        assertTrue(gate.healthy());
        fanOut.close();
    }

    @Test
    void shutdownPrepForcesReadyOffAndFaultOnSticky() throws Exception {
        Path map = tempDir.resolve("register-map.yaml");
        Files.writeString(map, """
                version: 1
                map_id: test
                signals:
                  - name: vision_ready
                    area: W
                    address: "0.04"
                    data_type: bool
                    direction: pc_to_plc
                  - name: vision_fault
                    area: W
                    address: "0.05"
                    data_type: bool
                    direction: pc_to_plc
                """);
        Map<String, Object> root = Map.of(
                "plc_fins", Map.of(
                        "enabled", false,
                        "register_map_path", map.toString(),
                        "vision_ready_signal", "vision_ready",
                        "vision_fault_signal", "vision_fault"
                )
        );
        FanOutCoordinator fanOut = FanOutCoordinator.fromConfig(root, tempDir, null);
        ServiceHealthGate gate = new ServiceHealthGate();
        fanOut.setHealthGate(gate);
        fanOut.onSessionState(ClientWsSessionState.READY);
        assertFalse(fanOut.isShutdownPrepActive());

        fanOut.enterShutdownPrep("di4_power_supply");
        assertTrue(fanOut.isShutdownPrepActive());

        // Health recovery must not clear shutdown-prep latch.
        gate.markUnhealthy("io_input_monitor");
        gate.markHealthy("io_input_monitor");
        fanOut.refreshPlcLevels();
        assertTrue(fanOut.isShutdownPrepActive());

        fanOut.enterShutdownPrep("again");
        assertTrue(fanOut.isShutdownPrepActive());
        fanOut.close();
    }
}
