package com.example.iml.orchestrator.integration.capture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

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
    void recognizesEphemeralLinePins() {
        assertTrue(ImlShmJanitor.isEphemeralLinePin("iml_line_pin_cam5_f42"));
        assertTrue(ImlShmJanitor.isEphemeralLinePin("/iml_line_pin_cam0_f1"));
        assertFalse(ImlShmJanitor.isEphemeralLinePin("iml_ref_cam5"));
        assertFalse(ImlShmJanitor.isEphemeralLinePin("iml_pos_cam_5"));
        assertFalse(ImlShmJanitor.isEphemeralLinePin("iml_cam_5_frame"));
    }

    @Test
    void releaseEphemeralCaptureBuffersDeletesLinePinsOnly() throws Exception {
        Path pin = FrameJpegWriter.imlShmFilePath("iml_line_pin_cam9_f7");
        Path ref = FrameJpegWriter.imlShmFilePath("iml_ref_cam9");
        Path ring = FrameJpegWriter.imlShmFilePath("iml_cam_9_frame");
        Path parent = pin.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(pin, new byte[] {1, 2, 3});
        Files.write(ref, new byte[] {4, 5, 6});
        Files.write(ring, new byte[] {7, 8, 9});

        ImlShmJanitor.releaseEphemeralCaptureBuffers(
                Map.of(
                        "shm_name", "/iml_pos_cam_9",
                        "original_shm_name", "/iml_line_pin_cam9_f7",
                        "camera_id", 9
                ),
                null
        );

        assertFalse(Files.exists(pin));
        assertTrue(Files.isRegularFile(ref));
        assertTrue(Files.isRegularFile(ring));
    }

    @Test
    void imlShmDirectoryResolvesParent() {
        assertNotNull(ImlShmJanitor.imlShmDirectory());
    }
}
