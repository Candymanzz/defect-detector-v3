package com.example.iml.orchestrator.integration.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServicePoolLifecycleJavaTmpDirTest {

    @TempDir
    Path tempDir;

    @Test
    void injectsUniqueJavaIoTmpDirBeforeJar() throws Exception {
        List<String> cmd = List.of("java", "-jar", "svc.jar");
        List<String> out = ServicePoolLifecycle.withUniqueJavaIoTmpDir(cmd, "java-geometry-3", tempDir);

        assertEquals("java", out.get(0));
        assertTrue(out.get(1).startsWith("-Djava.io.tmpdir="));
        assertTrue(out.get(1).contains("java-geometry-3"));
        assertEquals("-jar", out.get(2));
        assertEquals("svc.jar", out.get(3));
    }

    @Test
    void keepsExistingJavaIoTmpDir() throws Exception {
        List<String> cmd = List.of("java", "-Djava.io.tmpdir=C:/custom", "-jar", "svc.jar");
        List<String> out = ServicePoolLifecycle.withUniqueJavaIoTmpDir(cmd, "java-geometry-0", tempDir);
        assertEquals(cmd, out);
    }

    @Test
    void leavesNonJavaCommandsUntouched() throws Exception {
        List<String> cmd = List.of("python", "-m", "app");
        assertEquals(cmd, ServicePoolLifecycle.withUniqueJavaIoTmpDir(cmd, "py-0", tempDir));
    }
}
