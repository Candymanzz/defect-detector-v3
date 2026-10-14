"""Metadata-only HTTP diagnostics: no body buffering, including image uploads."""
import time
import uuid
import re

from app.file_logging import (
    _REQUEST_ID, _should_skip_http, file_logging_enabled,
    log_error, log_http_request, log_http_response,
)


class SessionHttpLogging:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or not file_logging_enabled() or _should_skip_http(scope["path"]):
            return await self.app(scope, receive, send)
        started = time.perf_counter()
        headers = {k.decode("latin1"): v.decode("latin1") for k, v in scope.get("headers", [])}
        incoming_id = headers.get("x-request-id", "")
        request_id = incoming_id if re.fullmatch(r"[a-zA-Z0-9-]{1,80}", incoming_id) else uuid.uuid4().hex
        token = _REQUEST_ID.set(request_id)
        status = 500
        response_headers = {}
        size = 0
        log_http_request(scope["method"], scope["path"], headers, b"")

        async def observed_send(message):
            nonlocal status, response_headers, size
            if message["type"] == "http.response.start":
                status = message["status"]
                response_headers = {k.decode("latin1"): v.decode("latin1") for k, v in message.get("headers", [])}
            elif message["type"] == "http.response.body":
                size += len(message.get("body", b""))
            await send(message)

        try:
            await self.app(scope, receive, observed_send)
        except Exception as exc:
            log_error("http_exception", str(exc))
            raise
        finally:
            response_headers["logged-response-bytes"] = str(size)
            log_http_response(scope["method"], scope["path"], status,
                              (time.perf_counter() - started) * 1000,
                              response_headers, b"")
            _REQUEST_ID.reset(token)
