package com.example.iml.orchestrator.integration.smoke;

/** Результат одного шага smoke-теста оборудования. */
public record SmokeResult(String component, String step, boolean passed, String detail) {

  public static SmokeResult ok(String component, String step, String detail) {
    return new SmokeResult(component, step, true, detail);
  }

  public static SmokeResult fail(String component, String step, String detail) {
    return new SmokeResult(component, step, false, detail);
  }

  public static SmokeResult skip(String component, String step, String detail) {
    return new SmokeResult(component, step, true, "SKIP: " + detail);
  }
}
