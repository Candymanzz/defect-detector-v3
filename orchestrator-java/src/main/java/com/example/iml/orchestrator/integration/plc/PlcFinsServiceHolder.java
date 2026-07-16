package com.example.iml.orchestrator.integration.plc;

/**
 * Отложенная привязка {@link PlcFinsApi} к HTTP (UI поднимается раньше FanOut).
 */
public final class PlcFinsServiceHolder {

  private volatile PlcFinsApi service;

  public PlcFinsApi get() {
    return service;
  }

  public void set(PlcFinsApi service) {
    this.service = service;
  }
}
