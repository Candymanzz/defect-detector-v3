package com.example.iml.orchestrator.integration.trigger.impl;

import com.example.iml.orchestrator.integration.trigger.gpio.TriggerEdgeMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IoInputMonitor публикует только DI3↑ (Rising-only). Без сброса triggerActive
 * повторные UDP 3:1 молча игнорируются после первого кадра.
 */
class IoInputRisingOnlyRetriggerTest {

    @Test
    void risingOnlyTreatsRepeatedHighAsNewEdge() {
        boolean triggerActive = false;

        assertTrue(isRisingEvent(true, triggerActive, TriggerEdgeMode.RISING));
        triggerActive = releaseAfterRising(true, TriggerEdgeMode.RISING, true);
        assertFalse(triggerActive);

        assertTrue(isRisingEvent(true, triggerActive, TriggerEdgeMode.RISING));
        triggerActive = releaseAfterRising(true, TriggerEdgeMode.RISING, true);
        assertFalse(triggerActive);
    }

    @Test
    void risingOnlyRecoversFromStuckHigh() {
        boolean triggerActive = true; // залип после DI3=1 без DI3=0
        assertTrue(isRisingEvent(true, triggerActive, TriggerEdgeMode.RISING));
        triggerActive = releaseAfterRising(true, TriggerEdgeMode.RISING, true);
        assertFalse(triggerActive);
    }

    @Test
    void levelModeStillNeedsFallingBeforeNextRising() {
        boolean triggerActive = false;
        assertTrue(isRisingEvent(true, triggerActive, TriggerEdgeMode.FALLING));
        triggerActive = releaseAfterRising(true, TriggerEdgeMode.FALLING, true);
        assertTrue(triggerActive);

        assertFalse(isRisingEvent(true, triggerActive, TriggerEdgeMode.FALLING));
        triggerActive = false; // DI3↓
        assertTrue(isRisingEvent(true, triggerActive, TriggerEdgeMode.FALLING));
    }

    private static boolean isRisingEvent(boolean active, boolean triggerActive, TriggerEdgeMode edge) {
        if (!active) {
            return false;
        }
        if (!triggerActive) {
            return true;
        }
        return edge == TriggerEdgeMode.RISING;
    }

    private static boolean releaseAfterRising(boolean active, TriggerEdgeMode edge, boolean afterSet) {
        if (active && edge == TriggerEdgeMode.RISING) {
            return false;
        }
        return afterSet && active;
    }
}
