package com.example.iml.orchestrator.integration.trigger;

import java.util.function.Consumer;

/**
 * Направление хода с UI. На DI3 съёмка только если DI2 совпал с выбором
 * (например «Обратный ход» → снимаем при ходе назад, игнорируем DI3 при ходе вперёд).
 */
public final class ManualLineDirectionService {

    public enum Direction {
        FORWARD,
        REVERSE
    }

    /** По умолчанию обратный: DI3 на ходе вперёд не снимаем, на ходе назад — снимаем. */
    private volatile Direction direction = Direction.REVERSE;
    private volatile Consumer<String> onChanged = ignored -> { };

    public Direction direction() {
        return direction;
    }

    public boolean isForward() {
        return direction == Direction.FORWARD;
    }

    public String wireValue() {
        return direction == Direction.FORWARD ? "forward" : "reverse";
    }

    public void setOnChanged(Consumer<String> onChanged) {
        this.onChanged = onChanged == null ? ignored -> { } : onChanged;
    }

    public void setDirection(Direction direction) {
        this.direction = direction == null ? Direction.REVERSE : direction;
        notifyChanged();
    }

    public void setFromWireValue(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("direction required (forward|reverse)");
        }
        String normalized = raw.trim().toLowerCase();
        if ("forward".equals(normalized) || "1".equals(normalized) || "true".equals(normalized)) {
            direction = Direction.FORWARD;
            notifyChanged();
            return;
        }
        if ("reverse".equals(normalized) || "0".equals(normalized) || "false".equals(normalized)) {
            direction = Direction.REVERSE;
            notifyChanged();
            return;
        }
        throw new IllegalArgumentException("invalid direction: " + raw);
    }

    private void notifyChanged() {
        try {
            onChanged.accept(wireValue());
        } catch (RuntimeException ignored) {
            // слушатель не должен ломать HTTP PUT
        }
    }
}
