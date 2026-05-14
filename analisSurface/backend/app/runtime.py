import os
import uuid


def _initial_application_id() -> str:
    for env_name in ("PYTHON_APP_ID", "DETECTOR_ID"):
        value = os.environ.get(env_name)
        if value:
            return value.strip()
    return f"python-detector-{uuid.uuid4().hex[:12]}"


_APPLICATION_ID = _initial_application_id()


def get_application_id() -> str:
    return _APPLICATION_ID


def set_application_id(application_id: str) -> str:
    normalized = application_id.strip()
    if not normalized:
        raise ValueError("application id must not be empty")

    global _APPLICATION_ID
    _APPLICATION_ID = normalized
    os.environ["PYTHON_APP_ID"] = normalized
    os.environ["DETECTOR_ID"] = normalized
    return _APPLICATION_ID
