package com.example.iml.orchestrator.integration.clientapi;

/**
 * Отложенная привязка {@link UiTestAnalyzeService} (mount раньше pools/pipeline).
 */
public final class UiTestAnalyzeServiceHolder {

    private volatile UiTestAnalyzeService service;

    public UiTestAnalyzeService get() {
        return service;
    }

    public void set(UiTestAnalyzeService service) {
        this.service = service;
    }
}
