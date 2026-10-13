package com.example.iml.orchestrator.integration.ui;

import com.example.iml.orchestrator.integration.capture.FrameJpegWriter;
import com.example.iml.orchestrator.integration.capture.ImlShmJanitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiArtifactsSidecarSnapshotTest {

    @TempDir
    Path directory;

    @Test
    void jpegPublisherCanReadSnapshotByItsLogicalShmName() throws Exception {
        Path source = directory.resolve("source.bgr");
        Files.write(source, new byte[]{10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120});
        var snapshot = UiArtifactsSidecar.snapshotInspectionFrame(
                source, 0, 12, ImlShmJanitor.imlShmDirectory(), "iml_ui_inspect_test_");
        UiHttpServer.InspectionPreviewArtifacts previews = null;
        try {
            Files.delete(source);
            previews = UiHttpServer.writeInspectionJpegsFromBgrShm(
                    snapshot.shmName(), 2, 2, 6, 0, 2, 0.9f, 1, 0.8f, 0.9f);

            for (var artifact : List.of(previews.frame(), previews.card(), previews.archive())) {
                assertNull(artifact.error(), artifact.error());
                assertNotNull(artifact.path());
                var image = ImageIO.read(artifact.path().toFile());
                assertNotNull(image);
                assertEquals(artifact.width(), image.getWidth());
                assertEquals(artifact.height(), image.getHeight());
            }
            assertEquals(snapshot.path(), FrameJpegWriter.resolveShmPath(snapshot.shmName(), -1));
            assertEquals(2, previews.archive().width());
            assertEquals(1, previews.card().width());
        } finally {
            Files.deleteIfExists(snapshot.path());
            if (previews != null) {
                for (var artifact : List.of(previews.frame(), previews.card(), previews.archive())) {
                    if (artifact.path() != null) Files.deleteIfExists(artifact.path());
                }
            }
        }
    }

    @Test
    void queuedFramesKeepTheirOwnBytesWhenPositionedBufferIsReused() throws Exception {
        Path source = directory.resolve("iml_pos_cam_0.bin");
        byte[] firstFrame = {1, 2, 3, 4, 5, 6};
        byte[] secondFrame = {7, 8, 9, 10, 11, 12};
        Files.write(source, firstFrame);
        var first = UiArtifactsSidecar.snapshotInspectionFrame(source, 0, 6, directory, "iml_ui_inspect_cam_0_");

        Files.write(source, secondFrame);
        var second = UiArtifactsSidecar.snapshotInspectionFrame(source, 0, 6, directory, "iml_ui_inspect_cam_0_");
        Files.delete(source);

        assertNotEquals(first.path(), second.path());
        assertNotEquals(first.shmName(), second.shmName());
        assertArrayEquals(firstFrame, Files.readAllBytes(first.path()));
        assertArrayEquals(secondFrame, Files.readAllBytes(second.path()));
        assertTrue(first.deleteWhenDone());
        assertTrue(second.deleteWhenDone());
    }

    @Test
    void snapshotsOnlyTheCapturedRingSlice() throws Exception {
        Path source = directory.resolve("ring.bin");
        Files.write(source, new byte[]{99, 98, 1, 2, 3, 97});

        var snapshot = UiArtifactsSidecar.snapshotInspectionFrame(source, 2, 3, directory, "iml_ui_inspect_cam_0_");

        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(snapshot.path()));
    }

    @Test
    void deletesIncompleteSnapshotWhenSourceIsTooSmall() throws Exception {
        Path source = directory.resolve("ring.bin");
        Files.write(source, new byte[]{1, 2, 3});

        assertThrows(java.io.IOException.class, () ->
                UiArtifactsSidecar.snapshotInspectionFrame(source, 1, 3, directory, "iml_ui_inspect_cam_0_"));

        try (var files = Files.list(directory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().startsWith("iml_ui_inspect_cam_0_")));
        }
    }
}
