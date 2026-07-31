package com.example.iml.orchestrator.integration.http.controller;

import com.example.iml.orchestrator.integration.http.HttpController;
import com.example.iml.orchestrator.integration.http.HttpRequestContext;
import com.example.iml.orchestrator.integration.http.HttpResponses;
import com.example.iml.orchestrator.integration.ui.FrameArchiveService;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FrameArchiveHttpController implements HttpController {

    private static final Pattern ARTIFACT_PATH = Pattern.compile(
            "^/api/frame-archive/cameras/(\\d+)/frames/(\\d+)/(frame\\.jpg|heatmap\\.u8|result\\.json)$"
    );
    private static final Pattern FRAME_PATH = Pattern.compile("^/api/frame-archive/cameras/(\\d+)/frames/(\\d+)$");
    private static final Pattern HISTORY_PATH = Pattern.compile("^/api/frame-archive/cameras/(\\d+)/history$");
    private static final Pattern CAMERA_PATH = Pattern.compile("^/api/frame-archive/cameras/(\\d+)$");

    private final FrameArchiveHttpHandlers handlers;

    public FrameArchiveHttpController(FrameArchiveService frameArchive) {
        this.handlers = new FrameArchiveHttpHandlers(frameArchive);
    }

    @Override
    public void handle(HttpRequestContext ctx) throws IOException {
        String path = ctx.path();
        if (path.equals("/api/client/frame-archive")) {
            handlers.handleSettings(ctx);
            return;
        }
        if (path.equals("/api/frame-archive") || path.equals("/api/frame-archive/")) {
            handlers.handleArchiveRoot(ctx);
            return;
        }
        Matcher historyMatcher = HISTORY_PATH.matcher(path);
        if (historyMatcher.matches()) {
            handlers.handleHistory(ctx, Integer.parseInt(historyMatcher.group(1)));
            return;
        }
        Matcher cameraMatcher = CAMERA_PATH.matcher(path);
        if (cameraMatcher.matches()) {
            handlers.handleCamera(ctx, Integer.parseInt(cameraMatcher.group(1)));
            return;
        }
        Matcher frameMatcher = FRAME_PATH.matcher(path);
        if (frameMatcher.matches()) {
            handlers.handleFrame(ctx, Integer.parseInt(frameMatcher.group(1)), Long.parseLong(frameMatcher.group(2)));
            return;
        }
        Matcher artifactMatcher = ARTIFACT_PATH.matcher(path);
        if (artifactMatcher.matches()) {
            handlers.handleArtifact(
                    ctx,
                    Integer.parseInt(artifactMatcher.group(1)),
                    Long.parseLong(artifactMatcher.group(2)),
                    artifactMatcher.group(3)
            );
            return;
        }
        HttpResponses.notFound(ctx);
    }
}
