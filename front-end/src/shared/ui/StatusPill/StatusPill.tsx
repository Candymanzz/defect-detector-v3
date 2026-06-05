import type { ReactNode } from "react";
import "./StatusPill.css";

type StatusPillState = "danger" | "success" | "warning" | "neutral";

type StatusPillProps = {
  children: ReactNode;
  state?: StatusPillState;
};

export function StatusPill({ children, state = "neutral" }: StatusPillProps) {
  return <span className={`status-pill status-pill--${state}`}>{children}</span>;
}
