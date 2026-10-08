package com.example.iml.orchestrator.supervisor;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StackSupervisorHealthConfigTest {

    @Test
    void resolveStackHealthUrisIncludesOrchestratorAndPythonByDefault() {
        URI orch = URI.create("http://127.0.0.1:8099/health");
        List<URI> uris = StackSupervisorMain.resolveStackHealthUris(orch);
        assertEquals(2, uris.size());
        assertEquals(orch, uris.get(0));
        assertEquals(URI.create("http://127.0.0.1:8000/detector/health"), uris.get(1));
    }

    @Test
    void windowsRebootEscalationEnabledByDefaultOnWindows() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        assertEquals(windows, WindowsRebootEscalation.enabledByDefault());
    }

    @Test
    void scheduleRebootReturnsFalseOnNonWindows() {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return;
        }
        assertTrue(!WindowsRebootEscalation.scheduleReboot(30, "test"));
    }
}
