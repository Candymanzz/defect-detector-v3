package com.example.iml.orchestrator.integration.trigger;

/**
 * Направление хода с UI (отображение). Съёмка по DI3 зависит от физического DI2=1,
 * а не от этого выбора.
 */
public final class ManualLineDirectionService {

    public enum Direction {
        FORWARD,
        REVERSE
    }

    /** По умолчанию обратный: DI3 на ходе вперёд не снимаем, на ходе назад — снимаем. */
    private volatile Direction direction = Direction.REVERSE;

    public Direction direction() {
        return direction;
    }

    public boolean isForward() {
        return direction == Direction.FORWARD;
    }

    public String wireValue() {
        return direction == Direction.FORWARD ? "forward" : "reverse";
    }

    public void setDirection(Direction direction) {
        this.direction = direction == null ? Direction.REVERSE : direction;
    }

    public void setFromWireValue(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("direction required (forward|reverse)");
        }
        String normalized = raw.trim().toLowerCase();
        if ("forward".equals(normalized) || "1".equals(normalized) || "true".equals(normalized)) {
            direction = Direction.FORWARD;
            return;
        }
        if ("reverse".equals(normalized) || "0".equals(normalized) || "false".equals(normalized)) {
            direction = Direction.REVERSE;
            return;
        }
        throw new IllegalArgumentException("invalid direction: " + raw);
    }
}
