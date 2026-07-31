package com.example.iml.orchestrator.integration.plc.fins;

import com.example.iml.orchestrator.integration.plc.PlcFinsTrafficEvent;
import com.example.iml.orchestrator.integration.plc.PlcFinsTrafficSubject;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

/** Traffic emit + FINS response validation for {@link OmronFinsTransport}. */
final class OmronFinsTransportSupport {

    private OmronFinsTransportSupport() {
    }

    static void emitRequest(
            PlcFinsTrafficSubject trafficSubject,
            String operation,
            String signal,
            String area,
            String address,
            Object value,
            byte[] frame,
            int sid
    ) {
        trafficSubject.notifyObservers(new PlcFinsTrafficEvent(
                PlcFinsTrafficEvent.DIRECTION_REQUEST,
                operation,
                signal,
                area,
                address,
                value,
                FinsFrameBuilder.toHex(frame, frame.length),
                sid,
                null,
                true,
                null,
                System.currentTimeMillis()
        ));
    }

    static void emitResponse(
            PlcFinsTrafficSubject trafficSubject,
            String operation,
            String signal,
            String area,
            String address,
            Object value,
            byte[] frame,
            int length,
            int sid,
            String endCode,
            boolean ok,
            String error
    ) {
        trafficSubject.notifyObservers(new PlcFinsTrafficEvent(
                PlcFinsTrafficEvent.DIRECTION_RESPONSE,
                operation,
                signal,
                area,
                address,
                value,
                frame == null ? "" : FinsFrameBuilder.toHex(frame, length),
                sid,
                endCode,
                ok,
                error,
                System.currentTimeMillis()
        ));
    }

    static void validateResponse(byte[] data, int length, int expectedSid, Logger log) throws IOException {
        if (length < 14) {
            throw new IOException("FINS response too short len=" + length);
        }
        int sid = data[9] & 0xFF;
        if (sid != expectedSid) {
            log.warn("plc fins response SID mismatch expected={} actual={} len={}", expectedSid, sid, length);
        }
        int endCodeHi = data[12] & 0xFF;
        int endCodeLo = data[13] & 0xFF;
        if (endCodeHi != 0 || endCodeLo != 0) {
            String endCode = String.format("%02X%02X", endCodeHi, endCodeLo);
            log.warn("plc fins response error sid={} end_code={} len={}", sid, endCode, length);
            throw new IOException("FINS end code " + endCode);
        }
    }

    static String extractEndCode(byte[] data, int length) {
        if (data == null || length < 14) {
            return null;
        }
        return String.format("%02X%02X", data[12] & 0xFF, data[13] & 0xFF);
    }
}
