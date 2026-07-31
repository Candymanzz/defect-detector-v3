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

@SuppressWarnings("unused")
class InspectGeometryExecutorTest {

    private final InspectGeometryExecutor executor = new InspectGeometryExecutor(LogManager.getLogger(getClass()));

    @Test
    void returnsUnchangedStateWhenPoolEmpty() {
        PipelineState state = stateWithCapture();

        PipelineState result = executor.apply(
                state,
                0,
                "bench",
                reference(),
                Map.of(),
                Map.of(),
                List.of(),
                new Semaphore(1),
                new AtomicInteger(0)
        );

        assertSame(state, result);
    }

    @Test
    void skipsWhenReferenceMissing() {
        PipelineState result = executor.apply(
                stateWithCapture(),
                1,
                "bench",
                null,
                Map.of(),
                Map.of(),
                List.of(stubSupervisor("geometry")),
                new Semaphore(1),
                new AtomicInteger(0)
        );

        assertEquals(BinaryProtocol.MSG_ERROR, result.geom().type());
        assertEquals("SKIPPED", result.geom().header().get("status"));
    }

    @Test
    void errorsWhenCaptureFrameInvalid() {
        BinaryProtocol.Message badCapture = new BinaryProtocol.Message(
                BinaryProtocol.MSG_RESPONSE,
                Map.of("frame_id", 9L),
                new byte[0]
        );
        PipelineState state = new PipelineState(badCapture, null, null, 1L, 0L, 0L);

        PipelineState result = executor.apply(
                state,
                2,
                "bench",
                reference(),
                Map.of(),
                Map.of(),
                List.of(stubSupervisor("geometry")),
                new Semaphore(1),
                new AtomicInteger(0)
        );

        assertEquals(BinaryProtocol.MSG_ERROR, result.geom().type());
        assertEquals("ERROR", result.geom().header().get("status"));
    }

    @Test
    void callsGeometrySupervisorAndReturnsResponse() {
        AtomicReference<Map<String, Object>> sentHeader = new AtomicReference<>();
        BinaryRpcSupervisor geometry = new BinaryRpcSupervisor() {
            @Override
            public BinaryProtocol.Message command(Map<String, Object> header) {
                sentHeader.set(header);
                return new BinaryProtocol.Message(
                        BinaryProtocol.MSG_RESPONSE,
                        Map.of("overallPass", true, "status", "PASS"),
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
                return "geometry-test";
            }

            @Override
            public void close() {
            }
        };

        PipelineState result = executor.apply(
                stateWithCapture(),
                4,
                "bench",
                reference(),
                Map.of("max_shift_mm", 1.5),
                Map.of(),
                List.of(geometry),
                new Semaphore(1),
                new AtomicInteger(0)
        );

        assertEquals(BinaryProtocol.MSG_RESPONSE, result.geom().type());
        assertTrue(Boolean.TRUE.equals(result.geom().header().get("overallPass")));
        assertEquals(4, sentHeader.get().get("camera_id"));
        assertTrue(result.geometryMs() >= 0);
    }

    private static PipelineState stateWithCapture() {
        BinaryProtocol.Message capture = new BinaryProtocol.Message(
                BinaryProtocol.MSG_RESPONSE,
                Map.of(
                        "frame_id", 15L,
                        "shm_name", "cam_shm",
                        "width", 120,
                        "height", 90,
                        "stride", 360
                ),
                new byte[0]
        );
        return new PipelineState(capture, null, null, 3L, 0L, 0L);
    }

    private static ReferenceSnapshot reference() {
        return new ReferenceSnapshot("bench", Map.of(
                "shm_name", "ref_shm",
                "width", 120,
                "height", 90,
                "stride", 360
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
