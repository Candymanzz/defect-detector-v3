package com.example.iml.orchestrator.integration.io.mvs;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import java.util.*;

/** Read-only diagnostics for DI and DO; never sends an output command. */
public final class MvsIoDiagnosticMain {
    public static void main(String[] args) throws Exception {
        Map<String, Object> options = new HashMap<>();
        String mode = "--check";
        java.nio.file.Path xmlPath = null;
        int scans = 0;
        long listenMs = 0;
        java.nio.file.Path configPath = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (Set.of("--check", "--list").contains(arg)) mode = arg;
            else if (arg.equals("--config") && i + 1 < args.length) configPath = java.nio.file.Path.of(args[++i]);
            else if (arg.equals("--listen-ms") && i + 1 < args.length) listenMs = Long.parseLong(args[++i]);
            else if (arg.equals("--xml") && i + 1 < args.length) xmlPath = java.nio.file.Path.of(args[++i]);
            else if (arg.equals("--scan") && i + 1 < args.length) scans = Integer.parseInt(args[++i]);
            else if (Set.of("--port", "--serial", "--library").contains(arg) && i + 1 < args.length)
                options.put(arg.substring(2), args[++i]);
            else throw new IllegalArgumentException("Usage: MvsIoDiagnosticMain [--list|--check] [--port SDK_NAME] [--serial SN] [--library PATH]");
        }
        Map<String, Object> integration = Map.of();
        if (configPath != null) {
            Map<String, Object> root = new com.example.iml.orchestrator.config.YamlFileConfigLoader().load(configPath);
            if (root.get("integration") instanceof Map<?, ?> raw) {
                @SuppressWarnings("unchecked") Map<String, Object> parsed = (Map<String, Object>) raw;
                integration = parsed;
            }
            if (!options.isEmpty()) throw new IllegalArgumentException("Use either --config or port/library overrides");
        } else integration = Map.of("inspection_trigger", Map.of("mvs_io", options));
        MvsIoConfig cfg = MvsIoConfig.parse(integration);
        var logger = LogManager.getLogger(MvsIoDiagnosticMain.class);
        if (listenMs < 0) throw new IllegalArgumentException("--listen-ms must be positive");
        if (listenMs > 0) {
            if (!cfg.inputMode().equals("events")) throw new IllegalArgumentException("--listen-ms requires events mode");
            int triggerPort = com.example.iml.orchestrator.integration.trigger.config.InspectionTriggerConfig.parse(integration).ioInput().triggerPort();
            try (MvsIoTransport transport = new MvsIoTransport(cfg, triggerPort, logger,
                    () -> MvsIoDevices.create(cfg, logger), change -> {
                        if (change != null) System.out.println("DI" + change.diPort() + "=" + (change.active() ? 1 : 0));
                    })) {
                transport.start(); Thread.sleep(listenMs);
                if (!transport.isConnected()) throw new IllegalStateException("Event IO did not connect; see connection errors");
            }
            return;
        }
        try (MvsIoDevice device = MvsIoDevices.create(cfg, logger)) {
            if (mode.equals("--list")) {
                if (!(device instanceof JnaMvsIoDevice camera)) throw new IllegalArgumentException("mv_io uses an explicit port; no enumeration API");
                System.out.println("matching_mv_vc_devices=" + camera.listDevices());
            } else {
                if (device instanceof JnaMvsIoDevice camera) camera.open(false); else device.open();
                Map<String, Object> details = device instanceof JnaMvsIoDevice camera ? camera.inspect()
                        : Map.of("inputs", device.readInputs(), "backend", cfg.backend(), "port", cfg.port());
                System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(details));
                if (xmlPath != null) {
                    if (!(device instanceof JnaMvsIoDevice camera)) throw new IllegalArgumentException("XML is available only with mv_camera");
                    java.nio.file.Files.write(xmlPath, camera.xml());
                }
                if (scans > 0) {
                    long total = 0, max = 0;
                    for (int scan = 0; scan < scans; scan++) {
                        long start = System.nanoTime(); device.readInputs(); device.readOutputs(); long elapsed = System.nanoTime() - start;
                        total += elapsed; max = Math.max(max, elapsed);
                    }
                    System.out.println("di_do_scan_avg_ms=" + total / 1_000_000.0 / scans + "; di_do_scan_max_ms=" + max / 1_000_000.0);
                }
            }
        }
    }
}
