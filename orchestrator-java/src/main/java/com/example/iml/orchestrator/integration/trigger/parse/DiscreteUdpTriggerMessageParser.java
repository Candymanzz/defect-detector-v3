package com.example.iml.orchestrator.integration.trigger.parse;

import com.example.iml.orchestrator.integration.trigger.InspectionTriggerEvent;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * PLC discrete input: {@code 0} = idle, {@code 1} = line-wide inspection trigger (all cameras).
 * Accepts one raw byte {@code 0x00}/{@code 0x01} or UTF-8 text {@code "0"}/{@code "1"}.
 */
public final class DiscreteUdpTriggerMessageParser implements UdpTriggerMessageParser {

    @Override
    public Optional<InspectionTriggerEvent> parse(
            byte[] payload,
            int length,
            InetSocketAddress remote,
            int defaultCameraId
    ) {
        int value = parseDiscrete(payload, length);
        if (value < 0) {
            return Optional.empty();
        }
        if (value == 0) {
            return Optional.empty();
        }
        return Optional.of(InspectionTriggerEvent.lineBroadcast("udp"));
    }

    private static int parseDiscrete(byte[] payload, int length) {
        if (payload == null || length <= 0) {
            return -1;
        }
        if (length == 1) {
            int b = payload[0] & 0xFF;
            if (b == 0 || b == 1) {
                return b;
            }
            return -1;
        }
        String text = new String(payload, 0, length, StandardCharsets.UTF_8).trim();
        if ("0".equals(text)) {
            return 0;
        }
        if ("1".equals(text)) {
            return 1;
        }
        return -1;
    }
}
