package com.example.iml.orchestrator.integration.bootstrap.context.port;

/**
 * Порт запуска camera-worker и client stream.
 * Composed of narrower views for ISP: config / collaborators / sink.
 */
public interface CameraWorkerHost
        extends CameraWorkerConfigView, CameraWorkerCollaboratorView, CameraWorkerSink {
}
