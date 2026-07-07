package com.example.iml.orchestrator.integration.trigger;

import java.time.Instant;

/** Вызывается сразу при line-broadcast, до постановки событий в очереди камер. */
@FunctionalInterface
public interface LineTriggerListener {

    void onLineTrigger(long triggerSequence, Instant receivedAt);
}
