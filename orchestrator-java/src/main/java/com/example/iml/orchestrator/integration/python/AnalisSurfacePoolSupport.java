package com.example.iml.orchestrator.integration.python;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.clientapi.AnalisSurfaceHttpBinaryRpcSupervisor;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Утилиты пула analisSurface: дедупликация клиентов по backend URL и разделение фаз по чётности порта. */
public final class AnalisSurfacePoolSupport {

    private AnalisSurfacePoolSupport() {
    }

    /**
     * Один представитель на каждый uvicorn (для sync эталона/ROI — без дубля на тот же порт).
     */
    public static List<BinaryRpcSupervisor> uniqueServerClients(List<? extends BinaryRpcSupervisor> pool) {
        if (pool == null || pool.isEmpty()) {
            return List.of();
        }
        Map<String, BinaryRpcSupervisor> byKey = new LinkedHashMap<>();
        for (BinaryRpcSupervisor supervisor : pool) {
            if (supervisor == null) {
                continue;
            }
            byKey.putIfAbsent(serverKey(supervisor), supervisor);
        }
        return List.copyOf(byKey.values());
    }

    /**
     * Фаза 1 ({@code phaseId} 0) живёт только на нечётных портах, фаза 2 ({@code phaseId} 1) — только на чётных.
     * Один процесс на порт. Если в пуле один сервер, вернуть его: делить нечего.
     * Если серверов несколько, но нужной чётности нет — пустой список, чужую фазу не трогаем.
     */
    public static List<BinaryRpcSupervisor> uniqueServerClientsForPhase(
            List<? extends BinaryRpcSupervisor> pool,
            int phaseId
    ) {
        List<BinaryRpcSupervisor> unique = uniqueServerClients(pool);
        List<BinaryRpcSupervisor> matched = new ArrayList<>();
        for (BinaryRpcSupervisor supervisor : unique) {
            if (servesPhase(supervisor, phaseId)) {
                matched.add(supervisor);
            }
        }
        if (!matched.isEmpty()) {
            return List.copyOf(matched);
        }
        return unique.size() <= 1 ? unique : List.of();
    }

    /**
     * Все HTTP-клиенты фазы (несколько клиентов на один порт остаются — round-robin инспекции).
     * Правило чётности то же, что у {@link #uniqueServerClientsForPhase}.
     */
    public static List<BinaryRpcSupervisor> clientsForPhase(
            List<? extends BinaryRpcSupervisor> pool,
            int phaseId
    ) {
        if (pool == null || pool.isEmpty()) {
            return List.of();
        }
        List<BinaryRpcSupervisor> matched = new ArrayList<>();
        List<BinaryRpcSupervisor> present = new ArrayList<>();
        for (BinaryRpcSupervisor supervisor : pool) {
            if (supervisor == null) {
                continue;
            }
            present.add(supervisor);
            if (servesPhase(supervisor, phaseId)) {
                matched.add(supervisor);
            }
        }
        if (!matched.isEmpty()) {
            return List.copyOf(matched);
        }
        long distinct = present.stream().map(AnalisSurfacePoolSupport::serverKey).distinct().count();
        return distinct <= 1 ? List.copyOf(present) : List.of();
    }

    /**
     * Нечётный порт обслуживает чётный {@code phaseId} (0, 2, …), чётный порт — нечётный (1, 3, …).
     */
    public static boolean servesPhase(BinaryRpcSupervisor supervisor, int phaseId) {
        int port = portOf(supervisor);
        if (port <= 0 || phaseId < 0) {
            return false;
        }
        boolean evenPort = port % 2 == 0;
        boolean phaseWantsEvenPort = phaseId % 2 == 1;
        return evenPort == phaseWantsEvenPort;
    }

    static int portOf(BinaryRpcSupervisor supervisor) {
        if (!(supervisor instanceof AnalisSurfaceHttpBinaryRpcSupervisor http)) {
            return -1;
        }
        String baseUrl = http.baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return -1;
        }
        try {
            return URI.create(baseUrl.trim()).getPort();
        } catch (IllegalArgumentException ignored) {
            return -1;
        }
    }

    private static String serverKey(BinaryRpcSupervisor supervisor) {
        return supervisor instanceof AnalisSurfaceHttpBinaryRpcSupervisor http
                ? http.baseUrl()
                : supervisor.supervisorLabel();
    }

    /**
     * Распределение {@code clientCount} HTTP-клиентов по {@code serverBaseUrls} round-robin.
     */
    public static List<String> clientBaseUrls(List<String> serverBaseUrls, int clientCount) {
        if (serverBaseUrls == null || serverBaseUrls.isEmpty()) {
            return List.of();
        }
        int clients = Math.max(1, clientCount);
        List<String> out = new ArrayList<>(clients);
        for (int i = 0; i < clients; i++) {
            out.add(serverBaseUrls.get(i % serverBaseUrls.size()));
        }
        return List.copyOf(out);
    }
}
