package com.example.iml.orchestrator.integration.capture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FrameJpegWriterPathTest {
    @TempDir Path temp;

    @Test void posixNameResolvesExactFrozenBufferBeforeCameraFallback() throws Exception {
        String name = "iml_ui_inspect_test_" + UUID.randomUUID();
        Path buffer = FrameJpegWriter.imlShmFilePath(name);
        Files.createDirectories(buffer.getParent());
        Files.write(buffer, new byte[] {1, 2, 3});
        try {
            assertEquals(buffer, FrameJpegWriter.resolveShmPath("/" + name, 0));
            assertEquals(buffer, FrameJpegWriter.resolveShmPath("/" + name, -1));
        } finally {
            Files.deleteIfExists(buffer);
        }
    }

    @Test void realAbsoluteFilePathStillResolvesDirectly() throws Exception {
        Path buffer = Files.write(temp.resolve("frame.bin"), new byte[] {1, 2, 3});
        assertEquals(buffer, FrameJpegWriter.resolveShmPath(buffer.toString(), 0));
    }
}
