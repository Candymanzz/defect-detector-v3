package com.example.iml.geometry.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BinaryProtocolTest {

    @Test
    void roundTripMessage() throws Exception {
        Map<String, Object> header = Map.of("op", "inspect", "camera_id", 1);
        byte[] payload = new byte[]{7, 8, 9};

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        BinaryProtocol.write(new DataOutputStream(buf), BinaryProtocol.MSG_COMMAND, header, payload);

        BinaryProtocol.Message msg = BinaryProtocol.read(new DataInputStream(new ByteArrayInputStream(buf.toByteArray())));

        assertEquals(BinaryProtocol.MSG_COMMAND, msg.type());
        assertEquals("inspect", msg.header().get("op"));
        assertEquals(1, msg.header().get("camera_id"));
        assertArrayEquals(payload, msg.payload());
    }
}
