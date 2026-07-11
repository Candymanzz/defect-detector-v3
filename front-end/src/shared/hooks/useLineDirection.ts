import { useEffect, useState } from "react";
import { orchestratorApi } from "../../shared/api";
import type { LineDirection } from "../../shared/api";

export function useLineDirection(pollMs = 2000) {
  const [direction, setDirection] = useState<LineDirection>("forward");

  useEffect(() => {
    let active = true;

    const load = () => {
      orchestratorApi
        .getLineDirection()
        .then((response) => {
          if (active) {
            setDirection(response.direction);
          }
        })
        .catch(() => {
          if (active) {
            setDirection("forward");
          }
        });
    };

    load();
    const timerId = window.setInterval(load, pollMs);
    return () => {
      active = false;
      window.clearInterval(timerId);
    };
  }, [pollMs]);

  return direction;
}

export function lineDirectionLabel(direction: LineDirection) {
  return direction === "reverse" ? "Обратный ход" : "Прямой ход";
}
