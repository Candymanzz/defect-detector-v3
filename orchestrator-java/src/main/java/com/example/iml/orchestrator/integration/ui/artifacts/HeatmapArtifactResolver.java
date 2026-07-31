package com.example.iml.orchestrator.integration.ui.artifacts;

import com.example.iml.orchestrator.integration.config.YamlScalars;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Resolve heatmap U8 path/dimensions from Python response headers. */
final class HeatmapArtifactResolver {

    private HeatmapArtifactResolver() {
    }

    static HeatmapArtifact resolve(
            Map<String, Object> respHeader,
            Path requestedOutputPath,
            int captureWidth,
            int captureHeight
    ) {
        Map<String, Object> hdr = respHeader == null ? Map.of() : respHeader;
        String raw = firstNonBlankString(
                hdr,
                "heatmap_u8_path",
                "heatmap_u8_output_path",
                "heatmap_path",
                "heatmap_file",
                "heatmapFile"
        );
        if (raw == null && requestedOutputPath != null) {
            raw = requestedOutputPath.toString();
        }
        if (raw == null || raw.isBlank()) {
            return HeatmapArtifact.empty();
        }
        Path candidate = Path.of(raw.trim());
        if (!Files.isRegularFile(candidate)) {
            return HeatmapArtifact.empty();
        }
        int uw = YamlScalars.toInt(hdr.get("heatmap_u8_width"), 0);
        if (uw <= 0) {
            uw = YamlScalars.toInt(hdr.get("heatmap_width"), 0);
        }
        int uh = YamlScalars.toInt(hdr.get("heatmap_u8_height"), 0);
        if (uh <= 0) {
            uh = YamlScalars.toInt(hdr.get("heatmap_height"), 0);
        }
        if (uw <= 0 || uh <= 0) {
            uw = inferHeatmapWidth(hdr, captureWidth, uw);
            uh = inferHeatmapHeight(candidate, hdr, captureWidth, captureHeight, uh, uw);
        }
        if (uw <= 0 || uh <= 0) {
            uw = Math.max(1, captureWidth);
            uh = Math.max(1, captureHeight);
        }
        return new HeatmapArtifact(candidate, uw, uh);
    }

    private static int inferHeatmapWidth(Map<String, Object> hdr, int captureWidth, int uw) {
        if (uw > 0) {
            return uw;
        }
        return Math.max(1, YamlScalars.toInt(hdr.get("width"), captureWidth));
    }

    private static int inferHeatmapHeight(
            Path file,
            Map<String, Object> hdr,
            int captureWidth,
            int captureHeight,
            int uh,
            int uw
    ) {
        if (uh > 0) {
            return uh;
        }
        int fromHdr = YamlScalars.toInt(hdr.get("height"), 0);
        if (fromHdr > 0) {
            return fromHdr;
        }
        try {
            long sz = Files.size(file);
            if (uw > 0 && sz > 0 && sz % uw == 0) {
                int h = (int) (sz / uw);
                if (h > 0 && h <= Math.max(1, captureHeight) * 16L) {
                    return h;
                }
            }
            if (captureHeight > 0 && captureWidth > 0) {
                if (sz == (long) captureWidth * captureHeight || sz == (long) captureWidth * captureHeight * 3) {
                    return captureHeight;
                }
            }
        } catch (IOException ignored) {
        }
        return Math.max(1, captureHeight);
    }

    private static String firstNonBlankString(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object v = map.get(key);
            if (v == null) {
                continue;
            }
            String s = String.valueOf(v).trim();
            if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) {
                return s;
            }
        }
        return null;
    }
}
