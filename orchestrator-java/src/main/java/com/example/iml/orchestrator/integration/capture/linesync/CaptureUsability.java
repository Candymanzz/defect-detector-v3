package com.example.iml.orchestrator.integration.capture.linesync;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.protocol.BinaryProtocol;

import java.util.Map;

/** Helpers to decide whether a wait_frame / capture response is usable. */
public final class CaptureUsability {

    private CaptureUsability() {
    }

    public static boolean isUsableCapture(BinaryProtocol.Message capture) {
        if (capture == null || capture.type() == BinaryProtocol.MSG_ERROR || capture.header() == null) {
            return false;
        }
        Map<String, Object> header = capture.header();
        String shmName = String.valueOf(header.getOrDefault("shm_name", "")).trim();
        int width = YamlScalars.toInt(header.get("width"), 0);
        int height = YamlScalars.toInt(header.get("height"), 0);
        long frameId = YamlScalars.toLong(header.get("frame_id"), -1L);
        return !shmName.isEmpty() && width > 0 && height > 0 && frameId >= 0L;
    }

    public static String describeCapture(BinaryProtocol.Message capture) {
        if (capture == null) {
            return "null";
        }
        if (capture.header() == null) {
            return "type=" + capture.type() + " header=null";
        }
        return String.valueOf(capture.header());
    }
}
