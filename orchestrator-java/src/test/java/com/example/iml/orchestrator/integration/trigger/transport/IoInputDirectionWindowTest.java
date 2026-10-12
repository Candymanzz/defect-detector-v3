package com.example.iml.orchestrator.integration.trigger.transport;
import com.example.iml.orchestrator.integration.capture.DirectionCaptureWindow;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerBus;
import com.example.iml.orchestrator.integration.trigger.config.*;
import com.example.iml.orchestrator.integration.trigger.parse.IoInputDiChange;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class IoInputDirectionWindowTest {
    @Test void opensBeforeDi3ClosesAtDirectionFallAndRejectsOldTokens() throws Exception {
        var cfg = IoInputDiscreteConfig.parse(Map.of("inspection_trigger", Map.of("io_input", Map.of(
            "di3_only", true, "direction_latch", false, "external_hardware_capture", true,
            "require_direction", true, "require_work", false))), 0);
        try (var bus = new InspectionTriggerBus(List.of(0));
             var t = new IoInputTriggerTransport(LogManager.getLogger(getClass()), UdpTriggerConfig.defaults(), cfg, bus, () -> {})) {
            t.setHardwareObservationSource(); t.setDirectionWindowCapture();
            t.applyDiChange(new IoInputDiChange(2, true));
            assertEquals(1, bus.lastDispatchedSequence());
            var first = DirectionCaptureWindow.command(DirectionCaptureWindow.token(1));
            Path gate = Path.of(first.get("capture_window_path").toString());
            assertEquals(first.get("capture_window_token"), Files.readString(gate));
            t.applyDiChange(new IoInputDiChange(2, true));
            t.applyDiChange(new IoInputDiChange(3, true));
            assertEquals(1, bus.lastDispatchedSequence());
            t.applyDiChange(new IoInputDiChange(2, false));
            assertEquals("0", Files.readString(gate));
            t.applyDiChange(new IoInputDiChange(3, true));
            assertEquals(1, bus.lastDispatchedSequence());
            t.applyDiChange(new IoInputDiChange(2, true));
            assertEquals(2, bus.lastDispatchedSequence());
            assertNotEquals(first.get("capture_window_token"), Files.readString(gate));
            t.applyDiChange(null);
            assertEquals("0", Files.readString(gate));
        }
        assertFalse(DirectionCaptureWindow.enabled());
    }
}
