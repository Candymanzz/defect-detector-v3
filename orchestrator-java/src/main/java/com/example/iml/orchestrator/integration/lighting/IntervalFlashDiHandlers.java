package com.example.iml.orchestrator.integration.lighting;

/** DI idle/trigger edge handling for {@link IntervalFlashController}. */
final class IntervalFlashDiHandlers {

    private final IntervalFlashConfig config;
    private final IntervalFlashCycleSupport cycle;
    private final Object stateLock = new Object();

    private boolean idlePortActive;
    private boolean triggerPortActive;
    private boolean idlePortInitialized;
    private boolean triggerPortInitialized;

    IntervalFlashDiHandlers(IntervalFlashConfig config, IntervalFlashCycleSupport cycle) {
        this.config = config;
        this.cycle = cycle;
    }

    void handleIdlePort(boolean active) {
        boolean edge;
        boolean alreadyIdle;
        synchronized (stateLock) {
            if (!idlePortInitialized) {
                idlePortInitialized = true;
                idlePortActive = active;
                alreadyIdle = IntervalFlashController.isIdleLevel(active, config.idleEdge());
                edge = false;
            } else {
                alreadyIdle = false;
                edge = IntervalFlashController.isEdge(idlePortActive, active, config.idleEdge());
                idlePortActive = active;
            }
        }
        if (edge || alreadyIdle) {
            // Не отменяем pending Off: иначе при быстром DI2↓ Off после DI3 не срабатывает,
            // банк остаётся On на весь следующий цикл → «через цикл очень ярко».
            cycle.requestIdleOn("холостой DI" + config.idlePort() + " "
                    + (alreadyIdle ? "level" : config.idleEdge().name().toLowerCase()));
        }
    }

    void handleTriggerPort(boolean active) {
        boolean edge;
        synchronized (stateLock) {
            if (!triggerPortInitialized) {
                triggerPortInitialized = true;
                triggerPortActive = active;
                // Начальный уровень DI3 без фронта — не трогаем (избегаем ложного Off при старте).
                return;
            }
            edge = IntervalFlashController.isEdge(triggerPortActive, active, config.triggerEdge());
            triggerPortActive = active;
        }
        if (edge) {
            // Съёмка на DI3: On; Off по первому кадру или timeout off_delay_ms.
            cycle.cancelPendingOff();
            cycle.cancelPendingOn();
            cycle.beginCaptureCycle();
            cycle.scheduleOn("DI" + config.triggerPort() + " " + config.triggerEdge().name().toLowerCase());
            cycle.scheduleOff();
        }
    }
}
