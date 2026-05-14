package com.example.iml.orchestrator.integration.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Opaque handle → путь к файлу heatmap (.u8) для GET без раскрытия {@code file_path} в WebSocket (Фаза 4b).
 */
public final class HeatmapArtifactRegistry {

    private static final SecureRandom RND = new SecureRandom();
    private static final Pattern TOKEN = Pattern.compile("^[0-9a-f]{32}$");

    private final ConcurrentHashMap<String, Path> byToken = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, String> latestTokenByCamera = new ConcurrentHashMap<>();

    /**
     * Регистрирует файл для камеры; предыдущий токен этой камеры инвалидируется.
     *
     * @return hex-токен (32 символа) или {@code null}, если файла нет
     */
    public String register(int cameraId, Path heatmapFile) {
        Objects.requireNonNull(heatmapFile, "heatmapFile");
        if (!Files.isRegularFile(heatmapFile)) {
            return null;
        }
        Path abs = heatmapFile.toAbsolutePath().normalize();
        byte[] raw = new byte[16];
        RND.nextBytes(raw);
        String token = HexFormat.of().formatHex(raw);
        String prev = latestTokenByCamera.put(cameraId, token);
        if (prev != null) {
            byToken.remove(prev);
        }
        byToken.put(token, abs);
        return token;
    }

    public Path resolve(String token) {
        if (token == null) {
            return null;
        }
        String t = token.trim();
        if (t.isEmpty() || !TOKEN.matcher(t).matches()) {
            return null;
        }
        Path p = byToken.get(t);
        return p != null && Files.isRegularFile(p) ? p : null;
    }
}
