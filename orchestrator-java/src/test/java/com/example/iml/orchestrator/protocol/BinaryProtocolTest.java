package com.example.iml.orchestrator.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinaryProtocolTest {

    @Test
    void roundTripPreservesTypeHeaderAndPayload() throws IOException {
        Map<String, Object> header = Map.of(
                "camera_id", 3,
                "frame_id", 42L,
                "status", "PASS"
        );
        byte[] payload = new byte[]{1, 2, 3, 4};

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        BinaryProtocol.write(new DataOutputStream(buf), BinaryProtocol.MSG_RESPONSE, header, payload);

        BinaryProtocol.Message msg = BinaryProtocol.read(new DataInputStream(new ByteArrayInputStream(buf.toByteArray())));

        assertEquals(BinaryProtocol.MSG_RESPONSE, msg.type());
        assertEquals(3, msg.header().get("camera_id"));
        assertEquals(42, ((Number) msg.header().get("frame_id")).longValue());
        assertEquals("PASS", msg.header().get("status"));
        assertArrayEquals(payload, msg.payload());
    }

    @Test
    void writeWithNullPayloadUsesEmptyBody() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        BinaryProtocol.write(new DataOutputStream(buf), BinaryProtocol.MSG_COMMAND, Map.of("op", "health"), null);

        BinaryProtocol.Message msg = BinaryProtocol.read(new DataInputStream(new ByteArrayInputStream(buf.toByteArray())));

        assertEquals(BinaryProtocol.MSG_COMMAND, msg.type());
        assertEquals("health", msg.header().get("op"));
        assertEquals(0, msg.payload().length);
    }

    @Test
    void readRejectsBadMagic() {
        byte[] bad = new byte[]{0, 1, 2, 3, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0};
        IOException ex = assertThrows(IOException.class, () ->
                BinaryProtocol.read(new DataInputStream(new ByteArrayInputStream(bad))));
        assertTrue(ex.getMessage().contains("Bad magic"));
    }

    @Test
    void readRejectsUnsupportedVersion() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buf);
        out.write(new byte[]{'I', 'M', 'L', 'B'});
        out.writeByte(99);
        out.writeByte(BinaryProtocol.MSG_RESPONSE);
        out.writeShort(0);
        out.writeInt(0);
        out.writeInt(0);
        out.flush();

        IOException ex = assertThrows(IOException.class, () ->
                BinaryProtocol.read(new DataInputStream(new ByteArrayInputStream(buf.toByteArray()))));
        assertTrue(ex.getMessage().contains("Unsupported protocol version"));
    }
}
