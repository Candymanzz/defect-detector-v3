package com.example.iml.orchestrator.integration.plc;

/**
 * Событие обмена FINS для UI (запрос к ПЛК или ответ).
 */
public record PlcFinsTrafficEvent(
    String direction,
    String operation,
    String signal,
    String area,
    String address,
    Object value,
    String hexFrame,
    Integer sid,
    String endCode,
    boolean ok,
    String error,
    long serverTsMs
) {
  public static final String DIRECTION_REQUEST = "request";
  public static final String DIRECTION_RESPONSE = "response";

  public static final String OP_WRITE_BIT = "write_bit";
  public static final String OP_WRITE_WORDS = "write_words";
  public static final String OP_READ_WORDS = "read_words";
}
