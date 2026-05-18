package com.example.iml.orchestrator.integration.clientws.exception;

/**
 * Некорректный дескриптор кадра capture (width/height/stride/shm_offset).
 */
public final class ClientWsInvalidCaptureDescriptorException extends ClientWsException {

    public ClientWsInvalidCaptureDescriptorException(String field) {
        super("invalid capture descriptor: " + field);
    }
}
