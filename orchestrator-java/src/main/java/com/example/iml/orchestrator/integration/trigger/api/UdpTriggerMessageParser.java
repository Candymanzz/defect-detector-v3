package com.example.iml.orchestrator.integration.trigger.api;

import com.example.iml.orchestrator.integration.trigger.InspectionTriggerEvent;

import java.net.InetSocketAddress;
import java.util.Optional;

public interface UdpTriggerMessageParser {

    Optional<InspectionTriggerEvent> parse(byte[] payload, int length, InetSocketAddress remote, int defaultCameraId);
}
