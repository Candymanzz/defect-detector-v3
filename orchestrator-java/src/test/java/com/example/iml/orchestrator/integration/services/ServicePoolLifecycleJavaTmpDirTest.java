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
        assertTrue(out.contains("-Xms32m"));
        assertTrue(out.contains("-Xmx256m"));
        assertTrue(out.contains("-XX:+ExitOnOutOfMemoryError"));
        assertTrue(out.stream().anyMatch(arg -> arg.startsWith("-Djava.io.tmpdir=") && arg.contains("java-geometry-3")));
        assertEquals("-jar", out.get(out.size() - 2));
        assertEquals("svc.jar", out.get(out.size() - 1));
    }

    @Test
    void keepsExistingJavaIoTmpDir() throws Exception {
        List<String> cmd = List.of("java", "-Djava.io.tmpdir=C:/custom", "-jar", "svc.jar");
        List<String> out = ServicePoolLifecycle.withUniqueJavaIoTmpDir(cmd, "java-geometry-0", tempDir);
        assertTrue(out.contains("-Djava.io.tmpdir=C:/custom"));
        assertTrue(out.contains("-Xmx256m"));
    }

    @Test
    void preservesExplicitJavaMemoryLimits() throws Exception {
        List<String> cmd = List.of("java", "-Xms16m", "-Xmx128m", "-jar", "svc.jar");
        List<String> out = ServicePoolLifecycle.withUniqueJavaIoTmpDir(cmd, "java-positioning-0", tempDir);
        assertTrue(out.contains("-Xms16m"));
        assertTrue(out.contains("-Xmx128m"));
        assertEquals(1, out.stream().filter(arg -> arg.startsWith("-Xms")).count());
        assertEquals(1, out.stream().filter(arg -> arg.startsWith("-Xmx")).count());
    }

    @Test
    void leavesNonJavaCommandsUntouched() throws Exception {
        List<String> cmd = List.of("python", "-m", "app");
        assertEquals(cmd, ServicePoolLifecycle.withUniqueJavaIoTmpDir(cmd, "py-0", tempDir));
    }
}
