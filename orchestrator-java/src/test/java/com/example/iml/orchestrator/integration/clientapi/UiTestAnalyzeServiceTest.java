package com.example.iml.orchestrator.integration.clientapi;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.example.iml.orchestrator.integration.pipeline.PipelineState;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.pipeline.decision.InspectionDecisionPolicy;
import com.example.iml.orchestrator.integration.pipeline.reference.PipelineReferenceRegistry;
import com.example.iml.orchestrator.integration.pipeline.spi.AfterInspectionSidecar;
import com.example.iml.orchestrator.integration.pipeline.spi.GeometryInspectStage;
import com.example.iml.orchestrator.integration.pipeline.spi.PythonInspectStage;
import com.example.iml.orchestrator.integration.ui.FrameArchiveConfig;
import com.example.iml.orchestrator.integration.ui.FrameArchiveService;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiTestAnalyzeServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void parseSourceAcceptsArchiveArtifactAndPin() throws Exception {
        assertEquals(UiTestAnalyzeService.Source.ARCHIVE, UiTestAnalyzeService.parseSource("archive"));
        assertEquals(UiTestAnalyzeService.Source.ARTIFACT, UiTestAnalyzeService.parseSource("ARTIFACT"));
        assertEquals(UiTestAnalyzeService.Source.CURRENT, UiTestAnalyzeService.parseSource("current"));
        assertEquals(UiTestAnalyzeService.Source.PIN, UiTestAnalyzeService.parseSource("pin"));
        UiTestAnalyzeService.AnalyzeException ex = assertThrows(
                UiTestAnalyzeService.AnalyzeException.class,
                () -> UiTestAnalyzeService.parseSource("nope")
        );
        assertEquals(400, ex.status());
    }

    @Test
    void submitFailsWithoutReference() throws Exception {
        FrameArchiveService archive = openArchive();
        writeArchiveFrame(archive, 0, 7L);
        UiTestAnalyzeService service = service(archive, null, List.of(dummySupervisor()), List.of(dummySupervisor()),
                passthroughGeometry(), passthroughPython(), decidePass(), noopSidecar());

        UiTestAnalyzeService.AnalyzeException ex = assertThrows(
                UiTestAnalyzeService.AnalyzeException.class,
                () -> service.submit(new UiTestAnalyzeService.Request(
                        0, UiTestAnalyzeService.Source.ARCHIVE, 7L, null
                ))
        );
        assertEquals(409, ex.status());
        assertTrue(ex.getMessage().contains("reference"));
    }

    @Test
    void submitFailsWhenFrameMissing() throws Exception {
        FrameArchiveService archive = openArchive();
        PipelineReferenceRegistry refs = new PipelineReferenceRegistry();
        refs.byCamera().put(0, usableRef());
        UiTestAnalyzeService service = service(archive, refs, List.of(dummySupervisor()), List.of(dummySupervisor()),
                passthroughGeometry(), passthroughPython(), decidePass(), noopSidecar());

        UiTestAnalyzeService.AnalyzeException ex = assertThrows(
                UiTestAnalyzeService.AnalyzeException.class,
                () -> service.submit(new UiTestAnalyzeService.Request(
                        0, UiTestAnalyzeService.Source.ARCHIVE, 999L, null
                ))
        );
        assertEquals(404, ex.status());
    }

    @Test
    void pinSurvivesArchiveOverwriteAndPinSourceRunsJob() throws Exception {
        FrameArchiveService archive = openArchive();
        writeArchiveFrame(archive, 0, 42L);
        byte[] original = Files.readAllBytes(
                archive.resolveArtifact(0, 42L, "frame.jpg").orElseThrow()
        );
        PipelineReferenceRegistry refs = new PipelineReferenceRegistry();
        refs.byCamera().put(0, usableRef());

        AtomicBoolean sidecarCalled = new AtomicBoolean();
        AtomicReference<PipelineState> lastState = new AtomicReference<>();
        PythonInspectStage python = (state, cameraId, productType, detectorId, activeReference, pythonCfg,
                                     pythonPool, pythonSlots, pythonRoundRobin) -> {
            lastState.set(state);
            return state;
        };
        AfterInspectionSidecar sidecar = (uiServer, uiCfg, uiVisualsPython, uiArtifactsExecutor, cameraId,
                                          productType, detectorId, inspectionId, activeReference, decision,
                                          capture, pythonMsg, geometryMsg) -> sidecarCalled.set(true);

        TestFramePinStore pinStore = new TestFramePinStore(tempDir.resolve("pins"));
        UiTestAnalyzeService service = service(
                archive,
                refs,
                List.of(dummySupervisor()),
                List.of(dummySupervisor()),
                passthroughGeometry(),
                python,
                decidePass(),
                sidecar,
                pinStore
        );

        UiTestAnalyzeService.Pinned pinned = service.pin(new UiTestAnalyzeService.Request(
                0, UiTestAnalyzeService.Source.ARCHIVE, 42L, null
        ));
        assertEquals(0, pinned.cameraId());
        assertEquals(42L, pinned.frameId());

        // Overwrite archive slot with a different JPEG — pin must keep the original bytes.
        Path overwrite = Files.createTempFile(tempDir, "overwrite", ".jpg");
        BufferedImage other = new BufferedImage(32, 24, BufferedImage.TYPE_3BYTE_BGR);
        ImageIO.write(other, "jpg", overwrite.toFile());
        assertTrue(archive.saveImmediately(new FrameArchiveService.SaveRequest(
                0, 42L, 2L, "bench", "v1", null, overwrite, null, 0, 0
        )));
        byte[] overwritten = Files.readAllBytes(
                archive.resolveArtifact(0, 42L, "frame.jpg").orElseThrow()
        );
        assertTrue(overwritten.length != original.length || !java.util.Arrays.equals(overwritten, original));

        UiTestAnalyzeService.Accepted accepted = service.submit(new UiTestAnalyzeService.Request(
                0, UiTestAnalyzeService.Source.PIN, 42L, null, pinned.pinId()
        ));
        assertEquals(42L, accepted.frameId());

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && !sidecarCalled.get()) {
            Thread.sleep(20);
        }
        assertTrue(sidecarCalled.get(), "after-inspection sidecar");
        assertNotNull(lastState.get());
        assertEquals(Boolean.TRUE, lastState.get().capture().header().get("test_analyze"));
        assertEquals(42L, ((Number) lastState.get().capture().header().get("frame_id")).longValue());

        byte[] pinnedBytes = Files.readAllBytes(pinStore.get(pinned.pinId()).orElseThrow().jpegPath());
        assertTrue(java.util.Arrays.equals(original, pinnedBytes));
        assertEquals(
                UiTestAnalyzeService.sha256Hex(original),
                lastState.get().capture().header().get("pin_jpeg_sha256")
        );
        assertEquals(
                pinned.imageHttpPath(),
                lastState.get().capture().header().get("http_path")
        );
        assertFalse(
                String.valueOf(lastState.get().capture().header().get("http_path")).contains("current.jpg")
        );
    }

    @Test
    void submitPinFailsWhenNotPinned() throws Exception {
        FrameArchiveService archive = openArchive();
        PipelineReferenceRegistry refs = new PipelineReferenceRegistry();
        refs.byCamera().put(0, usableRef());
        UiTestAnalyzeService service = service(archive, refs, List.of(dummySupervisor()), List.of(dummySupervisor()),
                passthroughGeometry(), passthroughPython(), decidePass(), noopSidecar());

        UiTestAnalyzeService.AnalyzeException ex = assertThrows(
                UiTestAnalyzeService.AnalyzeException.class,
                () -> service.submit(new UiTestAnalyzeService.Request(
                        0, UiTestAnalyzeService.Source.PIN, 1L, null, "missing-pin"
                ))
        );
        assertEquals(404, ex.status());
    }

    @Test
    void submitHappyPathRunsGeometryPythonAndSidecar() throws Exception {
        FrameArchiveService archive = openArchive();
        writeArchiveFrame(archive, 0, 42L);
        PipelineReferenceRegistry refs = new PipelineReferenceRegistry();
        refs.byCamera().put(0, usableRef());

        AtomicBoolean geometryCalled = new AtomicBoolean();
        AtomicBoolean pythonCalled = new AtomicBoolean();
        AtomicBoolean sidecarCalled = new AtomicBoolean();
        AtomicReference<PipelineState> lastState = new AtomicReference<>();

        GeometryInspectStage geometry = (state, cameraId, productType, activeReference, geometryCfg, pythonCfg,
                                         geometryPool, geometrySlots, geometryRoundRobin) -> {
            geometryCalled.set(true);
            assertEquals(0, cameraId);
            assertEquals(Boolean.TRUE, state.capture().header().get("test_analyze"));
            return state;
        };
        PythonInspectStage python = (state, cameraId, productType, detectorId, activeReference, pythonCfg,
                                     pythonPool, pythonSlots, pythonRoundRobin) -> {
            pythonCalled.set(true);
            lastState.set(state);
            return state;
        };
        AfterInspectionSidecar sidecar = (uiServer, uiCfg, uiVisualsPython, uiArtifactsExecutor, cameraId,
                                          productType, detectorId, inspectionId, activeReference, decision,
                                          capture, pythonMsg, geometryMsg) -> sidecarCalled.set(true);

        UiTestAnalyzeService service = service(archive, refs, List.of(dummySupervisor()), List.of(dummySupervisor()),
                geometry, python, decidePass(), sidecar);

        UiTestAnalyzeService.Accepted accepted = service.submit(new UiTestAnalyzeService.Request(
                0, UiTestAnalyzeService.Source.ARCHIVE, 42L, null
        ));
        assertNotNull(accepted.jobId());
        assertEquals(0, accepted.cameraId());
        assertEquals(42L, accepted.frameId());

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && !sidecarCalled.get()) {
            Thread.sleep(20);
        }
        assertTrue(geometryCalled.get(), "geometry stage");
        assertTrue(pythonCalled.get(), "python stage");
        assertTrue(sidecarCalled.get(), "after-inspection sidecar");
        assertNotNull(lastState.get());
        assertEquals(42L, ((Number) lastState.get().capture().header().get("frame_id")).longValue());
    }

    private FrameArchiveService openArchive() throws Exception {
        FrameArchiveConfig cfg = new FrameArchiveConfig(true, tempDir.resolve("archive"), 10, 100);
        return FrameArchiveService.open(cfg);
    }

    private static void writeArchiveFrame(FrameArchiveService archive, int cameraId, long frameId) throws Exception {
        Path src = Files.createTempFile("uitest-frame", ".jpg");
        BufferedImage img = new BufferedImage(16, 12, BufferedImage.TYPE_3BYTE_BGR);
        ImageIO.write(img, "jpg", src.toFile());
        boolean ok = archive.saveImmediately(new FrameArchiveService.SaveRequest(
                cameraId,
                frameId,
                1L,
                "bench",
                "v1",
                null,
                src,
                null,
                0,
                0
        ));
        assertTrue(ok);
        Files.deleteIfExists(src);
    }

    private UiTestAnalyzeService service(
            FrameArchiveService archive,
            PipelineReferenceRegistry refs,
            List<BinaryRpcSupervisor> geometryPool,
            List<BinaryRpcSupervisor> pythonPool,
            GeometryInspectStage geometry,
            PythonInspectStage python,
            InspectionDecisionPolicy decision,
            AfterInspectionSidecar sidecar
    ) {
        return service(
                archive,
                refs,
                geometryPool,
                pythonPool,
                geometry,
                python,
                decision,
                sidecar,
                new TestFramePinStore(tempDir.resolve("pins-" + System.nanoTime()))
        );
    }

    private UiTestAnalyzeService service(
            FrameArchiveService archive,
            PipelineReferenceRegistry refs,
            List<BinaryRpcSupervisor> geometryPool,
            List<BinaryRpcSupervisor> pythonPool,
            GeometryInspectStage geometry,
            PythonInspectStage python,
            InspectionDecisionPolicy decision,
            AfterInspectionSidecar sidecar,
            TestFramePinStore pinStore
    ) {
        PipelineReferenceRegistry registry = refs == null ? new PipelineReferenceRegistry() : refs;
        return new UiTestAnalyzeService(
                LogManager.getLogger(getClass()),
                registry,
                Map.of(0, "v1"),
                Map.of(),
                Map.of(),
                geometryPool,
                pythonPool,
                geometry,
                python,
                decision,
                sidecar,
                archive,
                pinStore,
                () -> null,
                () -> null,
                () -> null,
                () -> null,
                Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "uitest-ut");
                    t.setDaemon(true);
                    return t;
                })
        );
    }

    private static ReferenceSnapshot usableRef() {
        return new ReferenceSnapshot("bench", Map.of(
                "shm_name", "/iml_ref_cam0",
                "width", 16,
                "height", 12,
                "stride", 48
        ));
    }

    private static GeometryInspectStage passthroughGeometry() {
        return (state, cameraId, productType, activeReference, geometryCfg, pythonCfg,
                geometryPool, geometrySlots, geometryRoundRobin) -> state;
    }

    private static PythonInspectStage passthroughPython() {
        return (state, cameraId, productType, detectorId, activeReference, pythonCfg,
                pythonPool, pythonSlots, pythonRoundRobin) -> state;
    }

    private static InspectionDecisionPolicy decidePass() {
        return (cameraId, capture, py, geom) -> InspectionDecision.simple(
                cameraId,
                0L,
                true,
                "PASS",
                0.0,
                "OK",
                "OK"
        );
    }

    private static AfterInspectionSidecar noopSidecar() {
        return (uiServer, uiCfg, uiVisualsPython, uiArtifactsExecutor, cameraId, productType, detectorId,
                inspectionId, activeReference, decision, capture, python, geometry) -> {
        };
    }

    private static BinaryRpcSupervisor dummySupervisor() {
        return new BinaryRpcSupervisor() {
            @Override
            public BinaryProtocol.Message command(Map<String, Object> header) {
                return new BinaryProtocol.Message(BinaryProtocol.MSG_RESPONSE, Map.of("status", "OK"), new byte[0]);
            }

            @Override
            public BinaryProtocol.Message commandNoRetry(Map<String, Object> header) {
                return command(header);
            }

            @Override
            public BinaryProtocol.Message health() {
                return new BinaryProtocol.Message(BinaryProtocol.MSG_RESPONSE, Map.of("status", "OK"), new byte[0]);
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
                return "dummy";
            }

            @Override
            public void close() {
            }
        };
    }
}
