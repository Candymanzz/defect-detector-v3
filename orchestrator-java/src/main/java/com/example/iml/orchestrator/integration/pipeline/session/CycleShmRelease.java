package com.example.iml.orchestrator.integration.pipeline.session;

import com.example.iml.orchestrator.integration.capture.LineFramePinService;
import com.example.iml.orchestrator.protocol.BinaryProtocol;

/** SHM release helpers for inspection cycle capture frames. */
final class CycleShmRelease {

    private CycleShmRelease() {
    }

    static void releaseCycleShm(BinaryProtocol.Message capture) {
        if (capture == null || capture.header() == null) {
            return;
        }
        LineFramePinService.releasePinnedCapture(capture.header());
    }
}
