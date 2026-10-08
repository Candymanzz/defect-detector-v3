package com.example.iml.orchestrator.integration.pipeline.bucket;

import java.util.Locale;

/**
 * Как агрегатор отдаёт готовые вёдра (изделия) на ПЛК/UI. Задаётся в {@code inspection_bucket.fanout_mode}.
 * См. DECISIONS.md, решение D-001.
 */
public enum BucketFanOutMode {

    /**
     * Станок на 2 изделия (одна фаза): вердикты всех вёдер одного triggerSequence уходят пакетом
     * (reject_line_1 и reject_line_2 синхронно). Нет второго ведра за таймаут — синтетический брак линии.
     * По умолчанию: ранний брак ручки включён, capture-only ведро уходит как «годно».
     */
    SEQUENCE_BARRIER(true, false),

    /**
     * Станок на 4 изделия (две фазы): каждое ведро публикуется сразу и независимо от соседей и фаз,
     * синтетических вердиктов за отсутствующие группы нет.
     * По умолчанию: ранний брак ручки выключен, capture-only ведро не публикуется.
     */
    IMMEDIATE(false, true);

    private final boolean defaultEarlyReject;
    private final boolean defaultSuppressCaptureOnly;

    BucketFanOutMode(boolean defaultEarlyReject, boolean defaultSuppressCaptureOnly) {
        this.defaultEarlyReject = defaultEarlyReject;
        this.defaultSuppressCaptureOnly = defaultSuppressCaptureOnly;
    }

    /** Значение {@code early_reject}, если оно не задано в конфиге. */
    public boolean defaultEarlyReject() {
        return defaultEarlyReject;
    }

    /** Значение {@code suppress_capture_only}, если оно не задано в конфиге. */
    public boolean defaultSuppressCaptureOnly() {
        return defaultSuppressCaptureOnly;
    }

    public static BucketFanOutMode parse(Object raw, BucketFanOutMode fallback) {
        if (raw == null) {
            return fallback;
        }
        String text = String.valueOf(raw).trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (text) {
            case "sequence_barrier", "barrier", "sync" -> SEQUENCE_BARRIER;
            case "immediate", "independent" -> IMMEDIATE;
            default -> throw new IllegalArgumentException(
                    "inspection_bucket.fanout_mode: unknown value '" + raw + "' (sequence_barrier | immediate)"
            );
        };
    }
}
