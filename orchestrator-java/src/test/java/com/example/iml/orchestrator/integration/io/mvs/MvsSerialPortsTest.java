package com.example.iml.orchestrator.integration.io.mvs;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class MvsSerialPortsTest {
    @Test void bothDevicesRemainInEveryEnumerationAndUseSdkStructureStride() {
        MvsSdkLease.registerSerialPorts(List.of("COM_Port#ttyS1", "COM_Port#ttyS4"));
        for (String requested : List.of("COM_Port#ttyS1", "COM_Port#ttyS4")) {
            try (var ports = MvsSdkLease.serialPortList(requested)) {
                var names = new HashSet<String>();
                for (int i = 0; i < ports.getInt(0); i++) {
                    names.add(ports.getString(4L + 80L * i));
                    for (int r = 0; r < 4; r++) assertEquals(0, ports.getInt(4L + 80L * i + 64 + 4L * r));
                }
                assertTrue(names.containsAll(List.of("COM_Port#ttyS1", "COM_Port#ttyS4")));
                assertEquals(names.size(), ports.getInt(0));
            }
        }
    }
}
