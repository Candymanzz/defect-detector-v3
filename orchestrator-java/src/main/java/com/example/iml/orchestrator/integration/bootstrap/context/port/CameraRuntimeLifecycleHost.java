package com.example.iml.orchestrator.integration.bootstrap.context.port;

import com.example.iml.orchestrator.integration.bootstrap.lifecycle.IntegrationComponent;
import com.example.iml.orchestrator.integration.bootstrap.lifecycle.OrchestratorStopSignal;
import com.example.iml.orchestrator.integration.capture.LineSynchronizedCaptureCoordinator;
import com.example.iml.orchestrator.integration.preview.LivePreviewPublisher;

import java.util.List;

/**
 * Порт координатора camera-runtime (lifecycle / preview rebind).
 */
public interface CameraRuntimeLifecycleHost {

    LivePreviewPublisher livePreview();

    LineSynchronizedCaptureCoordinator lineCaptureCoordinator();

    OrchestratorStopSignal stopSignal();

    List<IntegrationComponent> managedRuntimeComponents();
}
