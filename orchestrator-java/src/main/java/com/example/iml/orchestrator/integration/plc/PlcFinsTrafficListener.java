package com.example.iml.orchestrator.integration.plc;

/**
 * Observer паттерна Observer: подписчик на {@link PlcFinsTrafficEvent}
 * (запрос/ответ FINS или DO→DI через IoInputMonitor).
 */
@FunctionalInterface
public interface PlcFinsTrafficListener {
  void onTraffic(PlcFinsTrafficEvent event);
}
