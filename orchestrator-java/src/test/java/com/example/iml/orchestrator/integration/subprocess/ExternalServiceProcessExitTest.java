package com.example.iml.orchestrator.integration.subprocess;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
class ExternalServiceProcessExitTest {

    @Test
    void closeDoesNotFireUnexpectedExit() throws Exception {
        ExternalServiceProcess process = ExternalServiceProcess.start(
                "exit-test-close",
                List.of("cmd.exe", "/c", "ping -n 30 127.0.0.1 >nul"),
                Path.of(".")
        );
        AtomicBoolean unexpected = new AtomicBoolean(false);
        process.onUnexpectedExit(() -> unexpected.set(true));
        process.close();
        Thread.sleep(400);
        assertFalse(unexpected.get());
        assertTrue(process.isClosing());
    }

    @Test
    void unexpectedExitFiresListener() throws Exception {
        ExternalServiceProcess process = ExternalServiceProcess.start(
                "exit-test-unexpected",
                List.of("cmd.exe", "/c", "exit /b 0"),
                Path.of(".")
        );
        CountDownLatch latch = new CountDownLatch(1);
        process.onUnexpectedExit(latch::countDown);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertFalse(process.isClosing());
    }
}
