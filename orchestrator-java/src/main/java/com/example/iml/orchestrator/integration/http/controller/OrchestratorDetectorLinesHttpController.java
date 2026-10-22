package com.example.iml.orchestrator.integration.http.controller;

import com.example.iml.orchestrator.integration.clientapi.KopcheniHttpProxy;
import com.example.iml.orchestrator.integration.http.HttpController;
import com.example.iml.orchestrator.integration.http.HttpRequestContext;
import com.example.iml.orchestrator.integration.http.HttpResponses;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code /api/orchestrator/detector-lines} -> proxy to the seam / edge line zones of cppAnalisSurface {@code /lines}.
 * <ul>
 *   <li>{@code /api/orchestrator/detector-lines/defaults} -> {@code /lines/defaults}</li>
 *   <li>{@code /api/orchestrator/detector-lines/camera/{id}} -> {@code /lines/{analysis_profile of the camera}}
 *       (GET reads, PUT applies a partial update, DELETE resets to defaults)</li>
 *   <li>{@code /api/orchestrator/detector-lines/camera/{id}/last} -> {@code /lines/{profile}/last}: the line
 *       measurements of the latest inspection of the camera (deviation graph)</li>
 * </ul>
 */
public final class OrchestratorDetectorLinesHttpController implements HttpController {

    private static final Pattern CAMERA = Pattern.compile("^/detector-lines/camera/(\\d+)(/last)?$");

    private final String detectorBaseUrl;
    private final Map<Integer, String> analysisProfileByCamera;

    public OrchestratorDetectorLinesHttpController(
            String detectorBaseUrl,
            Map<Integer, String> analysisProfileByCamera
    ) {
        this.detectorBaseUrl = detectorBaseUrl == null ? "" : detectorBaseUrl.trim();
        this.analysisProfileByCamera = analysisProfileByCamera == null ? Map.of() : Map.copyOf(analysisProfileByCamera);
    }

    @Override
    public void handle(HttpRequestContext ctx) throws IOException {
        if (detectorBaseUrl.isEmpty()) {
            HttpResponses.sendJsonError(ctx, 503, "python_detector.base_url not configured");
            return;
        }
        String target = ctx.path().replaceFirst("^/api/orchestrator", "");
        Matcher camera = CAMERA.matcher(target);
        if (camera.matches()) {
            int cameraId = Integer.parseInt(camera.group(1));
            String profile = analysisProfileByCamera.get(cameraId);
            if (profile == null || profile.isBlank()) {
                HttpResponses.sendJsonError(ctx, 404, "camera " + cameraId + " is not configured");
                return;
            }
            String suffix = camera.group(2) == null ? "" : camera.group(2);
            KopcheniHttpProxy.forward(ctx.exchange(), detectorBaseUrl, "/lines/" + encode(profile) + suffix);
            return;
        }
        if ("/detector-lines/defaults".equals(target)) {
            KopcheniHttpProxy.forward(ctx.exchange(), detectorBaseUrl, "/lines/defaults");
            return;
        }
        HttpResponses.sendJsonError(ctx, 404, "unknown detector-lines path");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
