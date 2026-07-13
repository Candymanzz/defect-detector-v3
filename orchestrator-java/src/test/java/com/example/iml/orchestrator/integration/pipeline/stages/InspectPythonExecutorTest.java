package com.example.iml.orchestrator.integration.pipeline.stages;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.pipeline.PipelineState;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InspectPythonExecutorTest {

    private final InspectPythonExecutor executor = new InspectPythonExecutor(LogManager.getLogger(getClass()));

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
    void callsPythonSupervisorAndReturnsResponse() throws Exception {
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
                Map.of("inspect_scale", 0.5),
                List.of(python),
                new Semaphore(1),
                new AtomicInteger(0)
        );

        assertEquals(BinaryProtocol.MSG_RESPONSE, result.py().type());
        assertTrue(Boolean.TRUE.equals(result.py().header().get("ok")));
        assertEquals(3, sentHeader.get().get("camera_id"));
        assertTrue(result.pythonMs() >= 0);
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
