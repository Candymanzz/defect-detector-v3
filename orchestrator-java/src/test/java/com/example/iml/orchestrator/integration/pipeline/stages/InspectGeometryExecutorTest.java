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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InspectGeometryExecutorTest {

    private final InspectGeometryExecutor executor = new InspectGeometryExecutor(LogManager.getLogger(getClass()));

    @AfterEach
    void clearProfiles() {
        CameraAnalysisProfiles.setByCamera(Map.of());
    }

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

        assertEquals(BinaryProtocol.MSG_RESPONSE, result.geom().type());
        assertEquals("SKIPPED", result.geom().header().get("status"));
        assertEquals(true, result.geom().header().get("overallPass"));
    }

    @Test
    void skipsDisabledCameraWithoutCallingPool() {
        AtomicInteger calls = new AtomicInteger();
        InspectGeometryExecutor disabledExecutor = new InspectGeometryExecutor(
                LogManager.getLogger(getClass()),
                null,
                null,
                null,
                Set.of(2, 7)
        );

        PipelineState result = disabledExecutor.apply(
                stateWithCapture(),
                2,
                "bench",
                reference(),
                Map.of(),
                Map.of(),
                List.of(countingSupervisor(calls)),
                new Semaphore(1),
                new AtomicInteger(0)
        );

        assertEquals(0, calls.get());
        assertEquals("SKIPPED", result.geom().header().get("status"));
        assertEquals(true, result.geom().header().get("overallPass"));
        assertEquals(false, result.geom().header().get("jointCamera"));
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

    @Test
    void appliesGeometryRuntimeUnderCameraAnalysisProfileNotProductType() {
        CameraAnalysisProfiles.setByCamera(Map.of(4, "bench-lan3"));
        GeometryRuntimeConfig runtime = new GeometryRuntimeConfig();
        runtime.replaceAllFromClient("bench-lan3", Map.of("maxShiftMm", 7.5));
        runtime.replaceAllFromClient("bench", Map.of("maxShiftMm", 0.1));

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
                return "geometry-profile-test";
            }

            @Override
            public void close() {
            }
        };

        InspectGeometryExecutor withRuntime = new InspectGeometryExecutor(
                LogManager.getLogger(getClass()),
                null,
                runtime
        );
        withRuntime.apply(
                stateWithCapture(),
                4,
                "bench",
                reference(),
                Map.of(),
                Map.of(),
                List.of(geometry),
                new Semaphore(1),
                new AtomicInteger(0)
        );

        assertEquals(7.5, sentHeader.get().get("maxShiftMm"));
    }

    @Test
    void skipsClientReferenceWithoutJointRoiWithoutCallingPool() {
        AtomicInteger calls = new AtomicInteger();
        ReferenceSnapshot noJoint = new ReferenceSnapshot("bench", Map.of(
                "shm_name", "ref_shm",
                "width", 120,
                "height", 90,
                "stride", 360,
                "client_reference_bundle", true
        ));

        PipelineState result = executor.apply(
                stateWithCapture(),
                0,
                "bench",
                noJoint,
                Map.of("joint_roi", Map.of("x", 0, "y", 0, "width", 10, "height", 10)),
                Map.of(),
                List.of(countingSupervisor(calls)),
                new Semaphore(1),
                new AtomicInteger(0)
        );

        assertEquals(0, calls.get());
        assertEquals("SKIPPED", result.geom().header().get("status"));
        assertEquals(true, result.geom().header().get("overallPass"));
        assertTrue(String.valueOf(result.geom().header().get("error")).contains("no joint ROI"));
    }

    @Test
    void callsGeometryWhenClientReferenceHasJointRoi() {
        AtomicInteger calls = new AtomicInteger();
        ReferenceSnapshot withJoint = new ReferenceSnapshot("bench", Map.of(
                "shm_name", "ref_shm",
                "width", 120,
                "height", 90,
                "stride", 360,
                "client_reference_bundle", true,
                "joint_roi_norm", Map.of("x", 0.1, "y", 0.1, "width", 0.2, "height", 0.2)
        ));

        PipelineState result = executor.apply(
                stateWithCapture(),
                0,
                "bench",
                withJoint,
                Map.of(),
                Map.of(),
                List.of(countingSupervisor(calls)),
                new Semaphore(1),
                new AtomicInteger(0)
        );

        assertEquals(1, calls.get());
        assertEquals(true, result.geom().header().get("overallPass"));
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

    private static BinaryRpcSupervisor countingSupervisor(AtomicInteger calls) {
        return new BinaryRpcSupervisor() {
            @Override
            public BinaryProtocol.Message command(Map<String, Object> header) {
                calls.incrementAndGet();
                return new BinaryProtocol.Message(BinaryProtocol.MSG_RESPONSE, Map.of("overallPass", true), new byte[0]);
            }

            @Override
            public BinaryProtocol.Message commandNoRetry(Map<String, Object> header) {
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
                return "counting";
            }

            @Override
            public void close() {
            }
        };
    }
}
