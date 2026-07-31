package com.example.iml.orchestrator.integration.capture;

import java.util.Set;
import java.util.regex.Pattern;

/** SHM filename classification helpers for {@link ImlShmJanitor}. */
final class ImlShmNames {

    static final Pattern STABLE_FILE = Pattern.compile(
            "^iml_cam_\\d+_frame$"
                    + "|^iml_ref_cam\\d+$"
                    + "|^iml_pos_cam_\\d+$"
                    + "|^iml_ds_[a-z_]+_cam\\d+$"
                    + "|^iml_py_ds_(cur|ref)_cam\\d+$"
                    + "|^iml_ui_(inspect|heatmap)_cam_\\d+$"
    );

    private static final Pattern LINE_PIN_FILE = Pattern.compile("^iml_line_pin_cam\\d+_f\\d+$");

    private ImlShmNames() {
    }

    static boolean isEphemeralLinePin(String shmNameOrBase) {
        String base = shmBaseName(shmNameOrBase);
        return base != null && LINE_PIN_FILE.matcher(base).matches();
    }

    static boolean isDedicatedOrchestratorBuffer(String baseName) {
        if (baseName == null || baseName.isBlank()) {
            return false;
        }
        return baseName.startsWith("iml_ds_")
                || baseName.startsWith("iml_py_ds_")
                || baseName.startsWith("iml_pos_")
                || baseName.startsWith("iml_ui_");
    }

    static boolean isOrchestratorOwnedBuffer(String name) {
        return name.startsWith("iml_ds_")
                || name.startsWith("iml_py_ds_")
                || name.startsWith("iml_pos_")
                || name.startsWith("iml_ui_");
    }

    static void addShmCandidate(Set<String> out, Object raw) {
        String base = shmBaseName(raw == null ? null : String.valueOf(raw));
        if (base != null) {
            out.add(base);
        }
    }

    static String shmBaseName(String shmName) {
        if (shmName == null || shmName.isBlank() || "null".equals(shmName)) {
            return null;
        }
        String name = shmName.trim();
        if (name.startsWith("/")) {
            name = name.substring(1);
        }
        name = name.replace('/', '_');
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < name.length()) {
            name = name.substring(slash + 1);
        }
        if (name.endsWith(".bin")) {
            name = name.substring(0, name.length() - 4);
        }
        return name.isBlank() ? null : name;
    }
}
