package com.example.iml.orchestrator.integration.stream;

import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.clientws.outbound.WsOutboundMessenger;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.ui.UiHttpServer;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** stream_poll + JPEG encode + MJPEG/WS publish for one camera tick. */
final class CameraStreamPollSupport {

    private CameraStreamPollSupport() {
    }

    record PathHolder(Path path, int width, int height, String error) {
    }

    static void pollAndPublish(
            Logger log,
            Map<String, Object> uiCfg,
            Map<Integer, WorkerProcessSupervisor> workersByCamera,
            ConcurrentHashMap<Integer, CameraStreamSession> sessions,
            ConcurrentHashMap<Integer, CameraStreamMetrics> metricsByCamera,
            MjpegStreamHub mjpegHub,
            WsOutboundMessenger outbound,
            int cameraId
    ) {
        CameraStreamSession session = sessions.get(cameraId);
        if (session == null || !session.running.get()) {
            return;
        }
        if (!session.tickInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            WorkerProcessSupervisor worker = workersByCamera.get(cameraId);
            if (worker == null) {
                return;
            }
            BinaryProtocol.Message capture;
            CameraStreamMetrics metrics = metricsByCamera.computeIfAbsent(cameraId, ignored -> new CameraStreamMetrics());
            synchronized (worker) {
                capture = worker.command(Map.of("op", "stream_poll"));
            }
            if (capture == null) {
                return;
            }
            if (capture.type() == BinaryProtocol.MSG_ERROR) {
                session.notePollError(log, cameraId, formatWorkerError(capture));
                return;
            }
            if (capture.header() == null) {
                return;
            }
            Map<String, Object> header = capture.header();
            session.pollErrors.set(0);
            long frameId = YamlScalars.toLong(header.get("frame_id"), -1L);
            if (frameId < 0) {
                return;
            }
            String shmName = String.valueOf(header.get("shm_name"));
            int width = YamlScalars.toInt(header.get("width"), 0);
            int height = YamlScalars.toInt(header.get("height"), 0);
            int stride = YamlScalars.toInt(header.get("stride"), 0);
            long shmOffset = YamlScalars.toLong(header.get("shm_offset"), 0L);
            if (shmName.isBlank() || width <= 0 || height <= 0) {
                return;
            }

            long encodeStarted = System.nanoTime();
            PathHolder jpeg = writePreviewJpeg(uiCfg, shmName, width, height, stride, shmOffset);
            metrics.encodeNs.add(System.nanoTime() - encodeStarted);
            if (jpeg.path == null || !Files.isRegularFile(jpeg.path)) {
                if (jpeg.error != null) {
                    log.warn("client_stream cam={} frame={}: {}", cameraId, frameId, jpeg.error);
                }
                return;
            }
            try {
                byte[] jpegBytes = Files.readAllBytes(jpeg.path);
                mjpegHub.publish(cameraId, jpegBytes);
                maybeNotifyStreamStarted(log, outbound, session, cameraId);
            } catch (IOException e) {
                log.debug("client_stream mjpeg publish camera={}: {}", cameraId, e.getMessage());
                return;
            } finally {
                try {
                    Files.deleteIfExists(jpeg.path);
                } catch (IOException e) {
                    log.debug("client_stream temp jpeg cleanup camera={}: {}", cameraId, e.getMessage());
                }
            }
            metrics.frames.increment();
            metrics.maybeLog(log, cameraId);
        } catch (Exception e) {
            session.notePollError(log, cameraId, e.getMessage());
        } finally {
            session.tickInProgress.set(false);
        }
    }

    private static void maybeNotifyStreamStarted(
            Logger log,
            WsOutboundMessenger outbound,
            CameraStreamSession session,
            int cameraId
    ) {
        if (!session.wsStartedSent.compareAndSet(false, true)) {
            return;
        }
        if (outbound == null || session.connection == null || !session.connection.isOpen()) {
            return;
        }
        String httpPath = "/api/camera/" + cameraId + "/current.jpg";
        outbound.sendStreamStarted(
                session.connection, cameraId, session.fps, httpPath, MjpegStreamHub.mjpegPath(cameraId));
        log.info("client_stream ws stream_started camera={} after first published frame", cameraId);
    }

    private static String formatWorkerError(BinaryProtocol.Message capture) {
        if (capture.payload() != null && capture.payload().length > 0) {
            try {
                return new String(capture.payload(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // fall through
            }
        }
        return capture.header() == null ? "worker_error" : String.valueOf(capture.header());
    }

    private static PathHolder writePreviewJpeg(
            Map<String, Object> uiCfg,
            String shmName,
            int width,
            int height,
            int stride,
            long shmOffset
    ) {
        int previewMaxW = YamlScalars.toInt(uiCfg.get("client_preview_max_width"), 0);
        int qualPct = YamlScalars.toInt(uiCfg.get("client_preview_jpeg_quality"), 58);
        qualPct = Math.min(100, Math.max(5, qualPct));
        float q = qualPct / 100f;
        UiHttpServer.ClientPreviewArtifact art = UiHttpServer.writeCurrentJpegFromBgrShm(
                shmName, width, height, stride, shmOffset, previewMaxW, q, -1);
        return new PathHolder(art.path(), art.width(), art.height(), art.error());
    }
}
