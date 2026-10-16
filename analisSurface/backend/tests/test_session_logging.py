"""Run with unittest; does not require detector/ML dependencies."""
import asyncio
import os
from pathlib import Path
import queue
import tempfile
import unittest
from unittest.mock import patch

from app import file_logging as logs
from app.session_http_logging import SessionHttpLogging


class SessionLoggingTest(unittest.TestCase):
    def test_streaming_binary_is_forwarded_without_buffering(self):
        body = b"image-data" * 10000
        messages = []
        captured = []

        async def application(scope, receive, send):
            await send({"type": "http.response.start", "status": 200, "headers": []})
            await send({"type": "http.response.body", "body": body, "more_body": True})
            await send({"type": "http.response.body", "body": b"", "more_body": False})

        async def receive():
            raise AssertionError("Logging must never read an image upload")

        async def send(message):
            messages.append(message)

        with patch("app.session_http_logging.file_logging_enabled", return_value=True), \
                patch("app.session_http_logging.log_http_request") as request, \
                patch("app.session_http_logging.log_http_response", side_effect=lambda *args: captured.append(args)):
            asyncio.run(SessionHttpLogging(application)(
                {"type": "http", "method": "POST", "path": "/inspect", "headers": []}, receive, send))
        self.assertIs(messages[1]["body"], body)
        self.assertEqual(captured[0][-1], b"")
        self.assertEqual(captured[0][-2]["logged-response-bytes"], str(len(body)))
        self.assertGreaterEqual(captured[0][3], 0)
        self.assertEqual(request.call_args.args[-1], b"")
        self.assertEqual(logs._REQUEST_ID.get(), "")

    def test_full_queue_drops_instead_of_blocking(self):
        bounded = queue.Queue(maxsize=1)
        bounded.put(object())
        with patch.object(logs, "_enabled", return_value=True), \
                patch.object(logs, "_ensure_initialized"), \
                patch.object(logs, "_SESSION_DIR", Path("unused")), \
                patch.object(logs, "_LOG_QUEUE", bounded), patch.object(logs, "_DROPPED", 0):
            logs.log_error("test", "full queue")
            self.assertEqual(logs._DROPPED, 1)

    def test_shared_directory_and_timestamped_records(self):
        # Other API tests may have initialized this process-wide singleton.
        logs._shutdown()
        logs._INITIALIZED = False
        logs._SESSION_DIR = None
        with tempfile.TemporaryDirectory() as directory, \
                patch.dict(os.environ, {"IML_LOG_SESSION_DIR": directory}), \
                patch.object(logs, "_enabled", return_value=True):
            logs.init_file_logging()
            self.assertEqual(logs.get_session_log_dir(), Path(directory))
            logs.log_analysis_stage("decision", "accepted", extra={"image": b"secret pixels"})
            logs._LOG_QUEUE.join()
            logs._shutdown()
            text = next(Path(directory).glob("python-analysis-*.log")).read_text(encoding="utf-8")
            self.assertRegex(text, r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}")
            self.assertIn("stage=decision", text)
            self.assertNotIn("secret pixels", text)
            logs._INITIALIZED = False
            logs._SESSION_DIR = None


if __name__ == "__main__":
    unittest.main()
