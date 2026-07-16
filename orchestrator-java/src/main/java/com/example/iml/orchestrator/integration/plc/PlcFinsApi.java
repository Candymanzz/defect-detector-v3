package com.example.iml.orchestrator.integration.plc;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * API ПЛК для HTTP/UI: таймауты D4400–D4404, ручные сигналы и состояние редактирования.
 */
public interface PlcFinsApi {

  boolean enabled();

  boolean inspectionInFlight();

  boolean inspectionEnabled();

  /**
   * Ручное управление (таймауты + сигналы) разрешено, пока инспекция не запущена
   * и нет цикла in-flight.
   */
  boolean manualControlEditable();

  /** @deprecated use {@link #manualControlEditable()} */
  default boolean timeoutsEditable() {
    return manualControlEditable();
  }

  List<PlcTimeoutDefinition> timeoutDefinitions();

  List<PlcTimeoutState> readTimeouts() throws IOException, InterruptedException, TimeoutException;

  /**
   * @param unitsByKey ключ — {@code D4400} / имя сигнала / индекс; значение — единицы 100 ms (десятичные).
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
