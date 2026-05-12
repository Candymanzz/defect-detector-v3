import argparse
import os
import uuid

import uvicorn


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run one defect detector REST worker.")
    parser.add_argument("--app-id", "--detector-id", dest="app_id", default=None)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--reload", action="store_true")
    return parser


def main() -> int:
    args = _build_parser().parse_args()
    app_id = (
        args.app_id
        or os.environ.get("PYTHON_APP_ID")
        or os.environ.get("DETECTOR_ID")
        or f"python-detector-{uuid.uuid4().hex[:12]}"
    )
    os.environ["PYTHON_APP_ID"] = app_id
    os.environ["DETECTOR_ID"] = app_id

    uvicorn.run(
        "app.main:app",
        host=args.host,
        port=args.port,
        reload=args.reload,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
