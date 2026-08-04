package com.example.iml.orchestrator.integration.clientws.session;

/**
 * Состояние сессии клиента.
 * <ul>
 *   <li>{@link #NO_REFERENCE} — эталон не задан</li>
 *   <li>{@link #READY} / {@link #OPERATIONAL} — эталон есть, инспекция может идти</li>
 *   <li>{@link #INSPECTION_STOPPED} — эталон/ROI/настройки сохранены, циклы инспекции на всех камерах остановлены</li>
 * </ul>
 */
public enum ClientWsSessionState {
    NO_REFERENCE,
    READY,
    OPERATIONAL,
    /** Полная пауза инспекции без сброса эталона. */
    INSPECTION_STOPPED
}
