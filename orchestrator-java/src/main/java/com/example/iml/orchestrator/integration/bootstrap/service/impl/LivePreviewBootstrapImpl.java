package com.example.iml.orchestrator.integration.bootstrap.service.impl;

import com.example.iml.orchestrator.integration.bootstrap.service.api.BootstrapInspectionFeatures;

import com.example.iml.orchestrator.integration.bootstrap.service.api.LivePreviewBootstrap;

import com.example.iml.orchestrator.integration.bootstrap.service.api.AbstractBootstrapService;

import com.example.iml.orchestrator.integration.bootstrap.context.port.LivePreviewHost;
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
        IntegrationFeatureConfig.DevAutoTriggerStubConfig devAutoTriggerStub =
                BootstrapInspectionFeatures.devAutoTriggerStub(session.integration());
        LivePreviewPublisher livePreview = LivePreviewPublisher.start(
                log,
                session.root(),
                session.activeCameras(),
                session.workersByCamera(),
                session.lightClient(),
                session.uiServer(),
                session.clientWsServer(),
                session.flashLeadMs(),
                session.uiCfg(),
                session.bootConfig().referenceSource(),
                session.pipelineReferenceRegistry(),
                devAutoTriggerStub,
                session.cameraStreamService(),
                session.livePreviewGate(),
                session.inspectionGate()
        );
        session.setLivePreview(livePreview);
        if (livePreview != null && session.lineCaptureCoordinator() != null) {
            livePreview.setLineCaptureCoordinator(session.lineCaptureCoordinator());
        }
    }
}
