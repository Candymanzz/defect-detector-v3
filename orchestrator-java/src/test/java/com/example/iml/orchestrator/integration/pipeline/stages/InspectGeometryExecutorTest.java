package com.example.iml.orchestrator.integration.pipeline.stages;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.clientapi.GeometryRuntimeConfig;
import com.example.iml.orchestrator.integration.config.CameraAnalysisProfiles;
import com.example.iml.orchestrator.integration.pipeline.BinaryInspectHeaders;
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
    void skipsDisabledCameraWithoutCallingGeometryPool() {
        AtomicInteger geometryCalls = new AtomicInteger();
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
                List.of(countingSupervisor(geometryCalls)),
                new Semaphore(1),
                new AtomicInteger(0)
        );

        assertEquals(0, geometryCalls.get());
        assertEquals("SKIPPED", result.geom().header().get("status"));
        assertEquals(true, result.geom().header().get("overallPass"));
        assertEquals(false, result.geom().header().get("jointCamera"));
    }

    @Test
    void runsPositioningForGeometryDisabledCamera() {
        AtomicInteger positioningCalls = new AtomicInteger();
        InspectPositioningExecutor positioning = new InspectPositioningExecutor(
                LogManager.getLogger(getClass()),
                List.of(countingSupervisor(positioningCalls)),
                new Semaphore(1),
                new AtomicInteger(),
                Map.of("enabled", true)
        );
        InspectGeometryExecutor withPositioning = new InspectGeometryExecutor(
                LogManager.getLogger(getClass()),
                null,
                null,
                positioning,
                Set.of(2)
        );

        PipelineState result = withPositioning.apply(
                stateWithCapture(),
                2,
                "bench",
                reference(),
                Map.of(),
                Map.of(),
                List.of(),
                new Semaphore(1),
                new AtomicInteger(0)
        );

        assertEquals(1, positioningCalls.get());
        assertEquals("SKIPPED", result.geom().header().get("status"));
        assertEquals(true, result.geom().header().get("overallPass"));
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

    @Test
    void poseQcFailsWhenPositioningShiftExceedsGeometryLimit() {
        BinaryProtocol.Message geomPass = new BinaryProtocol.Message(
                BinaryProtocol.MSG_RESPONSE,
                Map.of(
                        "status", "PASS",
                        "overallPass", true,
                        "alignmentPass", true,
                        "jointPass", true,
                        "wrinklesPass", true,
                        "concentricityPass", true,
                        "shiftXmm", 0.0,
                        "shiftYmm", 0.0
                ),
                new byte[0]
        );
        BinaryProtocol.Message capture = new BinaryProtocol.Message(
                BinaryProtocol.MSG_RESPONSE,
                Map.of(
                        "frame_id", 350L,
                        InspectPositioningExecutor.HEADER_ALIGNED, true,
                        "positioning_shift_x_mm", 0.8,
                        "positioning_shift_y_mm", 0.1,
                        "positioning_rotation_deg", 0.2
                ),
                new byte[0]
        );

        BinaryProtocol.Message gated = InspectGeometryExecutor.applyPoseQcFromPositioning(
                geomPass,
                capture,
                Map.of("maxShiftMm", 0.5, "maxRotationDeg", 1.0)
        );

        assertEquals(false, gated.header().get("overallPass"));
        assertEquals(false, gated.header().get("alignmentPass"));
        assertEquals("FAIL", gated.header().get("status"));
        assertEquals(0.8, ((Number) gated.header().get("shiftXmm")).doubleValue(), 1e-9);
        assertEquals(true, gated.header().get("poseQcFromPositioning"));
    }

    @Test
    void poseQcKeepsPassWhenPositioningShiftWithinLimit() {
        BinaryProtocol.Message geomPass = new BinaryProtocol.Message(
                BinaryProtocol.MSG_RESPONSE,
                Map.of(
                        "status", "PASS",
                        "overallPass", true,
                        "alignmentPass", true,
                        "jointPass", true,
                        "wrinklesPass", true,
                        "concentricityPass", true,
                        "shiftXmm", 0.0,
                        "shiftYmm", 0.0
                ),
                new byte[0]
        );
        BinaryProtocol.Message capture = new BinaryProtocol.Message(
                BinaryProtocol.MSG_RESPONSE,
                Map.of(
                        InspectPositioningExecutor.HEADER_ALIGNED, true,
                        "positioning_shift_x_mm", 0.04,
                        "positioning_shift_y_mm", 0.01,
                        "positioning_rotation_deg", 0.1
                ),
                new byte[0]
        );

        BinaryProtocol.Message gated = InspectGeometryExecutor.applyPoseQcFromPositioning(
                geomPass,
                capture,
                Map.of("maxShiftMm", 0.5, "maxRotationDeg", 1.0)
        );

        assertEquals(true, gated.header().get("overallPass"));
        assertEquals(true, gated.header().get("alignmentPass"));
        assertEquals("PASS", gated.header().get("status"));
        assertEquals(0.04, ((Number) gated.header().get("shiftXmm")).doubleValue(), 1e-9);
    }

    @Test
    void geometryProfileOverridesWidenLowerLineParallelism() {
        Map<String, Object> header = new HashMap<>();
        header.put("maxJointParallelismDeg", 2.5);
        Map<String, Object> geometryCfg = Map.of(
                "max_joint_parallelism_deg", 2.5,
                "profiles", Map.of(
                        "bench-lan6", Map.of("max_joint_parallelism_deg", 5.0)
                )
        );

        BinaryInspectHeaders.applyGeometryProfileOverrides(
                header,
                BinaryInspectHeaders.resolveGeometryProfileOverrides(geometryCfg, "bench-lan6")
        );

        assertEquals(5.0, ((Number) header.get("maxJointParallelismDeg")).doubleValue(), 1e-9);
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
