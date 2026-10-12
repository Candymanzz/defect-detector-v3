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
import java.io.IOException;
import java.util.Map;

public final class StdioBinaryGeometryLoop {

    private static final Logger log = LogManager.getLogger(StdioBinaryGeometryLoop.class);

    private final DataInputStream in;
    private final DataOutputStream out;
    private final OpenCvGeometryAnalysisService inspection;
    private final ShmMatReader shmReader = new ShmMatReader();
    private final ReferenceShmMatCache referenceCache = new ReferenceShmMatCache();
    private final RpcTraceLog trace = new RpcTraceLog();

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
                if (!"EOF".equals(e.getMessage())) {
                    trace.protocolError(e);
                }
                return;
            }

            RpcTraceLog.Call call = trace.request(msg.type(), msg.header(), msg.payload().length);
            if (msg.type() != BinaryProtocol.MSG_COMMAND) {
                try {
                    writeReply(call, BinaryProtocol.MSG_ERROR, Map.of("error", "unexpected message type"), new byte[0]);
                } catch (Exception writeError) {
                    trace.failure(call, writeError);
                    throw writeError;
                }
                continue;
            }

            String op = String.valueOf(msg.header().getOrDefault("op", ""));
            try {
                dispatch(op, msg, call);
            } catch (Exception e) {
                log.error("Geometry op failed: {}", op, e);
                Map<String, Object> errorHeader = Map.of(
                        "error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                        "error_class", e.getClass().getName(), "op", op, "status", "ERROR");
                try {
                    writeReply(call, BinaryProtocol.MSG_ERROR, errorHeader, new byte[0]);
                } catch (Exception writeError) {
                    trace.failure(call, writeError);
                    throw writeError;
                }
            }
        }
    }

    private void writeReply(RpcTraceLog.Call call, int type, Map<String, Object> header, byte[] payload) throws Exception {
        BinaryProtocol.write(out, type, header, payload);
        trace.response(call, type, header, payload == null ? 0 : payload.length);
    }

    private void dispatch(String op, BinaryProtocol.Message msg, RpcTraceLog.Call call) throws Exception {
        Map<String, Object> h = msg.header();
        switch (op) {
            case "health" -> writeReply(
                    call,
                    BinaryProtocol.MSG_RESPONSE,
                    Map.of("status", "ok", "service", "java-geometry-service"),
                    new byte[0]
            );
            case "inspect_stub" -> writeReply(
                    call,
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
                writeReply(
                        call,
                        BinaryProtocol.MSG_RESPONSE,
                        InspectionResponsePayloadBuilder.toResponseHeader(response, includeDebug, request),
                        new byte[0]
                );
            }
            case "inspect_shm" -> handleInspectShm(h, call);
            case "inject_exit" -> {
                trace.failure(call, new IOException("inject_exit requested; no response"));
                out.flush();
                System.exit(42);
            }
            case "inject_timeout_ms" -> {
                int timeoutMs = (int) InspectionHeaderMapper.num(h.get("timeout_ms"), 1000);
                Thread.sleep(Math.max(0, timeoutMs));
                writeReply(call, BinaryProtocol.MSG_RESPONSE, Map.of("status", "timeout_injected"), new byte[0]);
            }
            case "inject_broken_response" -> {
                out.write("BROKEN_RESPONSE".getBytes());
                out.flush();
                trace.failure(call, new IOException("inject_broken_response requested; invalid protocol response"));
            }
            default -> writeReply(
                    call,
                    BinaryProtocol.MSG_ERROR,
                    Map.of("error", "unknown op", "op", op),
                    new byte[0]
            );
        }
    }

    private void handleInspectShm(Map<String, Object> h, RpcTraceLog.Call call) throws Exception {
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
            String referenceKey = ReferenceShmMatCache.referenceKey(h);
            boolean poseLocked = InspectionHeaderMapper.bool(h.get("pose_locked"), false)
                    || InspectionHeaderMapper.bool(h.get("poseLocked"), false)
                    || InspectionHeaderMapper.bool(h.get("already_aligned"), false);
            Map<String, Object> logContext = Map.of(
                    "camera_id", h.getOrDefault("camera_id", ""),
                    "frame_id", h.getOrDefault("frame_id", "")
            );
            var response = inspection.inspectMats(
                    reference, current, request, includeDebug, referenceKey, poseLocked, logContext);
            writeReply(
                    call,
                    BinaryProtocol.MSG_RESPONSE,
                    InspectionResponsePayloadBuilder.toResponseHeader(response, includeDebug, request),
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
