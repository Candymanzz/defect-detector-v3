package com.example.iml.orchestrator.integration.capture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImlShmJanitorTest {

    @Test
    void recognizesDedicatedOrchestratorBuffers() {
        assertTrue(ImlShmJanitor.isDedicatedOrchestratorBuffer("iml_ds_cur_cam0"));
        assertTrue(ImlShmJanitor.isDedicatedOrchestratorBuffer("iml_py_ds_ref_cam1"));
        assertTrue(ImlShmJanitor.isDedicatedOrchestratorBuffer("iml_ui_heatmap_cam_2"));
        assertTrue(ImlShmJanitor.isDedicatedOrchestratorBuffer("iml_pos_cam_3"));
        assertFalse(ImlShmJanitor.isDedicatedOrchestratorBuffer("iml_cam_0_frame"));
        assertFalse(ImlShmJanitor.isDedicatedOrchestratorBuffer(null));
    }

    @Test
    void phaseBuffersAreSeparatePerPhaseAndSurviveStartupPurge() {
        assertEquals("", ImlShmJanitor.phaseSuffix(0));
        assertEquals("_p1", ImlShmJanitor.phaseSuffix(1));
        assertTrue(ImlShmJanitor.isRetainedAtStartup("iml_pos_cam_3_p1"));
        assertTrue(ImlShmJanitor.isRetainedAtStartup("iml_ui_inspect_cam_3_p1"));
        assertTrue(ImlShmJanitor.isDedicatedOrchestratorBuffer("iml_pos_cam_3_p1"));
        assertFalse(ImlShmJanitor.isEphemeralLinePin("iml_pos_cam_3_p1"));
    }

    @Test
    void recognizesEphemeralLinePins() {
        assertTrue(ImlShmJanitor.isEphemeralLinePin("iml_line_pin_cam5_f42"));
        assertTrue(ImlShmJanitor.isEphemeralLinePin("/iml_line_pin_cam0_f1"));
        assertFalse(ImlShmJanitor.isEphemeralLinePin("iml_ref_cam5"));
        assertTrue(ImlShmJanitor.isRetainedAtStartup("iml_ref_phase0_cam0"));
        assertTrue(ImlShmJanitor.isRetainedAtStartup("iml_ref_phase1_cam9"));
        assertFalse(ImlShmJanitor.isRetainedAtStartup("iml_line_pin_cam0_f28"));
        assertFalse(ImlShmJanitor.isEphemeralLinePin("iml_pos_cam_5"));
        assertFalse(ImlShmJanitor.isEphemeralLinePin("iml_cam_5_frame"));
    }

    @Test
    void releaseEphemeralCaptureBuffersDeletesLinePinsOnly() throws Exception {
        Path pin = FrameJpegWriter.imlShmFilePath("iml_line_pin_cam90_f7");
        Path ref = FrameJpegWriter.imlShmFilePath("iml_ref_cam90");
        Path ring = FrameJpegWriter.imlShmFilePath("iml_cam_90_frame");
        Path parent = pin.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(pin, new byte[] {1, 2, 3});
        Files.write(ref, new byte[] {4, 5, 6});
        Files.write(ring, new byte[] {7, 8, 9});

        ImlShmJanitor.releaseEphemeralCaptureBuffers(
                Map.of(
                        "shm_name", "/iml_pos_cam_90",
                        "original_shm_name", "/iml_line_pin_cam90_f7",
                        "camera_id", 90
                ),
                null
        );

        assertFalse(Files.exists(pin));
        assertTrue(Files.isRegularFile(ref));
        assertTrue(Files.isRegularFile(ring));
        Files.deleteIfExists(ref);
        Files.deleteIfExists(ring);
    }

    @Test
    void purgeEphemeralOlderThanDeletesStaleLinePinsOnly() throws Exception {
        Path oldestPin = FrameJpegWriter.imlShmFilePath("iml_line_pin_cam90_f97");
        Path phase0Pin = FrameJpegWriter.imlShmFilePath("iml_line_pin_cam90_f98");
        Path phase1Pin = FrameJpegWriter.imlShmFilePath("iml_line_pin_cam90_f99");
        Path ref = FrameJpegWriter.imlShmFilePath("iml_ref_cam91");
        Path parent = oldestPin.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(oldestPin, new byte[] {1});
        Files.write(phase0Pin, new byte[] {2});
        Files.write(phase1Pin, new byte[] {3});
        Files.write(ref, new byte[] {4, 5, 6});
        long now = System.currentTimeMillis();
        Files.setLastModifiedTime(oldestPin, java.nio.file.attribute.FileTime.fromMillis(now - 62_000L));
        Files.setLastModifiedTime(phase0Pin, java.nio.file.attribute.FileTime.fromMillis(now - 61_000L));
        Files.setLastModifiedTime(phase1Pin, java.nio.file.attribute.FileTime.fromMillis(now - 60_000L));

        ImlShmJanitor.purgeEphemeralOlderThan(java.time.Duration.ofSeconds(30), null);

        assertFalse(Files.exists(oldestPin));
        assertTrue(Files.isRegularFile(phase0Pin));
        assertTrue(Files.isRegularFile(phase1Pin));
        assertTrue(Files.isRegularFile(ref));
        Files.deleteIfExists(phase0Pin);
        Files.deleteIfExists(phase1Pin);
        Files.deleteIfExists(ref);
    }

    @Test
    void purgeEphemeralOlderThanKeepsFreshLinePins() throws Exception {
        Path pin = FrameJpegWriter.imlShmFilePath("iml_line_pin_cam90_f100");
        Path parent = pin.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(pin, new byte[] {1, 2, 3});
        Files.setLastModifiedTime(pin, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));

        ImlShmJanitor.purgeEphemeralOlderThan(java.time.Duration.ofSeconds(30), null);

        assertTrue(Files.isRegularFile(pin));
        Files.deleteIfExists(pin);
    }

    @Test
    void imlShmDirectoryResolvesParent() {
        assertNotNull(ImlShmJanitor.imlShmDirectory());
    }
}
