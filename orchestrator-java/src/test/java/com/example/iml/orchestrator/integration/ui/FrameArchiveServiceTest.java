package com.example.iml.orchestrator.integration.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
                archive.scheduleSave(new FrameArchiveService.SaveRequest(
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
                ));
            }
            awaitArchiveIdle(archive, 2);

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

    private static void awaitArchiveIdle(FrameArchiveService archive, int minFrames) throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            if (archive.listHistory(0).size() >= minFrames) {
                return;
            }
            Thread.sleep(20);
        }
    }
}
