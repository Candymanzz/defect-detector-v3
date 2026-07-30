package com.example.iml.orchestrator.integration.subprocess;

import com.example.iml.orchestrator.integration.config.CameraWorkerPaths;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Автозапуск внешних процессов из {@code integration.<name>_autostart} и {@code integration.<name>_command_*}.
 */
public final class IntegrationExternalProcessLauncher {

    private final Logger log;

    public IntegrationExternalProcessLauncher(Logger log) {
        this.log = log;
    }

    public record AutostartSettings(boolean enabled, int startupDelayMs, Path workingDir) {
    }

    public AutostartSettings parseAutostart(
            Map<String, Object> integration,
            String blockKey,
            Path projectRoot,
            String defaultRelativeWorkingDir
    ) {
        if (integration == null) {
            return new AutostartSettings(false, 0, projectRoot.resolve(defaultRelativeWorkingDir).normalize());
        }
        Object raw = integration.get(blockKey);
        if (!(raw instanceof Map<?, ?> settings)) {
            return new AutostartSettings(false, 0, projectRoot.resolve(defaultRelativeWorkingDir).normalize());
        }
        boolean enabled = YamlScalars.toBool(settings.get("enabled"), false);
        int startupDelayMs = Math.max(0, YamlScalars.toInt(settings.get("startup_delay_ms"), 0));
        String workingDirRel = settings.get("working_dir") != null
                ? String.valueOf(settings.get("working_dir")).trim()
                : defaultRelativeWorkingDir;
        Path workingDir = projectRoot.resolve(workingDirRel).normalize();
        return new AutostartSettings(enabled, startupDelayMs, workingDir);
    }

    public ExternalServiceProcess startIfConfigured(
            Map<String, Object> integration,
            Path projectRoot,
            boolean isWindows,
            String autostartBlockKey,
            String commandWindowsKey,
            String commandLinuxKey,
            String serviceName,
            String defaultRelativeWorkingDir
    ) {
        AutostartSettings autostart = parseAutostart(
                integration,
                autostartBlockKey,
                projectRoot,
                defaultRelativeWorkingDir
        );
        if (!autostart.enabled()) {
            log.info("{} autostart disabled", serviceName);
            return null;
        }
        List<String> command = CameraWorkerPaths.pickIntegrationCommandList(
                integration,
                isWindows,
                commandWindowsKey,
                commandLinuxKey
        );
        if (command.isEmpty()) {
            log.warn("{} autostart enabled but command list is empty ({}/{})",
                    serviceName, commandWindowsKey, commandLinuxKey);
            return null;
        }
        Path workingDir = autostart.workingDir();
        if (!Files.isDirectory(workingDir)) {
            log.warn("{} autostart skipped — working directory not found: {}", serviceName, workingDir);
            return null;
        }
        try {
            List<String> launchCommand = prepareCommand(command, isWindows);
            Map<String, String> extraEnv = prepareChildEnv(launchCommand, isWindows);
            ExternalServiceProcess process = ExternalServiceProcess.start(
                    serviceName, launchCommand, workingDir, extraEnv);
            if (autostart.startupDelayMs() > 0) {
                Thread.sleep(autostart.startupDelayMs());
            }
            log.info("started {} command={} cwd={}", serviceName, launchCommand, workingDir);
            return process;
        } catch (Exception e) {
            log.warn("failed to start {} command={}: {}", serviceName, command, e.getMessage());
            return null;
        }
    }

    /** Windows: {@code npm} — shell-скрипт; ProcessBuilder требует {@code npm.cmd}. */
    static List<String> prepareCommand(List<String> command, boolean isWindows) {
        if (!isWindows || command.isEmpty()) {
            return command;
        }
        String exe = command.get(0);
        if (!"npm".equalsIgnoreCase(exe) && !"npx".equalsIgnoreCase(exe)) {
            return command;
        }
        String resolved = resolveWindowsExecutable(exe + ".cmd");
        if (resolved == null) {
            return command;
        }
        List<String> out = new ArrayList<>(command);
        out.set(0, resolved);
        return out;
    }

    static Map<String, String> prepareChildEnv(List<String> command, boolean isWindows) {
        if (!isWindows || command.isEmpty()) {
            return Map.of();
        }
        String exe = Path.of(command.get(0)).getFileName().toString().toLowerCase();
        if (!exe.startsWith("npm") && !exe.startsWith("npx") && !exe.equals("node.exe")) {
            return Map.of();
        }
        String nodeDir = resolveNodeJsDir();
        if (nodeDir == null) {
            return Map.of();
        }
        String path = System.getenv("PATH");
        if (path != null && path.toLowerCase().contains(nodeDir.toLowerCase())) {
            return Map.of();
        }
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PATH", nodeDir + ";" + (path != null ? path : ""));
        return env;
    }

    private static String resolveWindowsExecutable(String fileName) {
        Path direct = Path.of(fileName);
        if (direct.isAbsolute() && Files.isRegularFile(direct)) {
            return direct.normalize().toString();
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(";")) {
                if (dir.isBlank()) {
                    continue;
                }
                Path candidate = Path.of(dir.trim(), fileName);
                if (Files.isRegularFile(candidate)) {
                    return candidate.normalize().toString();
                }
            }
        }
        for (String fallback : List.of(
                "C:\\Program Files\\nodejs\\" + fileName,
                "C:\\Program Files (x86)\\nodejs\\" + fileName
        )) {
            if (Files.isRegularFile(Path.of(fallback))) {
                return fallback;
            }
        }
        return null;
    }

    private static String resolveNodeJsDir() {
        String npmCmd = resolveWindowsExecutable("npm.cmd");
        if (npmCmd != null) {
            Path parent = Path.of(npmCmd).getParent();
            if (parent != null) {
                return parent.toString();
            }
        }
        for (String fallback : List.of("C:\\Program Files\\nodejs", "C:\\Program Files (x86)\\nodejs")) {
            if (Files.isDirectory(Path.of(fallback))) {
                return fallback;
            }
        }
        return null;
    }
}
