package com.example.iml.positioning.runner;

import com.example.iml.positioning.analysis.BucketPositioningService;
import com.example.iml.positioning.dto.PositioningRequest;
import com.example.iml.positioning.protocol.BinaryProtocol;
import com.example.iml.positioning.shm.ReferenceShmMatCache;
import com.example.iml.positioning.shm.ShmMatReader;
import com.example.iml.positioning.shm.ShmMatWriter;
import com.example.iml.positioning.wire.PositioningHeaderMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opencv.core.Mat;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

public final class StdioBinaryPositioningLoop {

    private static final Logger log = LogManager.getLogger(StdioBinaryPositioningLoop.class);

    private final DataInputStream in;
    private final DataOutputStream out;
    private final BucketPositioningService positioning;
    private final ShmMatReader shmReader = new ShmMatReader();
    private final ReferenceShmMatCache referenceCache = new ReferenceShmMatCache();

    public StdioBinaryPositioningLoop(
            DataInputStream in,
            DataOutputStream out,
            BucketPositioningService positioning,
            ShmMatWriter shmWriter
    ) {
        this.in = in;
        this.out = out;
        this.positioning = positioning;
    }

    public void runForever() throws Exception {
        while (true) {
            BinaryProtocol.Message msg;
            try {
                msg = BinaryProtocol.read(in);
            } catch (Exception e) {
                return;
            }

            if (msg.type() != BinaryProtocol.MSG_COMMAND) {
                BinaryProtocol.write(out, BinaryProtocol.MSG_ERROR, Map.of("error", "unexpected message type"), new byte[0]);
                continue;
            }

            String op = String.valueOf(msg.header().getOrDefault("op", ""));
            try {
                dispatch(op, msg);
            } catch (Exception e) {
                String err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                log.error("Positioning op failed: {} error={}", op, err, e);
                Map<String, Object> errHeader = new LinkedHashMap<>();
                errHeader.put("error", err);
                errHeader.put("error_class", e.getClass().getName());
                errHeader.put("op", op);
                errHeader.put("status", "ERROR");
                errHeader.put("overallPass", false);
                BinaryProtocol.write(out, BinaryProtocol.MSG_ERROR, errHeader, new byte[0]);
            }
        }
    }

    private void dispatch(String op, BinaryProtocol.Message msg) throws Exception {
        Map<String, Object> h = msg.header();
        switch (op) {
            case "health" -> BinaryProtocol.write(
                    out,
                    BinaryProtocol.MSG_RESPONSE,
                    Map.of("status", "ok", "service", "java-positioning-service"),
                    new byte[0]
            );
            case "position_shm" -> handlePositionShm(h);
            default -> BinaryProtocol.write(
                    out,
                    BinaryProtocol.MSG_ERROR,
                    Map.of("error", "unknown op", "op", op),
                    new byte[0]
            );
        }
    }

    private void handlePositionShm(Map<String, Object> h) throws Exception {
        Mat current = null;
        Mat reference = null;
        boolean releaseReference = false;
        try {
            current = shmReader.readShmMat(h);
            ReferenceShmMatCache.ReferenceMatResolution referenceResult = referenceCache.resolve(h, current, shmReader);
            reference = referenceResult.mat();
            releaseReference = referenceResult.releaseAfterUse();
            PositioningRequest request = PositioningHeaderMapper.fromCommand(ensureOutputName(h));
            String referenceKey = ReferenceShmMatCache.referenceKey(h);
            Map<String, Object> logContext = new LinkedHashMap<>();
            if (h.get("camera_id") != null) {
                logContext.put("camera_id", h.get("camera_id"));
            }
            if (h.get("frame_id") != null) {
                logContext.put("frame_id", h.get("frame_id"));
            }
            var response = positioning.position(reference, current, request, referenceKey, logContext);
            Map<String, Object> header = new LinkedHashMap<>(PositioningHeaderMapper.toResponseHeader(response));
            header.put("camera_id", h.get("camera_id"));
            header.put("frame_id", h.get("frame_id"));
            BinaryProtocol.write(out, BinaryProtocol.MSG_RESPONSE, header, new byte[0]);
        } finally {
            if (current != null) {
                current.release();
            }
            if (releaseReference && reference != null && reference != current) {
                reference.release();
            }
        }
    }

    private static Map<String, Object> ensureOutputName(Map<String, Object> h) {
        Object existing = h.get("output_shm_name");
        if (existing == null || String.valueOf(existing).isBlank()) {
            existing = h.get("outputShmName");
        }
        if (existing != null && !String.valueOf(existing).isBlank()) {
            return h;
        }
        Object cameraId = h.get("camera_id");
        Map<String, Object> copy = new LinkedHashMap<>(h);
        copy.put("output_shm_name", "iml_pos_cam_" + (cameraId == null ? "x" : cameraId));
        return copy;
    }
}
