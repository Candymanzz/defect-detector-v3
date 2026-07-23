package com.example.iml.orchestrator.integration.plc.fins;

import com.example.iml.orchestrator.integration.plc.PlcFinsTrafficEvent;
import com.example.iml.orchestrator.integration.plc.PlcMemoryArea;

import java.io.IOException;
import java.util.Arrays;

/**
 * FINS-операции для таймингов / регистров словами (обычно DM D4400…): read/write words.
 */
public final class OmronFinsTimingAccess {

  private final OmronFinsTransport transport;

  public OmronFinsTimingAccess(OmronFinsTransport transport) {
    this.transport = transport;
  }

  public void writeWords(PlcMemoryArea area, int startWord, int[] words, String signal) throws IOException {
    int sid = transport.nextSid();
    byte[] request = FinsFrameBuilder.buildWriteWords(
        transport.destNode(), transport.srcNode(), sid, area, startWord, words);
    String addressLabel = formatWordRange(area, startWord, words.length);
    Object value = Arrays.stream(words).boxed().toList();
    transport.emitRequest(
        PlcFinsTrafficEvent.OP_WRITE_WORDS, signal, area.name(), addressLabel, value, request, sid);
    transport.exchange(
        request, sid, PlcFinsTrafficEvent.OP_WRITE_WORDS, signal, area.name(), addressLabel, value);
  }

  public int[] readWords(PlcMemoryArea area, int startWord, int count, String signal) throws IOException {
    int sid = transport.nextSid();
    byte[] request = FinsFrameBuilder.buildReadWords(
        transport.destNode(), transport.srcNode(), sid, area, startWord, count);
    String addressLabel = formatWordRange(area, startWord, count);
    transport.emitRequest(
        PlcFinsTrafficEvent.OP_READ_WORDS, signal, area.name(), addressLabel, count, request, sid);
    byte[] response = transport.exchange(
        request, sid, PlcFinsTrafficEvent.OP_READ_WORDS, signal, area.name(), addressLabel, count);
    int[] words = FinsFrameBuilder.parseReadWordsData(response, response.length, count);
    transport.emitResponse(
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

  private static String formatWordRange(PlcMemoryArea area, int startWord, int count) {
    String prefix = area == PlcMemoryArea.DM ? "D" : area.name();
    if (count <= 1) {
      return prefix + startWord;
    }
    return prefix + startWord + ".." + (startWord + count - 1);
  }
}
