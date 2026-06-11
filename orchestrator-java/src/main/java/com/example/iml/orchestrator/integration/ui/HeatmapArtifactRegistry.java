package com.example.iml.orchestrator.integration.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.regex.Pattern;

/**
 * Opaque handle → путь к файлу heatmap (.u8) для GET без раскрытия {@code file_path} в WebSocket (Фаза 4b).
 */
public final class HeatmapArtifactRegistry {

    private static final SecureRandom RND = new SecureRandom();
    private static final Pattern TOKEN = Pattern.compile("^[0-9a-f]{32}$");
    private static final int MAX_ARTIFACTS = 256;
    private static final Path ARTIFACT_DIR = Path.of(System.getProperty("java.io.tmpdir"), "iml-ui-artifacts");

    private final ConcurrentHashMap<String, Path> byToken = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> insertionOrder = new ConcurrentLinkedDeque<>();

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
        byte[] raw = new byte[16];
        RND.nextBytes(raw);
        String token = HexFormat.of().formatHex(raw);
        String fileName = heatmapFile.getFileName() == null ? "" : heatmapFile.getFileName().toString();
        String suffix = fileName.toLowerCase().endsWith(".jpg") ? ".jpg" : ".u8";
        Path artifact = ARTIFACT_DIR.resolve(token + suffix);
        try {
            Files.createDirectories(ARTIFACT_DIR);
            Files.copy(heatmapFile, artifact, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            return null;
        }
        byToken.put(token, artifact);
        insertionOrder.addLast(token);
        trimOldArtifacts();
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

    private void trimOldArtifacts() {
        while (byToken.size() > MAX_ARTIFACTS) {
            String oldest = insertionOrder.pollFirst();
            if (oldest == null) {
                return;
            }
            Path removed = byToken.remove(oldest);
            if (removed != null) {
                try {
                    Files.deleteIfExists(removed);
                } catch (IOException ignored) {
                    // Temp cleanup can remove a stale artifact later.
                }
            }
        }
    }
}
