package com.example.iml.orchestrator.integration.clientws.util;

/**
 * Вспомогательные строковые операции WebSocket-протокола.
 */
public final class WsTextUtil {

    private WsTextUtil() {
    }

    public static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
