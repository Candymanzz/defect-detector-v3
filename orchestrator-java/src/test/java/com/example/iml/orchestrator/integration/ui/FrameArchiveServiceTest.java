package com.example.iml.orchestrator.integration.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameArchiveServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void savesFramesAndTrimsToConfiguredLimit() throws Exception {
        FrameArchiveConfig config = new FrameArchiveConfig(true, tempDir, 2, 10);
        FrameArchiveService archive = FrameArchiveService.open(config);
        try {
            archive.setMaxFramesPerCamera(2);
            for (long frameId = 1; frameId <= 3; frameId++) {
                Path frame = Files.write(tempDir.resolve("source-" + frameId + ".jpg"), new byte[]{(byte) frameId});
                assertTrue(archive.saveImmediately(new FrameArchiveService.SaveRequest(
                        0,
                        frameId,
                        frameId + 100,
                        "product",
                        "detector",
                        new com.example.iml.orchestrator.integration.pipeline.InspectionDecision(
                                0,
                                frameId,
                                frameId % 2 == 0,
                                "inspect",
                                0.1,
                                "OK",
                                "OK"
                        ),
                        frame,
                        null,
                        0,
                        0
                )));
            }

            var history = archive.listHistory(0);
            assertEquals(2, history.size());
            assertEquals(3L, history.get(0).frameId());
            assertEquals(2L, history.get(1).frameId());
            assertTrue(Files.isRegularFile(tempDir.resolve("camera_0/f_0000003/frame.jpg")));
            assertTrue(Files.isRegularFile(tempDir.resolve("camera_0/f_0000003/result.json")));
            assertFalse(Files.exists(tempDir.resolve("camera_0/f_0000001")));
        } finally {
            archive.close();
        }
    }

    @Test
    void persistsMaxFramesSettingAcrossRestart() throws Exception {
        FrameArchiveConfig config = new FrameArchiveConfig(true, tempDir, 20, 100);
        FrameArchiveService first = FrameArchiveService.open(config);
        first.setMaxFramesPerCamera(37);
        first.close();

        FrameArchiveService second = FrameArchiveService.open(config);
        try {
            assertEquals(37, second.maxFramesPerCamera());
        } finally {
            second.close();
        }
    }

    @Test
    void loweringMaxFramesTrimsExistingArchive() throws Exception {
        FrameArchiveConfig config = new FrameArchiveConfig(true, tempDir, 5, 10);
        FrameArchiveService archive = FrameArchiveService.open(config);
        try {
            archive.setMaxFramesPerCamera(5);
            for (long frameId = 1; frameId <= 4; frameId++) {
                Path frame = Files.write(tempDir.resolve("source-" + frameId + ".jpg"), new byte[]{(byte) frameId});
                archive.scheduleSave(new FrameArchiveService.SaveRequest(
                        0,
                        frameId,
                        frameId,
                        "product",
                        "detector",
                        null,
                        frame,
                        null,
                        0,
                        0
                ));
            }
            awaitArchiveIdle(archive, 4);
            assertEquals(4, archive.listHistory(0).size());

            archive.setMaxFramesPerCamera(2);
            assertEquals(2, archive.listHistory(0).size());
            assertFalse(Files.exists(tempDir.resolve("camera_0/f_0000001")));
            assertFalse(Files.exists(tempDir.resolve("camera_0/f_0000002")));
            assertTrue(Files.isRegularFile(tempDir.resolve("camera_0/f_0000003/frame.jpg")));
            assertTrue(Files.isRegularFile(tempDir.resolve("camera_0/f_0000004/frame.jpg")));
        } finally {
            archive.close();
        }
    }

    @Test
    void trimsOldestBySavedAtWhenLimitReached() throws Exception {
        FrameArchiveConfig config = new FrameArchiveConfig(true, tempDir, 2, 10);
        FrameArchiveService archive = FrameArchiveService.open(config);
        try {
            archive.setMaxFramesPerCamera(2);
            // Low frame ids saved later must keep; high frame id saved earlier must be dropped.
            Path oldHigh = Files.write(tempDir.resolve("old-high.jpg"), new byte[]{1});
            assertTrue(archive.saveImmediately(new FrameArchiveService.SaveRequest(
                    0, 90, 1, "p", "d", null, oldHigh, null, 0, 0)));
            Thread.sleep(5);
            Path newerLow = Files.write(tempDir.resolve("new-low.jpg"), new byte[]{2});
            assertTrue(archive.saveImmediately(new FrameArchiveService.SaveRequest(
                    0, 1, 2, "p", "d", null, newerLow, null, 0, 0)));
            Thread.sleep(5);
            Path newest = Files.write(tempDir.resolve("newest.jpg"), new byte[]{3});
            assertTrue(archive.saveImmediately(new FrameArchiveService.SaveRequest(
                    0, 2, 3, "p", "d", null, newest, null, 0, 0)));

            var history = archive.listHistory(0);
            assertEquals(2, history.size());
            assertEquals(2L, history.get(0).frameId());
            assertEquals(1L, history.get(1).frameId());
            assertFalse(Files.exists(tempDir.resolve("camera_0/f_0000090")));
            assertTrue(history.get(0).savedAtEpochMs() >= history.get(1).savedAtEpochMs());
        } finally {
            archive.close();
        }
    }

    @Test
    void deleteFrameRemovesDirectory() throws Exception {
        FrameArchiveConfig config = new FrameArchiveConfig(true, tempDir, 5, 10);
        FrameArchiveService archive = FrameArchiveService.open(config);
        try {
            Path frame = Files.write(tempDir.resolve("del.jpg"), new byte[]{4});
            assertTrue(archive.saveImmediately(new FrameArchiveService.SaveRequest(
                    3, 7, 7, "p", "d", null, frame, null, 0, 0)));
            assertTrue(archive.deleteFrame(3, 7));
            assertFalse(Files.exists(tempDir.resolve("camera_3/f_0000007")));
            assertEquals(0, archive.listHistory(3).size());
        } finally {
            archive.close();
        }
    }

    @Test
    void scheduleSaveSurvivesSourceDeletion() throws Exception {
        FrameArchiveConfig config = new FrameArchiveConfig(true, tempDir, 5, 10);
        FrameArchiveService archive = FrameArchiveService.open(config);
        try {
            Path frame = Files.write(tempDir.resolve("ephemeral.jpg"), new byte[]{9, 8, 7});
            Path heatmap = Files.write(tempDir.resolve("ephemeral.u8"), new byte[]{1, 2});
            archive.scheduleSave(new FrameArchiveService.SaveRequest(
                    1,
                    42,
                    100,
                    "product",
                    "detector",
                    null,
                    frame,
                    heatmap,
                    2,
                    1
            ));
            Files.deleteIfExists(frame);
            Files.deleteIfExists(heatmap);
            awaitArchiveIdle(archive, 1, 1);

            assertTrue(Files.isRegularFile(tempDir.resolve("camera_1/f_0000042/frame.jpg")));
            assertTrue(Files.isRegularFile(tempDir.resolve("camera_1/f_0000042/heatmap.u8")));
            assertTrue(Files.isRegularFile(tempDir.resolve("camera_1/f_0000042/result.json")));
            assertEquals(1, archive.listHistory(1).size());
        } finally {
            archive.close();
        }
    }

    @Test
    void infersMissingLegacyHeatmapDimensionsFromFileAndFrameAspect() throws Exception {
        Path frameDir = tempDir.resolve("camera_2/f_0000007");
        Files.createDirectories(frameDir);
        BufferedImage frame = new BufferedImage(4, 3, BufferedImage.TYPE_3BYTE_BGR);
        ImageIO.write(frame, "jpg", frameDir.resolve("frame.jpg").toFile());
        Files.write(frameDir.resolve("heatmap.u8"), new byte[12]);
        Files.writeString(frameDir.resolve("result.json"), """
                {
                  "frame_id": "7",
                  "inspection_id": "7",
                  "saved_at_ms": 1000,
                  "heatmap": {"width": 0, "height": 0}
                }
                """);

        FrameArchiveService archive = FrameArchiveService.open(new FrameArchiveConfig(true, tempDir, 5, 10));
        try {
            var history = archive.listHistory(2);
            assertEquals(1, history.size());
            assertTrue(history.get(0).hasHeatmap());
            assertEquals(4, history.get(0).heatmapWidth());
            assertEquals(3, history.get(0).heatmapHeight());
        } finally {
            archive.close();
        }
    }

    private static void awaitArchiveIdle(FrameArchiveService archive, int cameraId, int minFrames) throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            if (archive.listHistory(cameraId).size() >= minFrames) {
                return;
            }
            Thread.sleep(20);
        }
    }

    private static void awaitArchiveIdle(FrameArchiveService archive, int minFrames) throws Exception {
        awaitArchiveIdle(archive, 0, minFrames);
    }
}
