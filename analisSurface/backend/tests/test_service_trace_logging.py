from __future__ import annotations

import json

from fastapi.testclient import TestClient

from app import file_logging
from app.main import app


def test_service_trace_records_http_exchange_in_shared_session(tmp_path, monkeypatch) -> None:
    session = tmp_path / "2026-10-12_10-30-00"
    monkeypatch.setenv("IML_TRACE_SESSION_DIR", str(session))
    monkeypatch.setenv("ANALIS_SURFACE_FILE_LOGGING", "false")
    monkeypatch.setattr(file_logging, "_INITIALIZED", False)
    monkeypatch.setattr(file_logging, "_SESSION_DIR", None)

    assert file_logging.file_logging_enabled()
    with TestClient(app) as client:
        response = client.get("/health", headers={"authorization": "secret"})
    assert response.status_code == 200

    worker_dir = file_logging.get_session_log_dir()
    assert worker_dir is not None
    assert worker_dir.parent == session / "analisSurface"
    rows = [json.loads(line) for line in (worker_dir / "http.jsonl").read_text(encoding="utf-8").splitlines()]
    assert len(rows) == 2
    request, reply = rows
    assert request["event"] == "request"
    assert request["path"] == "/health"
    assert request["headers"]["authorization"] == "[omitted]"
    assert reply["event"] == "response"
    assert reply["request_id"] == request["request_id"]
    assert reply["status"] == 200
    assert reply["duration_ms"] >= 0
    assert "detector_id" in reply["body"]


def test_service_trace_records_exception_and_omits_large_body(tmp_path, monkeypatch) -> None:
    session = tmp_path / "run"
    monkeypatch.setenv("IML_TRACE_SESSION_DIR", str(session))
    monkeypatch.setattr(file_logging, "_INITIALIZED", False)
    monkeypatch.setattr(file_logging, "_SESSION_DIR", None)

    file_logging.log_http_request("POST", "/inspect", {"content-type": "application/json"},
                                  json.dumps({"image": "a" * 9000}).encode(), "test-1")
    file_logging.log_http_exception("POST", "/inspect", 12.5, ValueError("bad frame"), "test-1")

    worker_dir = file_logging.get_session_log_dir()
    assert worker_dir is not None
    rows = [json.loads(line) for line in (worker_dir / "http.jsonl").read_text(encoding="utf-8").splitlines()]
    assert rows[0]["request_id"] == rows[1]["request_id"] == "test-1"
    assert "[large value omitted" in rows[0]["body"]
    assert rows[1]["event"] == "exception"
    assert rows[1]["duration_ms"] == 12.5
