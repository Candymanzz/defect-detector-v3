package com.example.iml.orchestrator.integration.pipeline.session;

import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.example.iml.orchestrator.integration.pipeline.InspectionPipelineServices;
import com.example.iml.orchestrator.integration.pipeline.PipelineState;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.pipeline.decision.DefaultInspectionDecisionAggregator;
import com.example.iml.orchestrator.integration.pipeline.spi.AfterInspectionSidecar;
import com.example.iml.orchestrator.integration.pipeline.spi.CameraCaptureStage;
import com.example.iml.orchestrator.integration.pipeline.spi.GeometryInspectStage;
import com.example.iml.orchestrator.integration.pipeline.spi.PipelineRunTelemetry;
import com.example.iml.orchestrator.integration.pipeline.spi.PythonInspectStage;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncInspectionCycleRunnerTest {

    private final List<ExecutorService> executors = new ArrayList<>();

    @AfterEach
    void shutdownExecutors() {
        for (ExecutorService executor : executors) {
            executor.shutdownNow();
        }
        executors.clear();
    }

    @Test
    void runsFullPipelineCaptureGeometryPythonDecision() throws Exception {
        AtomicBoolean captureCalled = new AtomicBoolean(false);
        AtomicBoolean geometryCalled = new AtomicBoolean(false);
        AtomicBoolean pythonCalled = new AtomicBoolean(false);
        AtomicBoolean sidecarCalled = new AtomicBoolean(false);
        AtomicBoolean telemetryCalled = new AtomicBoolean(false);
        AtomicReference<InspectionDecision> decisionRef = new AtomicReference<>();

        BinaryProtocol.Message captureMsg = message(BinaryProtocol.MSG_RESPONSE, Map.of(
                "frame_id", 7L,
                "shm_name", "cam_shm",
                "width", 100,
                "height", 80,
                "stride", 300,
                "timestamp_ns", System.nanoTime()
        ));
        BinaryProtocol.Message geomMsg = message(BinaryProtocol.MSG_RESPONSE, Map.of(
                "overallPass", true,
                "status", "PASS"
        ));
        BinaryProtocol.Message pyMsg = message(BinaryProtocol.MSG_RESPONSE, Map.of(
                "ok", true,
                "status", "PASS",
                "anomaly_score", 0.05
        ));

        InspectionPipelineServices svc = pipelineServices(
                captureCalled,
                geometryCalled,
                pythonCalled,
                sidecarCalled,
                telemetryCalled,
                decisionRef,
                captureMsg,
                geomMsg,
                pyMsg
        );

        PerCameraInspectionGate gate = PerCameraInspectionGate.fromCameras(List.of(Map.of("id", 0)));
        gate.tryBeginInspection(0);

        ReferenceSnapshot reference = new ReferenceSnapshot("bench", Map.of(
                "shm_name", "ref_shm",
                "width", 100,
                "height", 80,
                "stride", 300
        ));
        AsyncInspectionCycleInput input = buildInput(0, reference);

        AsyncInspectionCycleRunner.run(svc, input, Map.of(), 5000L, gate);

        assertTrue(captureCalled.get(), "capture stage must run");
        assertTrue(geometryCalled.get(), "geometry stage must run");
        assertTrue(pythonCalled.get(), "python stage must run");
        assertTrue(sidecarCalled.get(), "after-inspection sidecar must run");
        assertTrue(telemetryCalled.get(), "pipeline telemetry must run");
        InspectionDecision decision = decisionRef.get();
        assertNotNull(decision);
        assertTrue(decision.overallPass());
        assertEquals(7L, decision.frameId());
        gate.endInspection(0);
    }

    @Test
    void captureOnlySkipsGeometryAndPythonWhenNoReference() throws Exception {
        AtomicBoolean captureCalled = new AtomicBoolean(false);
        AtomicBoolean geometryCalled = new AtomicBoolean(false);
        AtomicBoolean pythonCalled = new AtomicBoolean(false);
        AtomicBoolean sidecarCalled = new AtomicBoolean(false);
        AtomicReference<InspectionDecision> decisionRef = new AtomicReference<>();

        BinaryProtocol.Message captureMsg = message(BinaryProtocol.MSG_RESPONSE, Map.of(
                "frame_id", 11L,
                "shm_name", "cam_shm",
                "width", 64,
                "height", 48,
                "stride", 192
        ));

        InspectionPipelineServices svc = pipelineServices(
                captureCalled,
                geometryCalled,
                pythonCalled,
                sidecarCalled,
                new AtomicBoolean(false),
                decisionRef,
                captureMsg,
                null,
                null
        );

        PerCameraInspectionGate gate = PerCameraInspectionGate.fromCameras(List.of(Map.of("id", 1)));
        gate.tryBeginInspection(1);

        AsyncInspectionCycleInput input = buildInput(1, null);
        AsyncInspectionCycleRunner.run(svc, input, Map.of(), 5000L, gate);

        assertTrue(captureCalled.get());
        assertFalse(geometryCalled.get(), "geometry must be skipped without reference");
        assertFalse(pythonCalled.get(), "python must be skipped without reference");
        assertTrue(sidecarCalled.get());
        InspectionDecision decision = decisionRef.get();
        assertNotNull(decision);
        assertEquals("CAPTURE", decision.action());
        assertEquals("NO_REFERENCE", decision.pythonStatus());
        assertFalse(decision.overallPass());
        gate.endInspection(1);
    }

    @Test
    void softStopNullGateStillPublishesCaptureOnlyToUi() throws Exception {
        AtomicBoolean captureCalled = new AtomicBoolean(false);
        AtomicBoolean sidecarCalled = new AtomicBoolean(false);
        AtomicReference<InspectionDecision> decisionRef = new AtomicReference<>();

        BinaryProtocol.Message captureMsg = message(BinaryProtocol.MSG_RESPONSE, Map.of(
                "frame_id", 23L,
                "shm_name", "cam_shm",
                "width", 64,
                "height", 48,
                "stride", 192
        ));

        InspectionPipelineServices svc = pipelineServices(
                captureCalled,
                new AtomicBoolean(false),
                new AtomicBoolean(false),
                sidecarCalled,
                new AtomicBoolean(false),
                decisionRef,
                captureMsg,
                null,
                null
        );

        // Soft-stop preview path: gate is intentionally null.
        AsyncInspectionCycleRunner.run(svc, buildInput(2, null), Map.of(), 5000L, null);

        assertTrue(captureCalled.get());
        assertTrue(sidecarCalled.get(), "null gate must still schedule UI publish (no || short-circuit)");
        InspectionDecision decision = decisionRef.get();
        assertNotNull(decision);
        assertEquals("CAPTURE", decision.action());
        assertEquals(23L, decision.frameId());
    }

    @Test
    void exitsEarlyWhenCancelRequestedBeforeStart() throws Exception {
        AtomicBoolean captureCalled = new AtomicBoolean(false);
        InspectionPipelineServices svc = pipelineServices(
                captureCalled,
                new AtomicBoolean(false),
                new AtomicBoolean(false),
                new AtomicBoolean(false),
                new AtomicBoolean(false),
                new AtomicReference<>(),
                message(BinaryProtocol.MSG_RESPONSE, Map.of("frame_id", 1L)),
                null,
                null
        );

        PerCameraInspectionGate gate = PerCameraInspectionGate.fromCameras(List.of(Map.of("id", 0)));
        gate.tryBeginInspection(0);
        gate.requestCancel(0);

        ReferenceSnapshot reference = new ReferenceSnapshot("bench", Map.of(
                "shm_name", "ref_shm",
                "width", 10,
                "height", 10,
                "stride", 30
        ));
        AsyncInspectionCycleInput input = buildInput(0, reference);

        AsyncInspectionCycleRunner.run(svc, input, Map.of(), 5000L, gate);

        assertFalse(captureCalled.get(), "capture must not start when cancel is already requested");
        gate.endInspection(0);
    }

    @Test
    void suppressesResultWhenInspectionDisabledDuringDecision() throws Exception {
        AtomicBoolean sidecarCalled = new AtomicBoolean(false);
        AtomicReference<InspectionDecision> decisionRef = new AtomicReference<>();

        BinaryProtocol.Message captureMsg = message(BinaryProtocol.MSG_RESPONSE, Map.of(
                "frame_id", 3L,
                "shm_name", "cam_shm",
                "width", 32,
                "height", 32,
                "stride", 96
        ));
        BinaryProtocol.Message geomMsg = message(BinaryProtocol.MSG_RESPONSE, Map.of(
                "overallPass", true,
                "status", "PASS"
        ));
        BinaryProtocol.Message pyMsg = message(BinaryProtocol.MSG_RESPONSE, Map.of(
                "ok", true,
                "status", "PASS",
                "anomaly_score", 0.01
        ));

        InspectionPipelineServices svc = pipelineServices(
                new AtomicBoolean(true),
                new AtomicBoolean(true),
                new AtomicBoolean(true),
                sidecarCalled,
                new AtomicBoolean(true),
                decisionRef,
                captureMsg,
                geomMsg,
                pyMsg
        );

        PerCameraInspectionGate gate = PerCameraInspectionGate.fromCameras(List.of(Map.of("id", 0)));
        gate.tryBeginInspection(0);
        gate.disableInspectionAndRequestCancel(0);

        ReferenceSnapshot reference = new ReferenceSnapshot("bench", Map.of(
                "shm_name", "ref_shm",
                "width", 32,
                "height", 32,
                "stride", 96
        ));
        AsyncInspectionCycleInput input = buildInput(0, reference);

        AsyncInspectionCycleRunner.run(svc, input, Map.of(), 5000L, gate);

        assertFalse(sidecarCalled.get(), "sidecar must be suppressed when inspection is cancelled");
        assertNull(decisionRef.get());
        gate.endInspection(0);
    }

    private AsyncInspectionCycleInput buildInput(int cameraId, ReferenceSnapshot reference) {
        ExecutorService captureExec = trackedExecutor();
        ExecutorService geometryExec = trackedExecutor();
        ExecutorService pythonExec = trackedExecutor();
        ExecutorService decisionExec = trackedExecutor();
        ExecutorService uiExec = trackedExecutor();

        var saveCaptures = new IntegrationFeatureConfig.SaveCapturesConfig(false, "testimage", 0.92f);
        return AsyncInspectionCycleInput.of(
                Path.of("."),
                saveCaptures,
                cameraId,
                "bench",
                "detector-1",
                reference,
                0L,
                System.nanoTime(),
                null,
                null,
                List.of(),
                List.of(),
                Map.of(),
                Map.of(),
                null,
                new Semaphore(1),
                new Semaphore(1),
                new AtomicInteger(0),
                new AtomicInteger(0),
                captureExec,
                pythonExec,
                geometryExec,
                decisionExec,
                Map.of(),
                null,
                null,
                uiExec,
                0,
                null,
                1L,
                100L,
                null
        );
    }

    private ExecutorService trackedExecutor() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executors.add(executor);
        return executor;
    }

    private InspectionPipelineServices pipelineServices(
            AtomicBoolean captureCalled,
            AtomicBoolean geometryCalled,
            AtomicBoolean pythonCalled,
            AtomicBoolean sidecarCalled,
            AtomicBoolean telemetryCalled,
            AtomicReference<InspectionDecision> decisionRef,
            BinaryProtocol.Message captureMsg,
            BinaryProtocol.Message geomMsg,
            BinaryProtocol.Message pyMsg
    ) {
        CameraCaptureStage captureStage = new CameraCaptureStage() {
            @Override
            public void saveReferenceCapture(
                    Path projectRoot,
                    IntegrationFeatureConfig.SaveCapturesConfig saveCaptures,
                    BinaryProtocol.Message referenceCapture
            ) {
            }

            @Override
            public CompletableFuture<PipelineState> scheduleCapture(
                    Path projectRoot,
                    IntegrationFeatureConfig.SaveCapturesConfig saveCaptures,
                    int cameraId,
                    ReferenceSnapshot activeReference,
                    int flashLeadMs,
                    com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor worker,
                    com.example.iml.orchestrator.integration.lighting.LightTriggerClient lightClient,
                    ExecutorService captureStageExecutor,
                    long triggerSequence,
                    String debugLogSuffix
            ) {
                captureCalled.set(true);
                PipelineState state = new PipelineState(captureMsg, null, null, 1L, 0L, 0L);
                return CompletableFuture.completedFuture(state);
            }

            @Override
            public PipelineState runCaptureSync(
                    Path projectRoot,
                    IntegrationFeatureConfig.SaveCapturesConfig saveCaptures,
                    int cameraId,
                    ReferenceSnapshot activeReference,
                    int flashLeadMs,
                    com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor worker,
                    com.example.iml.orchestrator.integration.lighting.LightTriggerClient lightClient,
                    long triggerSequence,
                    String debugLogSuffix
            ) {
                captureCalled.set(true);
                return new PipelineState(captureMsg, null, null, 1L, 0L, 0L);
            }
        };

        GeometryInspectStage geometryStage = (state, cameraId, productType, activeReference, geometryCfg, pythonCfg, geometryPool, geometrySlots, geometryRoundRobin) -> {
            geometryCalled.set(true);
            return new PipelineState(state.capture(), state.py(), geomMsg, state.captureMs(), state.pythonMs(), 2L);
        };

        PythonInspectStage pythonStage = (state, cameraId, productType, detectorId, activeReference, pythonCfg, pythonPool, pythonSlots, pythonRoundRobin) -> {
            pythonCalled.set(true);
            return new PipelineState(state.capture(), pyMsg, state.geom(), state.captureMs(), 3L, state.geometryMs());
        };

        AfterInspectionSidecar sidecar = new AfterInspectionSidecar() {
            @Override
            public void scheduleAfterInspection(
                    com.example.iml.orchestrator.integration.ui.UiHttpServer uiServer,
                    Map<String, Object> uiCfg,
                    com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor uiVisualsPython,
                    ExecutorService uiArtifactsExecutor,
                    int cameraId,
                    String productType,
                    String detectorId,
                    long inspectionId,
                    ReferenceSnapshot activeReference,
                    InspectionDecision decision,
                    BinaryProtocol.Message capture,
                    BinaryProtocol.Message python,
                    BinaryProtocol.Message geometry
            ) {
                sidecarCalled.set(true);
                decisionRef.set(decision);
            }
        };

        PipelineRunTelemetry telemetry = new PipelineRunTelemetry() {
            @Override
            public void logReferenceSnapshot(
                    com.example.iml.orchestrator.integration.logging.PipelineStagesLog pipelineStagesLog,
                    int cameraId,
                    String productType,
                    long referenceWallMs,
                    int setReferenceRepeats,
                    Map<String, Object> extras
            ) {
            }

            @Override
            public void logInspectionCycle(
                    com.example.iml.orchestrator.integration.logging.PipelineStagesLog pipelineStagesLog,
                    Map<String, Object> timingExtras,
                    int cameraId,
                    String productType,
                    String detectorId,
                    long referenceMsFinal,
                    long pipelineWallSinceCamThreadStartMs,
                    PipelineState state,
                    InspectionDecision decision,
                    long tDecisionStartNanos,
                    long tDecisionEndNanos,
                    long tFanoutEndNanos
            ) {
                telemetryCalled.set(true);
            }
        };

        return new InspectionPipelineServices(
                LogManager.getLogger(getClass()),
                new DefaultInspectionDecisionAggregator(LogManager.getLogger(getClass())),
                telemetry,
                geometryStage,
                pythonStage,
                captureStage,
                null,
                sidecar
        );
    }

    private static BinaryProtocol.Message message(int type, Map<String, Object> header) {
        return new BinaryProtocol.Message(type, header, new byte[0]);
    }
}
