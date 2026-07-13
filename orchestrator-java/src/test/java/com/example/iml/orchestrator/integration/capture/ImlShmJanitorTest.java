package com.example.iml.orchestrator.integration.capture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImlShmJanitorTest {

    @Test
    void recognizesDedicatedOrchestratorBuffers() {
        assertTrue(ImlShmJanitor.isDedicatedOrchestratorBuffer("iml_ds_cur_cam0"));
        assertTrue(ImlShmJanitor.isDedicatedOrchestratorBuffer("iml_py_ds_ref_cam1"));
        assertTrue(ImlShmJanitor.isDedicatedOrchestratorBuffer("iml_ui_heatmap_cam_2"));
        assertFalse(ImlShmJanitor.isDedicatedOrchestratorBuffer("iml_cam_0_frame"));
        assertFalse(ImlShmJanitor.isDedicatedOrchestratorBuffer(null));
    }

    @Test
    void imlShmDirectoryResolvesParent() {
        assertNotNull(ImlShmJanitor.imlShmDirectory());
    }
}
