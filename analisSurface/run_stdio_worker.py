"""
Запуск stdio-детектора из корня репозитория (cwd = project root).
Оркестратор стартует процесс с directory=projectRoot, поэтому PYTHONPATH здесь задаётся вручную.
"""
from __future__ import annotations

import sys
from pathlib import Path

_BACKEND = Path(__file__).resolve().parent / "backend"
if str(_BACKEND) not in sys.path:
    sys.path.insert(0, str(_BACKEND))

from app.orchestrator_stdio_runner import main  # noqa: E402

if __name__ == "__main__":
    raise SystemExit(main())
