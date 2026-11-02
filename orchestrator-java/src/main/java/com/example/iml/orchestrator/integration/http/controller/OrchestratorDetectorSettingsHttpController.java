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
 * {@code /api/orchestrator/detector-settings} -> proxy to cppAnalisSurface {@code /settings}.
 * <ul>
 *   <li>{@code /api/orchestrator/detector-settings/defaults} -> {@code /settings/defaults}</li>
 *   <li>{@code /api/orchestrator/detector-settings/camera/{id}} -> {@code /settings/{analysis_profile of the camera}}</li>
 *   <li>{@code /api/orchestrator/detector-settings/{profile}} -> {@code /settings/{profile}}</li>
 * </ul>
 * GET reads, PUT applies a partial update (sensitivity, threshold, shift_tolerance_x/y, min_blob_area, inspect_scale),
 * DELETE resets the profile to defaults. Isolated from the legacy Python {@code analysis-settings} proxy.
 */
public final class OrchestratorDetectorSettingsHttpController implements HttpController {

    private static final Pattern CAMERA = Pattern.compile("^/detector-settings/camera/(\\d+)$");
    private static final Pattern PROFILE = Pattern.compile("^/detector-settings/([^/]+)$");

    private final String detectorBaseUrl;
    private final Map<Integer, String> analysisProfileByCamera;

    public OrchestratorDetectorSettingsHttpController(
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
            KopcheniHttpProxy.forwardTarget(ctx.exchange(), detectorBaseUrl,
                    "/settings/" + encode(profile) + "?camera_id=" + cameraId, null);
            return;
        }
        if ("/detector-settings/defaults".equals(target)) {
            KopcheniHttpProxy.forward(ctx.exchange(), detectorBaseUrl, "/settings/defaults");
            return;
        }
        Matcher profile = PROFILE.matcher(target);
        if (profile.matches()) {
            KopcheniHttpProxy.forward(ctx.exchange(), detectorBaseUrl, "/settings/" + profile.group(1));
            return;
        }
        HttpResponses.sendJsonError(ctx, 404, "unknown detector-settings path");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
