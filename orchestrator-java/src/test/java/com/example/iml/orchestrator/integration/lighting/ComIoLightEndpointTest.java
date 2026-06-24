package com.example.iml.orchestrator.integration.lighting;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ComIoLightEndpointTest {

    private static final List<ComIoLightEndpoint.BankChannel> BANK_LAYOUT = List.of(
            new ComIoLightEndpoint.BankChannel("COM1", 1),
            new ComIoLightEndpoint.BankChannel("COM1", 2),
            new ComIoLightEndpoint.BankChannel("COM2", 1),
            new ComIoLightEndpoint.BankChannel("COM2", 2),
            new ComIoLightEndpoint.BankChannel("COM2", 3),
            new ComIoLightEndpoint.BankChannel("COM2", 4),
            new ComIoLightEndpoint.BankChannel("COM3", 1),
            new ComIoLightEndpoint.BankChannel("COM3", 2),
            new ComIoLightEndpoint.BankChannel("COM3", 3),
            new ComIoLightEndpoint.BankChannel("COM3", 4)
    );

    @Test
    void formatsBrightnessOnlyForTargetComChannels() {
        assertEquals(
                "0,0,0,0,0,0,80,80,0,0",
                ComIoLightEndpoint.formatBrightnessCsv(80, null, BANK_LAYOUT, "COM3", new int[]{1, 2})
        );
    }

    @Test
    void preservesLegacyBrightnessOverrideForTargetChannels() {
        assertEquals(
                "0,0,0,0,0,0,5,5,0,0",
                ComIoLightEndpoint.formatBrightnessCsv(80, new int[]{5, 5}, BANK_LAYOUT, "COM3", new int[]{1, 2})
        );
    }
}
