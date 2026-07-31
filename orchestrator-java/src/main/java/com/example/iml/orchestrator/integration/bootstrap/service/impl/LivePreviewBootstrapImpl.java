package com.example.iml.orchestrator.integration.bootstrap.service.impl;

import com.example.iml.orchestrator.integration.bootstrap.service.api.BootstrapInspectionFeatures;

import com.example.iml.orchestrator.integration.bootstrap.service.api.LivePreviewBootstrap;

import com.example.iml.orchestrator.integration.bootstrap.service.api.AbstractBootstrapService;

import com.example.iml.orchestrator.integration.bootstrap.context.port.LivePreviewCollaboratorView;
import com.example.iml.orchestrator.integration.bootstrap.context.port.LivePreviewConfigView;
import com.example.iml.orchestrator.integration.bootstrap.context.port.LivePreviewHost;
import com.example.iml.orchestrator.integration.bootstrap.context.port.LivePreviewSink;
import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.preview.LivePreviewPublisher;
import org.apache.logging.log4j.Logger;

/**
 * Старт live preview (без workers/triggers).
 */
public final class LivePreviewBootstrapImpl extends AbstractBootstrapService implements LivePreviewBootstrap {

    public LivePreviewBootstrapImpl(Logger log) {
        super(log);
    }

    @Override
    public void start(LivePreviewHost session) {
        start(session, session, session);
    }

    void start(
            LivePreviewConfigView config,
            LivePreviewCollaboratorView collaborators,
            LivePreviewSink sink
    ) {
        IntegrationFeatureConfig.DevAutoTriggerStubConfig devAutoTriggerStub =
                BootstrapInspectionFeatures.devAutoTriggerStub(config.integration());
        LivePreviewPublisher livePreview = LivePreviewPublisher.start(
                log,
                config.root(),
                collaborators.activeCameras(),
                collaborators.workersByCamera(),
                collaborators.lightClient(),
                collaborators.uiServer(),
                collaborators.clientWsServer(),
                config.flashLeadMs(),
                config.uiCfg(),
                config.bootConfig().referenceSource(),
                devAutoTriggerStub,
                collaborators.cameraStreamService(),
                collaborators.livePreviewGate(),
                collaborators.inspectionGate()
        );
        sink.setLivePreview(livePreview);
        if (livePreview != null && collaborators.lineCaptureCoordinator() != null) {
            livePreview.setLineCaptureCoordinator(collaborators.lineCaptureCoordinator());
        }
    }
}
