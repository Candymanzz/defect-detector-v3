package com.example.iml.orchestrator.integration.io.mvs;

/** Validates the existing controller route; never rewires outputs or changes timer timing. */
final class MvsTimerCommand {
    interface Nodes {
        String get(String node);
        void select(String node, String value);
        int access(String node);
        void command(String node);
        void restore(String node, String value);
    }
    static void trigger(String timer, String line, Nodes nodes) {
        if (!timer.matches("Timer[1-8]") || timer.equals("Timer5") || !line.matches("Out[1-8]") || line.equals("Out5"))
            throw new IllegalArgumentException("Camera Timer5/Out5 protected; specify Timer1..8 and Out1..8");
        String oldTimer = nodes.get("TimerSelector"), oldLine = nodes.get("LineSelector");
        try {
            nodes.select("TimerSelector", timer);
            if (!nodes.get("TimerTriggerSource").equals("Software"))
                throw new IllegalStateException(timer + ": configure TimerTriggerSource=Software in MVS");
            nodes.select("LineSelector", line);
            if (!nodes.get("LineSource").equals(timer))
                throw new IllegalStateException(line + ": configure LineSource=" + timer + " in MVS");
            int access = nodes.access("TriggerSoftware");
            if (access != 2 && access != 4)
                throw new IllegalStateException(timer + ": TriggerSoftware is not writable; check TimerMode in MVS");
            nodes.command("TriggerSoftware");
        } finally {
            // A restoration failure must never hide an accepted pulse and cause duplicate delivery.
            nodes.restore("LineSelector", oldLine);
            nodes.restore("TimerSelector", oldTimer);
        }
    }
}
