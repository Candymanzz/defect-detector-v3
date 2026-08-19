import json
import logging
import logging.config
import os
from typing import Optional


_CONFIGURED = False


def configure_logging() -> None:
    global _CONFIGURED
    if _CONFIGURED:
        return

    level = os.environ.get("ANALIS_LOG_LEVEL", "INFO").strip().upper() or "INFO"
    logging.config.dictConfig(
        {
            "version": 1,
            "disable_existing_loggers": False,
            "formatters": {
                "standard": {
                    "format": (
                        "%(asctime)s.%(msecs)03d %(levelname)s "
                        "[%(name)s] %(filename)s:%(lineno)d %(message)s"
                    ),
                    "datefmt": "%Y-%m-%d %H:%M:%S",
                }
            },
            "handlers": {
                "default": {
                    "class": "logging.StreamHandler",
                    "formatter": "standard",
                }
            },
            "root": {
                "level": level,
                "handlers": ["default"],
            },
            "loggers": {
                "uvicorn": {"level": level, "handlers": ["default"], "propagate": False},
                "uvicorn.error": {"level": level, "handlers": ["default"], "propagate": False},
                # Access log дублирует наш middleware-лог, поэтому оставляем только warning+.
                "uvicorn.access": {"level": "WARNING", "handlers": ["default"], "propagate": False},
            },
        }
    )
    _CONFIGURED = True


def should_capture_request_body(content_type: str, content_length: int, max_bytes: int) -> bool:
    if content_length <= 0 or content_length > max_bytes:
        return False
    lowered = (content_type or "").lower()
    return (
        lowered.startswith("application/json")
        or lowered.startswith("text/")
        or lowered.startswith("application/x-www-form-urlencoded")
    )


def format_body_preview(raw_body: bytes, *, max_chars: int = 1500) -> Optional[str]:
    if not raw_body:
        return None
    try:
        text = raw_body.decode("utf-8", errors="replace").strip()
    except Exception:
        return f"<{len(raw_body)} bytes binary>"
    if not text:
        return None
    try:
        parsed = json.loads(text)
        text = json.dumps(parsed, ensure_ascii=False, separators=(",", ":"))
    except Exception:
        pass
    if len(text) > max_chars:
        text = text[:max_chars] + "...<truncated>"
    return text
