package com.example.iml.orchestrator.integration.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceTraceSessionTest {

    @TempDir
    Path projectRoot;

    @Test
    void startsDistinctSessionsAndPassesTheirDirectoryToChildren() throws Exception {
        Path first = ServiceTraceSession.start(projectRoot);
        Path second = ServiceTraceSession.start(projectRoot);

        assertNotEquals(first, second);
        assertEquals(projectRoot.resolve("logs/service-traces"), second.getParent());
        for (String service : new String[]{"java-positioning", "java-geometry", "analisSurface"}) {
            assertTrue(Files.isDirectory(second.resolve(service)));
        }
        assertTrue(Files.readString(second.resolve("session.txt")).contains("session_dir=" + second));

        Map<String, String> childEnvironment = new HashMap<>();
        ServiceTraceSession.addToChildEnvironment(childEnvironment);
        assertEquals(second.toString(), childEnvironment.get(ServiceTraceSession.ENV_NAME));
    }
}
