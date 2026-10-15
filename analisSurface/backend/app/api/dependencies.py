import os

import cv2

from app.services.inspection_service import InspectionService
from app.services.inspection_workload import BackgroundExecutor, InspectionExecutor


# Parallelism is provided by the inspection process pool. Letting every OpenCV
# call create its own worker team oversubscribes the CPU when 10 cameras arrive
# together and produces large tail-latency spikes.
cv2.setNumThreads(1)
cv2.ocl.setUseOpenCL(False)


def _inspect_worker_count() -> int:
    raw = os.environ.get("ANALIS_INSPECT_WORKERS", "10").strip()
    try:
        return max(1, min(32, int(raw)))
    except ValueError:
        return 10


inspection_service = InspectionService(
    session_wipe=False,
    learned_normals_session_wipe=False,
)
inspect_executor = InspectionExecutor(inspection_service._workload,
                                     max_workers=_inspect_worker_count(), thread_name_prefix="inspect")
heatmap_executor = BackgroundExecutor(inspection_service._workload)
