package com.example.iml.orchestrator.supervisor;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StackSupervisorMainTest {

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
    void destroyProcessTreeTerminatesChild() throws Exception {
        Path script = Files.createTempFile("supervisor-child-", ".sh");
        script.toFile().deleteOnExit();
        Files.writeString(script, "#!/bin/sh\nsleep 30\n");
        script.toFile().setExecutable(true);

        Process child = new ProcessBuilder(script.toString()).start();
        assertTrue(child.isAlive());
        StackSupervisorMain.destroyProcessTree(child, true);
        assertTrue(child.waitFor(5, TimeUnit.SECONDS));
        assertFalse(child.isAlive());
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
