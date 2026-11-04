package com.example.iml.orchestrator.supervisor;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StackSupervisorMainTest {

    @Test void orderlyExitStopsSupervisorButFailuresStillRecover() {
        assertTrue(StackSupervisorMain.isOrderlyExit(new StackSupervisorMain.MonitorResult("process-exited", 0, 0)));
        assertFalse(StackSupervisorMain.isOrderlyExit(new StackSupervisorMain.MonitorResult("process-exited", 2, 0)));
        assertFalse(StackSupervisorMain.isOrderlyExit(new StackSupervisorMain.MonitorResult("health-failed", 0, 0)));
    }

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void probeHealthReturnsTrueForOkEndpoint() throws Exception {
        int port = startHealthServer("ok\n");
        assertTrue(StackSupervisorMain.probeHealth(URI.create("http://127.0.0.1:" + port + "/health"), 2000));
    }

    @Test
    void probeHealthReturnsFalseForMissingServer() {
        assertFalse(StackSupervisorMain.probeHealth(URI.create("http://127.0.0.1:1/health"), 500));
    }

    @Test
    void isProcessAliveReturnsFalseForInvalidPid() {
        assertFalse(StackSupervisorMain.isProcessAlive(-1));
        assertFalse(StackSupervisorMain.isProcessAlive(999999999L));
    }

    @Test
    void destroyProcessTreeTerminatesChild() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                SleepingChild.class.getName()).start();
        try {
            assertEquals("ready", child.inputReader(StandardCharsets.UTF_8).readLine());
            assertTrue(child.isAlive());
            StackSupervisorMain.destroyProcessTree(child, true);
            assertTrue(child.waitFor(5, TimeUnit.SECONDS));
            assertFalse(child.isAlive());
        } finally {
            child.destroyForcibly();
        }
    }

    public static final class SleepingChild {
        public static void main(String[] args) throws InterruptedException {
            System.out.println("ready");
            Thread.sleep(30_000L);
        }
    }

    private int startHealthServer(String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return server.getAddress().getPort();
    }
}
