package com.example.iml.orchestrator.integration.io.mvs;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MvsTimerCommandTest {
    static final class Fake implements MvsTimerCommand.Nodes {
        final Map<String, String> state = new HashMap<>(Map.of("TimerSelector", "Timer5", "LineSelector", "In3", "TimerTriggerSource", "Software", "LineSource", "Timer6"));
        final List<String> commands = new ArrayList<>();
        int access = 2;
        boolean fail;
        public String get(String node) { return state.get(node); }
        public void select(String node, String value) { state.put(node, value); }
        public int access(String node) { assertEquals("TriggerSoftware", node); return access; }
        public void command(String node) {
            assertEquals("Timer6", state.get("TimerSelector"));
            assertEquals("Out6", state.get("LineSelector"));
            if (fail) throw new IllegalStateException("SDK failed");
            commands.add(node);
        }
        public void restore(String node, String value) { state.put(node, value); }
    }
    @Test void onePulseUsesSelectedTimerAndRestoresSelectors() {
        Fake fake = new Fake();
        MvsTimerCommand.trigger("Timer6", "Out6", fake);
        assertEquals(List.of("TriggerSoftware"), fake.commands);
        assertEquals("Timer5", fake.state.get("TimerSelector"));
        assertEquals("In3", fake.state.get("LineSelector"));
    }
    @Test void wrongRouteOrSourceOrUnavailableCommandNeverPulses() {
        for (int condition = 0; condition < 3; condition++) {
            Fake fake = new Fake();
            if (condition == 0) fake.state.put("LineSource", "Timer5");
            if (condition == 1) fake.state.put("TimerTriggerSource", "In3");
            if (condition == 2) fake.access = 3;
            assertThrows(IllegalStateException.class, () -> MvsTimerCommand.trigger("Timer6", "Out6", fake));
            assertTrue(fake.commands.isEmpty());
            assertEquals("Timer5", fake.state.get("TimerSelector"));
            assertEquals("In3", fake.state.get("LineSelector"));
        }
    }
    @Test void commandFailureRestoresStateAndCameraRouteIsProtected() {
        Fake fake = new Fake(); fake.fail = true;
        assertThrows(IllegalStateException.class, () -> MvsTimerCommand.trigger("Timer6", "Out6", fake));
        assertEquals("Timer5", fake.state.get("TimerSelector"));
        assertThrows(IllegalArgumentException.class, () -> MvsTimerCommand.trigger("Timer5", "Out6", fake));
        assertThrows(IllegalArgumentException.class, () -> MvsTimerCommand.trigger("Timer6", "Out5", fake));
    }
}
