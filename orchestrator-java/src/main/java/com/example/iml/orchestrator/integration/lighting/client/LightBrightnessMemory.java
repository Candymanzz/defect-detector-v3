package com.example.iml.orchestrator.integration.lighting.client;

import com.example.iml.orchestrator.integration.lighting.LightServersConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable in-memory brightness state for configured light cameras.
 *
 * <p>Synchronization remains the responsibility of the owning facade.</p>
 */
public final class LightBrightnessMemory {

    private static final Logger LOG = LogManager.getLogger(LightBrightnessMemory.class);

    private volatile int defaultBrightnessPercent;
    private final List<LightServersConfig.CameraFlashSpec> cameras;
    private final Map<Integer, LightServersConfig.CameraFlashSpec> cameraById;

    public LightBrightnessMemory(int defaultBrightnessPercent, List<LightServersConfig.CameraFlashSpec> cameras) {
        this.defaultBrightnessPercent = defaultBrightnessPercent;
        this.cameras = new ArrayList<>(cameras);
        this.cameraById = indexCameras(this.cameras);
    }

    public int brightnessPercent() {
        return defaultBrightnessPercent;
    }

    public int brightnessPercent(String endpointId) {
        Integer cameraId = parseCameraIdFromEndpoint(endpointId);
        if (cameraId == null) {
            return defaultBrightnessPercent;
        }
        LightServersConfig.CameraFlashSpec spec = cameraById.get(cameraId);
        return spec == null ? defaultBrightnessPercent : spec.brightnessPercent();
    }

    public Map<String, Integer> brightnessByEndpoint() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (LightServersConfig.CameraFlashSpec camera : cameras) {
            out.put(camera.endpointId(), camera.brightnessPercent());
        }
        return Map.copyOf(out);
    }

    public List<String> endpointIds() {
        return cameras.stream().map(LightServersConfig.CameraFlashSpec::endpointId).toList();
    }

    public int[] cameraIds(String endpointId) {
        Integer cameraId = parseCameraIdFromEndpoint(endpointId);
        return cameraId == null ? new int[0] : new int[]{cameraId};
    }

    public LightServersConfig.CameraFlashSpec camera(int cameraId) {
        return cameraById.get(cameraId);
    }

    public boolean hasCameras() {
        return !cameras.isEmpty();
    }

    public int size() {
        return cameras.size();
    }

    public List<LightServersConfig.CameraFlashSpec> snapshot() {
        return new ArrayList<>(cameras);
    }

    public void setDefaultBrightnessPercent(int percent) {
        defaultBrightnessPercent = percent;
    }

    public LightServersConfig.CameraFlashSpec replaceCameraBrightnessMemory(
            int cameraId,
            int percent,
            int leftPercent,
            int rightPercent
    ) {
        LightServersConfig.CameraFlashSpec existing = cameraById.get(cameraId);
        if (existing == null) {
            throw new IllegalArgumentException("unknown camera id: " + cameraId);
        }
        int before = existing.brightnessPercent();
        LightServersConfig.CameraFlashSpec updated = new LightServersConfig.CameraFlashSpec(
                cameraId, existing.mode(), percent, leftPercent, rightPercent
        );
        cameraById.put(cameraId, updated);
        for (int i = 0; i < cameras.size(); i++) {
            if (cameras.get(i).cameraId() == cameraId) {
                cameras.set(i, updated);
                break;
            }
        }
        if (before != percent) {
            LOG.info("light camera-{} brightness {}% -> {}%", cameraId, before, percent);
        }
        return updated;
    }

    public static Map<Integer, LightServersConfig.CameraFlashSpec> indexCameras(
            List<LightServersConfig.CameraFlashSpec> cameras
    ) {
        Map<Integer, LightServersConfig.CameraFlashSpec> out = new LinkedHashMap<>();
        for (LightServersConfig.CameraFlashSpec camera : cameras) {
            out.put(camera.cameraId(), camera);
        }
        return out;
    }

    public static Integer parseCameraIdFromEndpoint(String endpointId) {
        if (endpointId == null || endpointId.isBlank()) {
            return null;
        }
        if (endpointId.startsWith("camera-") || endpointId.startsWith("camera_")) {
            try {
                return Integer.parseInt(endpointId.substring(endpointId.indexOf('-') >= 0
                        ? endpointId.indexOf('-') + 1
                        : endpointId.indexOf('_') + 1));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (endpointId.startsWith("cam-") || endpointId.startsWith("cam_")) {
            try {
                return Integer.parseInt(endpointId.substring(4));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        try {
            return Integer.parseInt(endpointId);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
