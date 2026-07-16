package com.example.iml.orchestrator.integration.plc.fins;

import com.example.iml.orchestrator.integration.plc.PlcAddress;
import com.example.iml.orchestrator.integration.plc.PlcMemoryArea;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Минимальный FINS/UDP клиент: запись одного бита в область памяти Omron.
 */
public final class OmronFinsClient implements AutoCloseable {

  private static final Logger log = LogManager.getLogger(OmronFinsClient.class);

  private final String host;
  private final int port;
  private final int destNode;
  private final int srcNode;
  private final int responseTimeoutMs;
  private final DatagramSocket socket;
  private final AtomicInteger serviceId = new AtomicInteger(1);

  public OmronFinsClient(String host, int port, int destNode, int srcNode, int responseTimeoutMs) throws IOException {
    this.host = host;
    this.port = port;
    this.destNode = destNode;
    this.srcNode = srcNode;
    this.responseTimeoutMs = responseTimeoutMs;
    this.socket = new DatagramSocket();
    this.socket.setSoTimeout(responseTimeoutMs);
  }

  public void writeBit(PlcMemoryArea area, PlcAddress address, boolean value) throws IOException {
    int sid = serviceId.getAndIncrement() & 0xFF;
    byte[] request = FinsFrameBuilder.buildWriteBit(destNode, srcNode, sid, area, address, value);
    InetAddress target = InetAddress.getByName(host);
    DatagramPacket packet = new DatagramPacket(request, request.length, target, port);
    socket.send(packet);

    byte[] buffer = new byte[256];
    DatagramPacket response = new DatagramPacket(buffer, buffer.length);
    try {
      socket.receive(response);
    } catch (SocketTimeoutException e) {
      log.warn(
          "plc fins response timeout host={}:{} area={} address={}.{} value={} sid={}",
          host,
          port,
          area,
          address.word(),
          address.bit(),
          value,
          sid
      );
      throw new IOException("FINS timeout host=" + host + ":" + port, e);
    }
    validateResponse(response.getData(), response.getLength(), sid);
    InetAddress from = response.getAddress();
    log.info(
        "plc fins response ok host={}:{} from={}:{} area={} address={}.{} value={} sid={} end_code=0000 len={}",
        host,
        port,
        from != null ? from.getHostAddress() : "?",
        response.getPort(),
        area,
        address.word(),
        address.bit(),
        value,
        sid,
        response.getLength()
    );
  }

  static void validateResponse(byte[] data, int length, int expectedSid) throws IOException {
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

  @Override
  public void close() {
    socket.close();
  }
}
