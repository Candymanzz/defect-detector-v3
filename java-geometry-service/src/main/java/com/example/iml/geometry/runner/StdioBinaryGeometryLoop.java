package com.example.iml.geometry.runner;

import com.example.iml.geometry.analysis.OpenCvGeometryAnalysisService;
import com.example.iml.geometry.dto.InspectionRequest;
import com.example.iml.geometry.protocol.BinaryProtocol;
import com.example.iml.geometry.shm.ReferenceShmMatCache;
import com.example.iml.geometry.shm.ShmMatReader;
import com.example.iml.geometry.wire.InspectionHeaderMapper;
import com.example.iml.geometry.wire.InspectionResponsePayloadBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opencv.core.Mat;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Map;

public final class StdioBinaryGeometryLoop {

    private static final Logger log = LogManager.getLogger(StdioBinaryGeometryLoop.class);

    private final DataInputStream in;
    private final DataOutputStream out;
    private final OpenCvGeometryAnalysisService inspection;
    private final ShmMatReader shmReader = new ShmMatReader();
    private final ReferenceShmMatCache referenceCache = new ReferenceShmMatCache();

    public StdioBinaryGeometryLoop(DataInputStream in, DataOutputStream out, OpenCvGeometryAnalysisService inspection) {
        this.in = in;
        this.out = out;
        this.inspection = inspection;
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
                log.error("Geometry op failed: {}", op, e);
                BinaryProtocol.write(
                        out,
                        BinaryProtocol.MSG_ERROR,
                        Map.of("error", e.getMessage(), "op", op),
                        new byte[0]
                );
            }
        }
    }

    private void dispatch(String op, BinaryProtocol.Message msg) throws Exception {
        Map<String, Object> h = msg.header();
        switch (op) {
            case "health" -> BinaryProtocol.write(
                    out,
                    BinaryProtocol.MSG_RESPONSE,
                    Map.of("status", "ok", "service", "java-geometry-service"),
                    new byte[0]
            );
            case "inspect_stub" -> BinaryProtocol.write(
                    out,
                    BinaryProtocol.MSG_RESPONSE,
                    Map.of(
                            "camera_id", h.get("camera_id"),
                            "frame_id", h.get("frame_id"),
                            "status", "PASS",
                            "alignmentPass", true,
                            "overallPass", true
                    ),
                    new byte[0]
            );
            case "inspect" -> {
                boolean includeDebug = InspectionHeaderMapper.bool(h.get("includeDebugImage"), false);
                InspectionRequest request = InspectionHeaderMapper.fromInspectCommand(h);
                var response = inspection.inspect(request, includeDebug);
                BinaryProtocol.write(
                        out,
                        BinaryProtocol.MSG_RESPONSE,
                        InspectionResponsePayloadBuilder.toResponseHeader(response, includeDebug),
                        new byte[0]
                );
            }
            case "inspect_shm" -> handleInspectShm(h);
            case "inject_exit" -> {
                out.flush();
                System.exit(42);
            }
            case "inject_timeout_ms" -> {
                int timeoutMs = (int) InspectionHeaderMapper.num(h.get("timeout_ms"), 1000);
                Thread.sleep(Math.max(0, timeoutMs));
                BinaryProtocol.write(out, BinaryProtocol.MSG_RESPONSE, Map.of("status", "timeout_injected"), new byte[0]);
            }
            case "inject_broken_response" -> {
                out.write("BROKEN_RESPONSE".getBytes());
                out.flush();
            }
            default -> BinaryProtocol.write(
                    out,
                    BinaryProtocol.MSG_ERROR,
                    Map.of("error", "unknown op", "op", op),
                    new byte[0]
            );
        }
    }

    private void handleInspectShm(Map<String, Object> h) throws Exception {
        boolean includeDebug = InspectionHeaderMapper.bool(h.get("includeDebugImage"), false);
        Mat current = null;
        Mat reference = null;
        boolean releaseReference = false;
        try {
            current = shmReader.readShmMat(h);
            ReferenceShmMatCache.ReferenceMatResolution referenceResult = referenceCache.resolve(h, current, shmReader);
            reference = referenceResult.mat();
            releaseReference = referenceResult.releaseAfterUse();
            InspectionRequest request = InspectionHeaderMapper.fromInspectShmMetadata(h);
            var response = inspection.inspectMats(reference, current, request, includeDebug);
            BinaryProtocol.write(
                    out,
                    BinaryProtocol.MSG_RESPONSE,
                    InspectionResponsePayloadBuilder.toResponseHeader(response, includeDebug),
                    new byte[0]
            );
        } finally {
            if (current != null) {
                current.release();
            }
            if (releaseReference && reference != null && reference != current) {
                reference.release();
            }
        }
    }
}
