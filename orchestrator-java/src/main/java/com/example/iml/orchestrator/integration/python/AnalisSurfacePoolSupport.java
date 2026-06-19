package com.example.iml.orchestrator.integration.python;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.clientapi.AnalisSurfaceHttpBinaryRpcSupervisor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Утилиты пула analisSurface: дедупликация клиентов по backend URL. */
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
            String key = supervisor instanceof AnalisSurfaceHttpBinaryRpcSupervisor http
                    ? http.baseUrl()
                    : supervisor.supervisorLabel();
            byKey.putIfAbsent(key, supervisor);
        }
        return List.copyOf(byKey.values());
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
