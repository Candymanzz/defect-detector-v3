package com.example.iml.orchestrator.integration.plc.fins;

import com.example.iml.orchestrator.integration.plc.PlcAddress;
import com.example.iml.orchestrator.integration.plc.PlcFinsTrafficEvent;
import com.example.iml.orchestrator.integration.plc.PlcFinsTrafficListener;
import com.example.iml.orchestrator.integration.plc.PlcMemoryArea;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * FINS/UDP клиент: запись бита и чтение/запись слов DM.
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
  private final AtomicReference<PlcFinsTrafficListener> trafficListener = new AtomicReference<>();

  public OmronFinsClient(String host, int port, int destNode, int srcNode, int responseTimeoutMs) throws IOException {
    this.host = host;
    this.port = port;
    this.destNode = destNode;
    this.srcNode = srcNode;
    this.responseTimeoutMs = responseTimeoutMs;
    this.socket = new DatagramSocket();
    this.socket.setSoTimeout(responseTimeoutMs);
  }

  public void setTrafficListener(PlcFinsTrafficListener listener) {
    trafficListener.set(listener);
  }

  public void writeBit(PlcMemoryArea area, PlcAddress address, boolean value) throws IOException {
    writeBit(area, address, value, null);
  }

  public void writeBit(PlcMemoryArea area, PlcAddress address, boolean value, String signal) throws IOException {
    int sid = nextSid();
    byte[] request = FinsFrameBuilder.buildWriteBit(destNode, srcNode, sid, area, address, value);
    String addressLabel = address.word() + "." + address.bit();
    emitRequest(PlcFinsTrafficEvent.OP_WRITE_BIT, signal, area.name(), addressLabel, value, request, sid);
    exchange(request, sid, PlcFinsTrafficEvent.OP_WRITE_BIT, signal, area.name(), addressLabel, value);
  }

  public void writeWords(PlcMemoryArea area, int startWord, int[] words, String signal) throws IOException {
    int sid = nextSid();
    byte[] request = FinsFrameBuilder.buildWriteWords(destNode, srcNode, sid, area, startWord, words);
    String addressLabel = formatWordRange(area, startWord, words.length);
    emitRequest(
        PlcFinsTrafficEvent.OP_WRITE_WORDS,
        signal,
        area.name(),
        addressLabel,
        Arrays.stream(words).boxed().toList(),
        request,
        sid
    );
    exchange(
        request,
        sid,
        PlcFinsTrafficEvent.OP_WRITE_WORDS,
        signal,
        area.name(),
        addressLabel,
        Arrays.stream(words).boxed().toList()
    );
  }

  public int[] readWords(PlcMemoryArea area, int startWord, int count, String signal) throws IOException {
    int sid = nextSid();
    byte[] request = FinsFrameBuilder.buildReadWords(destNode, srcNode, sid, area, startWord, count);
    String addressLabel = formatWordRange(area, startWord, count);
    emitRequest(PlcFinsTrafficEvent.OP_READ_WORDS, signal, area.name(), addressLabel, count, request, sid);
    byte[] response = exchangeRaw(request, sid, PlcFinsTrafficEvent.OP_READ_WORDS, signal, area.name(), addressLabel, count);
    int[] words = FinsFrameBuilder.parseReadWordsData(response, response.length, count);
    emitResponse(
        PlcFinsTrafficEvent.OP_READ_WORDS,
        signal,
        area.name(),
        addressLabel,
        Arrays.stream(words).boxed().toList(),
        response,
        response.length,
        sid,
        "0000",
        true,
        null
    );
    return words;
  }

  private void exchange(
      byte[] request,
      int sid,
      String operation,
      String signal,
      String area,
      String address,
      Object value
  ) throws IOException {
    exchangeRaw(request, sid, operation, signal, area, address, value);
  }

  private byte[] exchangeRaw(
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

  private void emitRequest(
      String operation,
      String signal,
      String area,
      String address,
      Object value,
      byte[] frame,
      int sid
  ) {
    emit(new PlcFinsTrafficEvent(
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

  private void emitResponse(
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
    emit(new PlcFinsTrafficEvent(
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

  private void emit(PlcFinsTrafficEvent event) {
    PlcFinsTrafficListener listener = trafficListener.get();
    if (listener == null) {
      return;
    }
    try {
      listener.onTraffic(event);
    } catch (Exception e) {
      log.debug("plc fins traffic listener error: {}", e.getMessage());
    }
  }

  private int nextSid() {
    return serviceId.getAndIncrement() & 0xFF;
  }

  private static String formatWordRange(PlcMemoryArea area, int startWord, int count) {
    String prefix = area == PlcMemoryArea.DM ? "D" : area.name();
    if (count <= 1) {
      return prefix + startWord;
    }
    return prefix + startWord + ".." + (startWord + count - 1);
  }

  private static String extractEndCode(byte[] data, int length) {
    if (data == null || length < 14) {
      return null;
    }
    return String.format("%02X%02X", data[12] & 0xFF, data[13] & 0xFF);
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
