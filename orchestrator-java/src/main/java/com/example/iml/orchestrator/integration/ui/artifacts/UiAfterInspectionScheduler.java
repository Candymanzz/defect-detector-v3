package com.example.iml.orchestrator.integration.ui.artifacts;

import com.example.iml.orchestrator.integration.capture.LineFramePinService;
import com.example.iml.orchestrator.integration.clientws.ClientWebSocketServer;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.ui.UiHttpServer;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/** Schedules UI artifact publish after an inspection decision. */
public final class UiAfterInspectionScheduler {

    private final Logger log;
    private final UiPublishScheduler scheduler;
    private final InspectionFrameFreezer frameFreezer;
    private final HeatmapArtifactProducer heatmaps;
    private final UiArtifactFiles files;
    private final InspectionPreviewPublisher previewPublisher;

    public UiAfterInspectionScheduler(
            Logger log,
            UiPublishScheduler scheduler,
            InspectionFrameFreezer frameFreezer,
            HeatmapArtifactProducer heatmaps,
            UiArtifactFiles files,
            InspectionPreviewPublisher previewPublisher
    ) {
        this.log = log;
        this.scheduler = scheduler;
        this.frameFreezer = frameFreezer;
        this.heatmaps = heatmaps;
        this.files = files;
        this.previewPublisher = previewPublisher;
    }

    public void schedule(
            int cameraId,
            String productType,
            String detectorId,
            long inspectionId,
            ReferenceSnapshot activeReference,
            InspectionDecision decision,
            BinaryProtocol.Message capture,
            BinaryProtocol.Message pyResp,
            BinaryProtocol.Message geometry,
            UiHttpServer uiServer,
            Map<String, Object> uiCfg,
            BinaryRpcSupervisor uiVisualsPython,
            ExecutorService uiArtifactsExecutor,
            ClientWebSocketServer ws
    ) {
        if (capture == null) {
            return;
        }
        Map<String, Object> cap = new LinkedHashMap<>(capture.header());
        // Prefer positioned buffer for UI JPEG / cards (analysis already remapped shm_name).
        String previewShm = frameFreezer.resolveUiPreviewShmName(cap, cameraId);
        if (previewShm != null) {
            cap.put("shm_name", previewShm);
            cap.put("shm_offset", 0L);
        }
        String shmName = String.valueOf(cap.get("shm_name"));
        long frameId = YamlScalars.toLong(cap.get("frame_id"), -1L);
        int width = YamlScalars.toInt(cap.get("width"), 1224);
        int height = YamlScalars.toInt(cap.get("height"), 1024);
        int stride = YamlScalars.toInt(cap.get("stride"), width * 3);
        HeatmapArtifact resolvedSourceHeatmap = heatmaps.resolveHeatmapArtifact(
                pyResp == null ? null : pyResp.header(),
                null,
                width,
                height
        );
        if (uiServer == null || uiArtifactsExecutor == null) {
            notifyWs(ws, cameraId, productType, detectorId, inspectionId, decision, cap, "no ui pool");
            files.deleteTemporaryArtifact(resolvedSourceHeatmap.path(), "unused source heatmap");
            LineFramePinService.releasePinnedCapture(capture.header());
            return;
        }
        boolean storeCurrent = YamlScalars.toBool(uiCfg == null ? null : uiCfg.get("store_current_jpeg"), true);
        boolean storeHeatmapU8 = YamlScalars.toBool(uiCfg == null ? null : uiCfg.get("store_heatmap_u8"), true);
        if (!storeCurrent && !storeHeatmapU8) {
            notifyWs(ws, cameraId, productType, detectorId, inspectionId, decision, cap, "no store flags");
            files.deleteTemporaryArtifact(resolvedSourceHeatmap.path(), "disabled source heatmap");
            LineFramePinService.releasePinnedCapture(capture.header());
            return;
        }

        final HeatmapArtifact sourceHeatmap;
        if (!storeHeatmapU8) {
            files.deleteTemporaryArtifact(resolvedSourceHeatmap.path(), "disabled source heatmap");
            sourceHeatmap = HeatmapArtifact.empty();
        } else {
            sourceHeatmap = resolvedSourceHeatmap;
        }

        notifyWs(ws, cameraId, productType, detectorId, inspectionId, decision, cap, "immediate");

        long publishSequence = scheduler.nextSequence(cameraId);
        final FrozenFrame frozenFrame;
        try {
            frozenFrame = frameFreezer.freezeInspectionFrame(cameraId, frameId, shmName, width, height, stride, cap);
        } catch (IOException e) {
            files.deleteTemporaryArtifact(sourceHeatmap.path(), "failed source heatmap");
            LineFramePinService.releasePinnedCapture(capture.header());
            log.warn(
                    "inspection frame freeze failed camera_id={} frame_id={}: {}",
                    cameraId,
                    frameId,
                    e.getMessage()
            );
            return;
        }
        // Freeze no longer retains line-pin paths; free per-cycle SHM asap.
        LineFramePinService.releasePinnedCapture(capture.header());
        if (!scheduler.isLatestPublish(cameraId, publishSequence)) {
            files.deleteTemporaryArtifact(sourceHeatmap.path(), "stale source heatmap");
            files.deleteFrozenFrameIfOwned(frozenFrame, "stale frozen inspection frame");
            return;
        }

        UiPublishTask publishTask = new UiPublishTask(cameraId, () -> previewPublisher.publish(
                cameraId,
                productType,
                detectorId,
                inspectionId,
                activeReference,
                decision,
                cap,
                geometry,
                uiServer,
                uiCfg,
                uiVisualsPython,
                ws,
                shmName,
                frameId,
                width,
                height,
                stride,
                storeCurrent,
                storeHeatmapU8,
                sourceHeatmap,
                frozenFrame,
                publishSequence
        ), () -> {
            files.deleteTemporaryArtifact(sourceHeatmap.path(), "discarded queued source heatmap");
            files.deleteFrozenFrameIfOwned(frozenFrame, "discarded queued frozen inspection frame");
        });
        scheduler.removeQueuedPublishForCamera(uiArtifactsExecutor, cameraId);
        try {
            uiArtifactsExecutor.execute(publishTask);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            files.deleteTemporaryArtifact(sourceHeatmap.path(), "rejected source heatmap");
            files.deleteFrozenFrameIfOwned(frozenFrame, "rejected frozen inspection frame");
            long dropped = scheduler.markRejected();
            log.warn("ui publish rejected camera_id={} frame_id={} dropped_total={}", cameraId, frameId, dropped);
        }
    }

    private void notifyWs(
            ClientWebSocketServer ws,
            int cameraId,
            String productType,
            String detectorId,
            long inspectionId,
            InspectionDecision decision,
            Map<String, Object> cap,
            String label
    ) {
        if (ws == null) {
            return;
        }
        try {
            // Deliver decision immediately; heavy UI artifacts are published in a later update.
            ws.notifyInspectResult(cameraId, productType, detectorId, inspectionId, decision, cap, null, 0, 0, null, null, false, null);
        } catch (Exception e) {
            log.debug("client_ws inspect_result ({}) cam={}: {}", label, cameraId, e.getMessage());
        }
    }
}
