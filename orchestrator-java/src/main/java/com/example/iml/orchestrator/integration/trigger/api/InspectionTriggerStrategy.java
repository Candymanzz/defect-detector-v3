package com.example.iml.orchestrator.integration.trigger.api;

import com.example.iml.orchestrator.integration.trigger.InspectionTriggerEvent;

/**
 * Стратегия ожидания следующего сигнала инспекции (таймер, UDP-шина, непрерывный цикл).
 */
public interface InspectionTriggerStrategy {

    /** Блокируется до следующего триггера для камеры. */
    InspectionTriggerEvent awaitNext(int cameraId) throws InterruptedException;

    /** Пауза после завершённого цикла (непрерывный режим). */
    default int postCycleDelayMs() {
        return 0;
    }

    default void close() {
    }
}
