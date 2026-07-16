package com.example.iml.orchestrator.integration.plc;

@FunctionalInterface
public interface PlcFinsTrafficListener {
  void onTraffic(PlcFinsTrafficEvent event);
}
