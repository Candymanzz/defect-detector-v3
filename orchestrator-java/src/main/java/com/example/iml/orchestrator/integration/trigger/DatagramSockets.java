package com.example.iml.orchestrator.integration.trigger;

import java.net.DatagramSocket;

/** Shared UDP socket close helper. */
public final class DatagramSockets {

    private DatagramSockets() {
    }

    public static void closeQuietly(DatagramSocket socket) {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }
}
