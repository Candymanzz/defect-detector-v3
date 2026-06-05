import type { ButtonHTMLAttributes, ReactNode } from "react";
import "./Button.css";

type ButtonVariant = "primary" | "secondary" | "ghost";

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  children: ReactNode;
  fullWidth?: boolean;
  variant?: ButtonVariant;
};

export function Button({ children, className = "", fullWidth = false, variant = "secondary", ...props }: ButtonProps) {
  const buttonClassName = [
    "ui-button",
    `ui-button--${variant}`,
    fullWidth ? "ui-button--full" : "",
    className,
  ]
    .filter(Boolean)
    .join(" ");

  return (
    <button
      className={buttonClassName}
      {...props}
    >
      {children}
    </button>
  );
}
