import os
from concurrent.futures import ThreadPoolExecutor

from app.services.inspection_service import InspectionService


def _inspect_worker_count() -> int:
    raw = os.environ.get("ANALIS_INSPECT_WORKERS", "10").strip()
    try:
        return max(1, min(32, int(raw)))
    except ValueError:
        return 10


inspection_service = InspectionService()
inspect_executor = ThreadPoolExecutor(max_workers=_inspect_worker_count(), thread_name_prefix="inspect")
