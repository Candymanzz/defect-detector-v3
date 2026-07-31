package com.example.iml.orchestrator.integration.plc.fins;

import com.example.iml.orchestrator.integration.plc.PlcFinsTrafficEvent;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;

final class FinsExchange {
    private FinsExchange() {
    }

    static byte[] execute(
            Logger log, String host, int port, DatagramSocket socket, OmronFinsTransport transport,
            byte[] request, int sid, String operation, String signal, String area, String address, Object value
    ) throws IOException {
        InetAddress target = InetAddress.getByName(host);
        socket.send(new DatagramPacket(request, request.length, target, port));
        DatagramPacket response = new DatagramPacket(new byte[512], 512);
        try {
            socket.receive(response);
        } catch (SocketTimeoutException e) {
            log.warn("plc fins response timeout host={}:{} op={} area={} address={} sid={}",
                    host, port, operation, area, address, sid);
            transport.emitResponse(operation, signal, area, address, value, null, 0, sid, null, false, "timeout");
            throw new IOException("FINS timeout host=" + host + ":" + port, e);
        }
        try {
            OmronFinsTransport.validateResponse(response.getData(), response.getLength(), sid);
        } catch (IOException e) {
            transport.emitResponse(operation, signal, area, address, value, response.getData(), response.getLength(),
                    sid, extractEndCode(response.getData(), response.getLength()), false, e.getMessage());
            throw e;
        }
        if (!PlcFinsTrafficEvent.OP_READ_WORDS.equals(operation)) {
            transport.emitResponse(operation, signal, area, address, value, response.getData(), response.getLength(),
                    sid, "0000", true, null);
        }
        InetAddress from = response.getAddress();
        log.info("plc fins response ok host={}:{} from={}:{} op={} area={} address={} sid={} end_code=0000 len={}",
                host, port, from != null ? from.getHostAddress() : "?", response.getPort(), operation, area,
                address, sid, response.getLength());
        return Arrays.copyOf(response.getData(), response.getLength());
    }

    private static String extractEndCode(byte[] data, int length) {
        if (data == null || length < 14) {
            return null;
        }
        return String.format("%02X%02X", data[12] & 0xFF, data[13] & 0xFF);
    }
}
