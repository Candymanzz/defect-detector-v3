package com.example.iml.orchestrator.integration.fanout;

/** Сигнал роботу по одному ведру: groupId и 1/0. */
public record BucketRobotSignal(int groupId, boolean pass) {
}
