package com.example.iml.orchestrator.integration.pipeline.stages;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.clientapi.GeometryRuntimeConfig;
import com.example.iml.orchestrator.integration.config.CameraAnalysisProfiles;
import com.example.iml.orchestrator.integration.pipeline.PipelineState;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InspectPythonExecutorTest {

    private final InspectPythonExecutor executor = new InspectPythonExecutor(LogManager.getLogger(getClass()));

    @AfterEach
    void clearProfiles() {
        CameraAnalysisProfiles.setByCamera(Map.of());
    }

    @Test
    void returnsUnchangedStateWhenPoolEmpty() {
        PipelineState state = stateWithCapture();
        ReferenceSnapshot reference = reference();

        PipelineState result = executor.apply(
                state,
                0,
                "bench",
                "detector-1",
                reference,
                Map.of(),
                List.of(),
                new Semaphore(1),
                new AtomicInteger(0)
        );

        assertSame(state, result);
    }

    @Test
    void skipsWhenReferenceMissing() {
        PipelineState state = stateWithCapture();

        PipelineState result = executor.apply(
                state,
                1,
                "bench",
                "detector-1",
                null,
                Map.of(),
                List.of(stubSupervisor("python")),
                new Semaphore(1),
                new AtomicInteger(0)
        );

        assertEquals(BinaryProtocol.MSG_ERROR, result.py().type());
        assertEquals("SKIPPED", result.py().header().get("status"));
    }

    @Test
    void errorsWhenCaptureFrameInvalid() {
        BinaryProtocol.Message badCapture = new BinaryProtocol.Message(
                BinaryProtocol.MSG_RESPONSE,
                Map.of("frame_id", 1L),
                new byte[0]
        );
        PipelineState state = new PipelineState(badCapture, null, null, 1L, 0L, 0L);

        PipelineState result = executor.apply(
                state,
                2,
                "bench",
                "detector-1",
                reference(),
                Map.of(),
                List.of(stubSupervisor("python")),
                new Semaphore(1),
                new AtomicInteger(0)
        );

        assertEquals(BinaryProtocol.MSG_ERROR, result.py().type());
        assertEquals("ERROR", result.py().header().get("status"));
    }

    @Test
    void callsPythonSupervisorAndReturnsResponse() {
        AtomicReference<Map<String, Object>> sentHeader = new AtomicReference<>();
        BinaryRpcSupervisor python = new BinaryRpcSupervisor() {
            @Override
            public BinaryProtocol.Message command(Map<String, Object> header) {
                sentHeader.set(header);
                return new BinaryProtocol.Message(
                        BinaryProtocol.MSG_RESPONSE,
                        Map.of("ok", true, "status", "PASS", "anomaly_score", 0.02),
                        new byte[0]
                );
            }

            @Override
            public BinaryProtocol.Message commandNoRetry(Map<String, Object> header) {
                return command(header);
            }

            @Override
            public BinaryProtocol.Message health() {
                return new BinaryProtocol.Message(BinaryProtocol.MSG_RESPONSE, Map.of("status", "ok"), new byte[0]);
            }

            @Override
            public void start() {
            }

            @Override
            public void restart() {
            }

            @Override
            public int restartCount() {
                return 0;
            }

            @Override
            public String supervisorLabel() {
                return "python-test";
            }

            @Override
            public void close() {
            }
        };

        PipelineState result = executor.apply(
                stateWithCapture(),
                3,
                "bench",
                "detector-1",
                reference(),
                Map.of(),
                List.of(python),
                new Semaphore(1),
                new AtomicInteger(0)
        );

        assertEquals(BinaryProtocol.MSG_RESPONSE, result.py().type());
        assertTrue(Boolean.TRUE.equals(result.py().header().get("ok")));
        assertEquals(3, sentHeader.get().get("camera_id"));
        assertTrue(result.pythonMs() >= 0);
    }

    @Test
    void appliesGeometryRuntimeUnderCameraAnalysisProfileNotProductType() {
        CameraAnalysisProfiles.setByCamera(Map.of(3, "bench-lan3"));
        GeometryRuntimeConfig runtime = new GeometryRuntimeConfig();
        runtime.replaceAllFromClient("bench-lan3", Map.of(
                "mainRoi", Map.of("x", 1, "y", 2, "width", 10, "height", 20)
        ));
        runtime.replaceAllFromClient("bench", Map.of(
                "mainRoi", Map.of("x", 99, "y", 99, "width", 1, "height", 1)
        ));

        InspectPythonExecutor withRuntime = new InspectPythonExecutor(LogManager.getLogger(getClass()), runtime);
        Map<String, Object> header = new HashMap<>();
        header.put("product_type", "bench");

        withRuntime.applyAnalysisProfileAndRuntimeOverrides(header, 3, "bench", Map.of("fallback_threshold", 0.45));

        assertEquals("bench-lan3", header.get("analysis_profile"));
        assertFalse(header.containsKey("threshold"));
        @SuppressWarnings("unchecked")
        Map<String, Object> algorithmParams = (Map<String, Object>) header.get("algorithm_params");
        assertEquals(1, ((Number) ((Map<?, ?>) algorithmParams.get("main_roi")).get("x")).intValue());
    }

    @Test
    void testAnalyzeForwardsInspectScaleToPython() {
        AtomicReference<Map<String, Object>> sentHeader = new AtomicReference<>();
        BinaryRpcSupervisor python = new BinaryRpcSupervisor() {
            @Override
            public BinaryProtocol.Message command(Map<String, Object> header) {
                sentHeader.set(header);
                return new BinaryProtocol.Message(
                        BinaryProtocol.MSG_RESPONSE,
                        Map.of("ok", true, "status", "PASS", "anomaly_score", 0.02),
                        new byte[0]
                );
            }

            @Override
            public BinaryProtocol.Message commandNoRetry(Map<String, Object> header) {
                return command(header);
            }

            @Override
            public BinaryProtocol.Message health() {
                return new BinaryProtocol.Message(BinaryProtocol.MSG_RESPONSE, Map.of("status", "ok"), new byte[0]);
            }

            @Override
            public void start() {
            }

            @Override
            public void restart() {
            }

            @Override
            public int restartCount() {
                return 0;
            }

            @Override
            public String supervisorLabel() {
                return "python-test";
            }

            @Override
            public void close() {
            }
        };

        BinaryProtocol.Message capture = new BinaryProtocol.Message(
                BinaryProtocol.MSG_RESPONSE,
                Map.of(
                        "frame_id", 42L,
                        "test_analyze", true,
                        "test_frame_file_path", "/tmp/frame.jpg",
                        "test_frame_cache_key", "2:42",
                        "shm_name", "cam_shm",
                        "width", 1224,
                        "height", 1024,
                        "stride", 3672
                ),
                new byte[0]
        );
        PipelineState state = new PipelineState(capture, null, null, 5L, 0L, 7L);

        PipelineState result = executor.apply(
                state,
                2,
                "bench",
                "detector-1",
                reference(),
                Map.of("inspect_scale", 0.5),
                List.of(python),
                new Semaphore(1),
                new AtomicInteger(0)
        );

        assertEquals(BinaryProtocol.MSG_RESPONSE, result.py().type());
        assertEquals("inspect_test_frame", sentHeader.get().get("op"));
        assertEquals(0.5, ((Number) sentHeader.get().get("inspect_scale")).doubleValue(), 1e-9);
    }

    private static PipelineState stateWithCapture() {
        BinaryProtocol.Message capture = new BinaryProtocol.Message(
                BinaryProtocol.MSG_RESPONSE,
                Map.of(
                        "frame_id", 42L,
                        "shm_name", "cam_shm",
                        "width", 100,
                        "height", 80,
                        "stride", 300
                ),
                new byte[0]
        );
        BinaryProtocol.Message geom = new BinaryProtocol.Message(
                BinaryProtocol.MSG_RESPONSE,
                Map.of("overallPass", true, "status", "PASS"),
                new byte[0]
        );
        return new PipelineState(capture, null, geom, 5L, 0L, 7L);
    }

    private static ReferenceSnapshot reference() {
        return new ReferenceSnapshot("bench", Map.of(
                "shm_name", "ref_shm",
                "width", 100,
                "height", 80,
                "stride", 300
        ));
    }

    private static BinaryRpcSupervisor stubSupervisor(String label) {
        return new BinaryRpcSupervisor() {
            @Override
            public BinaryProtocol.Message command(Map<String, Object> header) throws IOException {
                throw new IOException("should not be called");
            }

            @Override
            public BinaryProtocol.Message commandNoRetry(Map<String, Object> header) throws IOException {
                return command(header);
            }

            @Override
            public BinaryProtocol.Message health() {
                return new BinaryProtocol.Message(BinaryProtocol.MSG_RESPONSE, Map.of(), new byte[0]);
            }

            @Override
            public void start() {
            }

            @Override
            public void restart() {
            }

            @Override
            public int restartCount() {
                return 0;
            }

            @Override
            public String supervisorLabel() {
                return label;
            }

            @Override
            public void close() {
            }
        };
    }
}
