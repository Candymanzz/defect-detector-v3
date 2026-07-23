package com.example.iml.orchestrator.integration.plc.fins;

import com.example.iml.orchestrator.integration.plc.PlcFinsTrafficListener;

import java.io.IOException;

/**
 * Фасад FINS/UDP: общий транспорт + две логические единицы —
 * {@link #signals()} (биты CIO) и {@link #timings()} (слова DM / таймауты).
 */
public final class OmronFinsClient implements AutoCloseable {

  private final OmronFinsTransport transport;
  private final OmronFinsSignalAccess signals;
  private final OmronFinsTimingAccess timings;

  public OmronFinsClient(String host, int port, int destNode, int srcNode, int responseTimeoutMs)
      throws IOException {
    this.transport = new OmronFinsTransport(host, port, destNode, srcNode, responseTimeoutMs);
    this.signals = new OmronFinsSignalAccess(transport);
    this.timings = new OmronFinsTimingAccess(transport);
  }

  /** Дискретные сигналы (reject / fault / …). */
  public OmronFinsSignalAccess signals() {
    return signals;
  }

  /** Тайминги и прочие word-регистры (обычно DM). */
  public OmronFinsTimingAccess timings() {
    return timings;
  }

  public void setTrafficListener(PlcFinsTrafficListener listener) {
    transport.setTrafficListener(listener);
  }

  public void addTrafficObserver(PlcFinsTrafficListener observer) {
    transport.addTrafficObserver(observer);
  }

  public void removeTrafficObserver(PlcFinsTrafficListener observer) {
    transport.removeTrafficObserver(observer);
  }

  @Override
  public void close() {
    transport.close();
  }
}
