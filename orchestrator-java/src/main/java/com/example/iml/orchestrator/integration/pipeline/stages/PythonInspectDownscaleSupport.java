package com.example.iml.orchestrator.integration.pipeline.stages;

import com.example.iml.orchestrator.integration.capture.FrameJpegWriter;
import com.example.iml.orchestrator.integration.config.YamlScalars;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Downscale BGR SHM frames for Python inspect path.
 */
final class PythonInspectDownscaleSupport {

    private static final ConcurrentHashMap<String, ShmDescriptor> REFERENCE_CACHE = new ConcurrentHashMap<>();

    private PythonInspectDownscaleSupport() {
    }

    static void applyDownscaleToPythonHeader(
            Map<String, Object> pyHeader,
            int cameraId,
            double inspectScale
    ) throws IOException {
        double scale = ShmBgrDownscale.sanitizeScale(inspectScale);
        if (scale >= 0.999d) {
            return;
        }
        ShmDescriptor current = descriptorFromHeader(pyHeader, "", cameraId);
        ShmDescriptor downscaledCurrent = writeDownscaled(current, cameraId, "iml_py_ds_cur_cam" + cameraId, scale);
        putDescriptor(pyHeader, "", downscaledCurrent);

        ShmDescriptor reference = descriptorFromHeader(pyHeader, "reference_", cameraId);
        // Fingerprint by on-disk content: same shm path/dims after reference retake must bust cache.
        String contentFp = contentFingerprint(reference, cameraId);
        pyHeader.put("reference_content_fingerprint", contentFp);
        String cacheKey = reference.cacheKey(scale) + "|" + contentFp;
        ShmDescriptor cached = REFERENCE_CACHE.get(cacheKey);
        if (cached != null && cached.existsForCamera(cameraId)) {
            putDescriptor(pyHeader, "reference_", cached);
            return;
        }
        String refName = "iml_py_ds_ref_cam" + cameraId;
        ShmDescriptor downscaledReference = writeDownscaled(reference, cameraId, refName, scale);
        REFERENCE_CACHE.put(cacheKey, downscaledReference);
        putDescriptor(pyHeader, "reference_", downscaledReference);
    }

    private static String contentFingerprint(ShmDescriptor source, int fallbackCameraId) {
        try {
            Path path = FrameJpegWriter.resolveShmPath(source.shmName(), source.cameraIdOr(fallbackCameraId));
            if (path == null || !Files.isRegularFile(path)) {
                return "missing";
            }
            return Files.getLastModifiedTime(path).toMillis() + ":" + Files.size(path);
        } catch (IOException e) {
            return "err";
        }
    }

    private static ShmDescriptor descriptorFromHeader(
            Map<String, Object> header,
            String prefix,
            int defaultCameraId
    ) {
        String shmName = String.valueOf(header.getOrDefault(prefix + "shm_name", "")).trim();
        int width = YamlScalars.toInt(header.get(prefix + "width"), 0);
        int height = YamlScalars.toInt(header.get(prefix + "height"), 0);
        int stride = YamlScalars.toInt(header.get(prefix + "stride"), 0);
        long shmOffset = YamlScalars.toLong(header.get(prefix + "shm_offset"), 0L);
        int cameraId = YamlScalars.toInt(header.get("camera_id"), defaultCameraId);
        if (shmName.isBlank() || width <= 0 || height <= 0 || stride < width * 3 || shmOffset < 0) {
            throw new IllegalArgumentException("invalid shm descriptor for prefix=" + prefix);
        }
        return new ShmDescriptor(shmName, width, height, stride, shmOffset, cameraId);
    }

    private static void putDescriptor(Map<String, Object> header, String prefix, ShmDescriptor d) {
        header.put(prefix + "shm_name", d.shmName());
        header.put(prefix + "shm_offset", d.shmOffset());
        header.put(prefix + "width", d.width());
        header.put(prefix + "height", d.height());
        header.put(prefix + "stride", d.stride());
    }

    private static ShmDescriptor writeDownscaled(
            ShmDescriptor source,
            int fallbackCameraId,
            String targetShmBaseName,
            double scale
    ) throws IOException {
        Path sourcePath = FrameJpegWriter.resolveShmPath(source.shmName(), source.cameraIdOr(fallbackCameraId));
        if (sourcePath == null || !Files.isRegularFile(sourcePath)) {
            throw new IOException("source shm not found: " + source.shmName());
        }
        byte[] srcBgr = ShmBgrDownscale.readBgr(
                sourcePath, source.shmOffset(), source.width(), source.height(), source.stride());
        int outW = Math.max(1, (int) Math.round(source.width() * scale));
        int outH = Math.max(1, (int) Math.round(source.height() * scale));
        byte[] outBgr = ShmBgrDownscale.resizeBgr(srcBgr, source.width(), source.height(), outW, outH);
        Path targetPath = FrameJpegWriter.imlShmFilePath(targetShmBaseName);
        ShmBgrDownscale.writeBgrFile(targetPath, outBgr);
        return new ShmDescriptor("/" + targetShmBaseName, outW, outH, outW * 3, 0L, source.cameraId());
    }

    private record ShmDescriptor(
            String shmName,
            int width,
            int height,
            int stride,
            long shmOffset,
            int cameraId
    ) {
        String cacheKey(double scale) {
            return String.join(
                    "|",
                    shmName,
                    String.valueOf(width),
                    String.valueOf(height),
                    String.valueOf(stride),
                    String.valueOf(shmOffset),
                    String.valueOf(cameraId),
                    String.valueOf(scale)
            );
        }

        int cameraIdOr(int fallback) {
            return cameraId >= 0 ? cameraId : fallback;
        }

        boolean existsForCamera(int fallbackCameraId) {
            Path p = FrameJpegWriter.resolveShmPath(shmName, cameraIdOr(fallbackCameraId));
            return p != null && Files.isRegularFile(p);
        }
    }
}
