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
 * {@code /api/orchestrator/analysis-settings} -> proxy to FastAPI analisSurface
 * ({@code /analysis-settings}).
 * <p>
 * {@code /api/orchestrator/analysis-settings/camera/{cameraId}[/simple|/pro]} resolves
 * {@code cameraId} to configured {@code analysis_profile} before proxying, preserving
 * optional mode suffix ({@code /simple}, {@code /pro}).
 */
public final class OrchestratorAnalysisSettingsHttpController implements HttpController {

    private static final Pattern CAMERA_SUFFIX = Pattern.compile("^/analysis-settings/camera/(\\d+)(/.*)?$");

    private final String analisSurfaceBaseUrl;
    private final Map<Integer, String> analysisProfileByCamera;

    public OrchestratorAnalysisSettingsHttpController(
            String analisSurfaceBaseUrl,
            Map<Integer, String> analysisProfileByCamera
    ) {
        this.analisSurfaceBaseUrl = analisSurfaceBaseUrl == null ? "" : analisSurfaceBaseUrl.trim();
        this.analysisProfileByCamera = analysisProfileByCamera == null ? Map.of() : Map.copyOf(analysisProfileByCamera);
    }

    @Override
    public void handle(HttpRequestContext ctx) throws IOException {
        if (analisSurfaceBaseUrl.isEmpty()) {
            HttpResponses.sendJsonError(ctx, 503, "python_detector.base_url not configured");
            return;
        }
        String path = ctx.path();
        String target = path.replaceFirst("^/api/orchestrator", "");
        if (target.isEmpty()) {
            target = "/";
        }
        target = resolveCameraScopedPath(target, ctx);
        if (target == null) {
            return;
        }
        KopcheniHttpProxy.forward(ctx.exchange(), analisSurfaceBaseUrl, target);
    }

    private String resolveCameraScopedPath(String target, HttpRequestContext ctx) throws IOException {
        Matcher matcher = CAMERA_SUFFIX.matcher(target);
        if (!matcher.matches()) {
            return target;
        }
        int cameraId;
        try {
            cameraId = Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            HttpResponses.sendJsonError(ctx, 400, "invalid camera id");
            return null;
        }
        String analysisProfile = analysisProfileByCamera.get(cameraId);
        if (analysisProfile == null || analysisProfile.isBlank()) {
            HttpResponses.sendJsonError(ctx, 404, "camera " + cameraId + " is not configured");
            return null;
        }
        String modeSuffix = matcher.group(2);
        if (modeSuffix == null) {
            modeSuffix = "";
        }
        return "/analysis-settings/" + encodePathSegment(analysisProfile) + modeSuffix;
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
