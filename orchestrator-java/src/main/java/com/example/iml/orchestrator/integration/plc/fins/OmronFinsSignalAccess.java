package com.example.iml.orchestrator.integration.plc.fins;

import com.example.iml.orchestrator.integration.plc.PlcAddress;
import com.example.iml.orchestrator.integration.plc.PlcFinsTrafficEvent;
import com.example.iml.orchestrator.integration.plc.PlcMemoryArea;

import java.io.IOException;

/**
 * FINS-операции для дискретных сигналов (CIO/биты): reject, fault и т.п.
 */
public final class OmronFinsSignalAccess {

  private final OmronFinsTransport transport;

  public OmronFinsSignalAccess(OmronFinsTransport transport) {
    this.transport = transport;
  }

  public void writeBit(PlcMemoryArea area, PlcAddress address, boolean value, String signal) throws IOException {
    int sid = transport.nextSid();
    byte[] request = FinsFrameBuilder.buildWriteBit(
        transport.destNode(), transport.srcNode(), sid, area, address, value);
    String addressLabel = address.word() + "." + address.bit();
    transport.emitRequest(
        PlcFinsTrafficEvent.OP_WRITE_BIT, signal, area.name(), addressLabel, value, request, sid);
    transport.exchange(
        request, sid, PlcFinsTrafficEvent.OP_WRITE_BIT, signal, area.name(), addressLabel, value);
  }
}
