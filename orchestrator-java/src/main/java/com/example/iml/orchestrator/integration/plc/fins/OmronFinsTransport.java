package com.example.iml.orchestrator.integration.plc.fins;

import com.example.iml.orchestrator.integration.plc.PlcFinsTrafficEvent;
import com.example.iml.orchestrator.integration.plc.PlcFinsTrafficListener;
import com.example.iml.orchestrator.integration.plc.PlcFinsTrafficSubject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Общий FINS/UDP транспорт: socket, SID, exchange, Observer трафика.
 * Не знает про биты сигналов или слова таймингов — только кадры.
 */
public final class OmronFinsTransport implements AutoCloseable {

  private static final Logger log = LogManager.getLogger(OmronFinsTransport.class);

  private final String host;
  private final int port;
  private final int destNode;
  private final int srcNode;
  private final DatagramSocket socket;
  private final AtomicInteger serviceId = new AtomicInteger(1);
  private final PlcFinsTrafficSubject trafficSubject = new PlcFinsTrafficSubject();

  public OmronFinsTransport(String host, int port, int destNode, int srcNode, int responseTimeoutMs)
      throws IOException {
    this.host = host;
    this.port = port;
    this.destNode = destNode;
    this.srcNode = srcNode;
    this.socket = new DatagramSocket();
    this.socket.setSoTimeout(responseTimeoutMs);
  }

  public int destNode() {
    return destNode;
  }

  public int srcNode() {
    return srcNode;
  }

  public int nextSid() {
    return serviceId.getAndIncrement() & 0xFF;
  }

  public void setTrafficListener(PlcFinsTrafficListener listener) {
    trafficSubject.setObserver(listener);
  }

  public void addTrafficObserver(PlcFinsTrafficListener observer) {
    trafficSubject.addObserver(observer);
  }

  public void removeTrafficObserver(PlcFinsTrafficListener observer) {
    trafficSubject.removeObserver(observer);
  }

  public void emitRequest(
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

  public void emitResponse(
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

  /**
   * Send + receive + validate. Для {@link PlcFinsTrafficEvent#OP_READ_WORDS} success-response
   * не эмитится здесь — вызывающий парсит данные и эмитит сам.
   */
  public byte[] exchange(
      byte[] request,
      int sid,
      String operation,
      String signal,
      String area,
      String address,
      Object value
  ) throws IOException {
    InetAddress target = InetAddress.getByName(host);
    DatagramPacket packet = new DatagramPacket(request, request.length, target, port);
    socket.send(packet);

    byte[] buffer = new byte[512];
    DatagramPacket response = new DatagramPacket(buffer, buffer.length);
    try {
      socket.receive(response);
    } catch (SocketTimeoutException e) {
      log.warn(
          "plc fins response timeout host={}:{} op={} area={} address={} sid={}",
          host,
          port,
          operation,
          area,
          address,
          sid
      );
      emitResponse(operation, signal, area, address, value, null, 0, sid, null, false, "timeout");
      throw new IOException("FINS timeout host=" + host + ":" + port, e);
    }
    try {
      validateResponse(response.getData(), response.getLength(), sid);
    } catch (IOException e) {
      String endCode = extractEndCode(response.getData(), response.getLength());
      emitResponse(
          operation,
          signal,
          area,
          address,
          value,
          response.getData(),
          response.getLength(),
          sid,
          endCode,
          false,
          e.getMessage()
      );
      throw e;
    }
    if (!PlcFinsTrafficEvent.OP_READ_WORDS.equals(operation)) {
      emitResponse(
          operation,
          signal,
          area,
          address,
          value,
          response.getData(),
          response.getLength(),
          sid,
          "0000",
          true,
          null
      );
    }
    InetAddress from = response.getAddress();
    log.info(
        "plc fins response ok host={}:{} from={}:{} op={} area={} address={} sid={} end_code=0000 len={}",
        host,
        port,
        from != null ? from.getHostAddress() : "?",
        response.getPort(),
        operation,
        area,
        address,
        sid,
        response.getLength()
    );
    return Arrays.copyOf(response.getData(), response.getLength());
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

  private static String extractEndCode(byte[] data, int length) {
    if (data == null || length < 14) {
      return null;
    }
    return String.format("%02X%02X", data[12] & 0xFF, data[13] & 0xFF);
  }

  @Override
  public void close() {
    socket.close();
  }
}
