package com.example.iml.orchestrator.integration.plc;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * API ПЛК для HTTP/UI: таймауты D4400–D4404, флаги DM (0/1), биты W0 / CIO из register-map.
 */
public interface PlcFinsApi {

  boolean enabled();

  boolean inspectionInFlight();

  /**
   * Для UI ПЛК: {@code true}, если задан эталон ({@code session_state != NO_REFERENCE}).
   */
  boolean inspectionEnabled();

  /**
   * Ручные сигналы разрешены, пока нет эталона и нет цикла in-flight.
   */
  boolean manualControlEditable();

  /**
   * Тайминги / флаги DM можно менять при включённом FINS, в том числе во время инспекции.
   */
  boolean timeoutsEditable();

  List<PlcTimeoutDefinition> timeoutDefinitions();

  List<PlcTimeoutState> readTimeouts() throws IOException, InterruptedException, TimeoutException;

  /**
   * Пишет только переданные ключи (отдельные слова DM), остальные не трогает.
   *
   * @param unitsByKey ключ — {@code D4400} / имя; значение — единицы 100 ms (BCD) или 0/1 для {@code unit: flag}
   */
  List<PlcTimeoutState> writeTimeouts(Map<String, Integer> unitsByKey)
      throws IOException, InterruptedException, TimeoutException;

  List<PlcSignalState> listSignals();

  /**
   * Ручная запись битовых сигналов из register-map ({@code vision_ready}, {@code reject_line_1}, …).
   *
   * @param valuesByName имя → значение
   * @param pulseByName  для {@code true} — импульс (как reject при fail), иначе уровень
   */
  List<PlcSignalState> writeSignals(Map<String, Boolean> valuesByName, Map<String, Boolean> pulseByName)
      throws IOException, InterruptedException, TimeoutException;
}
