import argparse
import json
from pathlib import Path
import signal
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import rt_diagnostics as rt
import stack_processes as stack
import stop_stack

ROOT = str(Path("/project").resolve())


def process(pid, parent, args, cwd=ROOT, state="S"):
    return {"pid": pid, "ppid": parent, "args": args, "cwd": cwd,
            "state": state, "starttime": pid * 100, "name": Path(args[0]).name}


class ProcessTests(unittest.TestCase):
    def setUp(self):
        self.kill_signal = patch.object(signal, "SIGKILL", 9, create=True)
        self.kill_signal.start()
        self.addCleanup(self.kill_signal.stop)
        for name, value in (("SIGSTOP", 19), ("SIGCONT", 18)):
            patcher = patch.object(signal, name, value, create=True)
            patcher.start()
            self.addCleanup(patcher.stop)

    def test_ownership_descendants_and_caller_exclusion(self):
        entries = {
            10: process(10, 1, ["java", "-jar", "orchestrator-0.1.0-SNAPSHOT.jar"]),
            11: process(11, 10, ["sleep", "30"], "/tmp"),
            12: process(12, 1, ["python3", "-m", "uvicorn", "app.main:app"], ROOT + "/analisSurface/backend"),
            13: process(13, 1, ["java", "-jar", "orchestrator-0.1.0-SNAPSHOT.jar"], "/another-project"),
            14: process(14, 1, ["java", "-jar", "unrelated.jar"]),
            15: process(15, 1, ["bash", "/project/run-supervised.sh"]),
            16: process(16, 15, ["python3", "/project/deploy/linux/stop_stack.py"]),
        }
        excluded = stack.ancestors(entries, 16)
        self.assertEqual(set(stack.select_services(entries, "/project", excluded)), {10, 11, 12})

    def test_supervisor_stops_before_children_and_force_stop_is_available(self):
        supervisor = process(10, 1, ["java", "StackSupervisorMain"])
        worker = process(11, 10, ["camera_worker"])
        events = []
        with patch.object(stop_stack, "processes", side_effect=[{10: supervisor, 11: worker}, {}, {}, {}]), \
                patch.object(stop_stack, "stop_systemd", side_effect=lambda *_: events.append("systemd")), \
                patch.object(stop_stack, "send", side_effect=lambda entry, sig: events.append((entry["pid"], sig))), \
                patch.object(stop_stack, "wait_for"):
            stop_stack.stop(Path("/project"), 1)
        self.assertEqual(events[0], "systemd")
        self.assertLess(events.index((10, signal.SIGTERM)), events.index((11, signal.SIGTERM)))
        self.assertIn((11, signal.SIGKILL), events)

    def test_cannot_stop_system_service_cancels_before_signaling_processes(self):
        with patch.object(stop_stack, "processes", return_value={}), \
                patch.object(stop_stack, "stop_systemd", side_effect=RuntimeError("no permission")), \
                patch.object(stop_stack, "send") as send:
            with self.assertRaises(RuntimeError):
                stop_stack.stop(Path("/project"), 1)
            send.assert_not_called()

    def test_pid_reuse_is_not_signaled(self):
        entry = {"pid": 42, "starttime": 100}
        with patch.object(stop_stack.Path, "read_text", return_value="ignored"), \
                patch.object(stop_stack, "stat_fields", return_value={"starttime": 101, "state": "S"}), \
                patch.object(stop_stack.os, "kill") as kill:
            stop_stack.send(entry, signal.SIGTERM)
            kill.assert_not_called()

    def test_kiosk_loop_is_paused_and_resumed_with_pid_identity_check(self):
        controller = process(20, 1, ["sh", "/usr/local/bin/defect-detector-kiosk"], "/opt/defect-detector-ui")
        with tempfile.TemporaryDirectory() as directory, patch.object(stop_stack, "send") as send:
            root = Path(directory)
            stop_stack.pause_kiosk({20: controller}, root)
            send.assert_called_with(controller, signal.SIGSTOP)
            self.assertTrue((root / ".kiosk-paused.json").exists())
            with patch.object(stop_stack, "processes", return_value={20: controller}):
                stop_stack.resume_kiosk(root)
            send.assert_called_with(controller, signal.SIGCONT)
            self.assertFalse((root / ".kiosk-paused.json").exists())

    def test_installed_frontend_is_selected_but_other_electron_apps_are_not(self):
        installed = process(20, 1, ["electron", "."], "/opt/defect-detector-ui")
        unrelated = process(21, 1, ["electron", "."], "/opt/another-application")
        self.assertTrue(stack.is_service(installed, "/project"))
        self.assertFalse(stack.is_service(unrelated, "/project"))

    def test_backend_start_preserves_kiosk_and_its_children(self):
        entries = {
            10: process(10, 1, ["java", "StackSupervisorMain"]),
            11: process(11, 10, ["camera_worker"]),
            20: process(20, 1, ["sh", "/usr/local/bin/defect-detector-kiosk"], "/opt/defect-detector-ui"),
            21: process(21, 20, ["electron", "."], "/opt/defect-detector-ui"),
            22: process(22, 21, ["renderer"], "/tmp"),
        }
        self.assertEqual(set(stack.select_services(entries, "/project", {1}, include_kiosk=False)), {10, 11})

    def test_skip_kiosk_never_pauses_the_other_users_session(self):
        with patch.object(stop_stack, "processes", return_value={}), \
                patch.object(stop_stack, "stop_systemd", return_value=[]), \
                patch.object(stop_stack, "pause_kiosk") as pause:
            stop_stack.stop(Path("/project"), 1, skip_kiosk=True)
            pause.assert_not_called()


class SystemdStopTests(unittest.TestCase):
    def test_stop_job_is_queued_without_waiting_for_service_exit(self):
        calls = []

        def run(command, **kwargs):
            calls.append(command)
            if "--user" in command:
                return subprocess.CompletedProcess(command, 1, "", "no user bus")
            if "show" in command:
                return subprocess.CompletedProcess(command, 0,
                    f"ActiveState=deactivating\nWorkingDirectory={ROOT}\nExecStart=\n", "")
            return subprocess.CompletedProcess(command, 0, "", "")

        with patch.object(stop_stack.shutil, "which", return_value="/usr/bin/tool"), \
                patch.object(stop_stack.Path, "is_dir", return_value=True), \
                patch.object(stop_stack.Path, "read_text", return_value="0::/user.slice/session.scope"), \
                patch.object(stop_stack.os, "geteuid", return_value=1000, create=True), \
                patch.object(stop_stack.subprocess, "run", side_effect=run):
            units = stop_stack.stop_systemd(Path(ROOT))
        self.assertEqual(len(units), 1)
        self.assertIn(["sudo", "-n", "systemctl", "--no-block", "stop", "defect-detector.service"], calls)

    def test_stuck_unit_is_force_stopped_and_then_verified(self):
        with patch.object(stop_stack, "unit_status", side_effect=[
                (False, {"ActiveState": "deactivating"}), (True, {"ActiveState": "inactive"})]), \
                patch.object(stop_stack.time, "monotonic", side_effect=[0, 100, 100]), \
                patch.object(stop_stack.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, "", "")) as run:
            stop_stack.finish_systemd([(["systemctl"], ["sudo", "-n", "systemctl"], "defect-detector.service")], 1)
        self.assertEqual(run.call_args.args[0], ["sudo", "-n", "systemctl", "kill", "--signal=SIGKILL", "defect-detector.service"])

    def test_surviving_unit_is_an_error_even_after_force_stop(self):
        with patch.object(stop_stack, "unit_status", return_value=(False, {"ActiveState": "deactivating"})), \
                patch.object(stop_stack.time, "monotonic", side_effect=[0, 100, 100, 110]), \
                patch.object(stop_stack.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, "", "")):
            with self.assertRaisesRegex(RuntimeError, "did not stop"):
                stop_stack.finish_systemd([(["systemctl"], ["systemctl"], "defect-detector.service")], 1)

    def test_inactive_unit_with_pending_stop_job_is_not_considered_stopped(self):
        with patch.object(stop_stack.subprocess, "run", return_value=subprocess.CompletedProcess([], 0,
                "ActiveState=inactive\nMainPID=0\nJob=123\nControlGroup=\n", "")):
            self.assertFalse(stop_stack.unit_status(["systemctl"], "defect-detector.service")[0])


class TelemetryTests(unittest.TestCase):
    def test_cpu_percentages_use_deltas_without_double_counting_guest_time(self):
        _, baseline = rt.cpu_delta("cpu0 100 0 100 800 0 0 0 0 50 0\n", {})
        result, _ = rt.cpu_delta("cpu0 120 0 110 865 0 2 3 0 60 0\n", baseline)
        self.assertEqual(result["cpu0"]["busy_pct"], 35)
        self.assertEqual(result["cpu0"]["user_pct"], 20)
        self.assertEqual(result["cpu0"]["softirq_pct"], 3)

    def test_missing_metrics_are_explicit_and_records_are_timestamped(self):
        with tempfile.TemporaryDirectory() as directory:
            result = rt.read(Path(directory) / "unavailable")
            self.assertIn("unavailable", result)
            journal = rt.Journal(Path(directory))
            journal.write("samples", sources={"metric": result})
            journal.close()
            record = json.loads((Path(directory) / "rt-samples.jsonl").read_text())
            self.assertIn("ts", record)
            self.assertGreater(record["monotonic_ns"], 0)

    def test_sampler_lifecycle_without_linux_privileges(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(rt, "pin_collector", return_value={"collector": [0]}), \
                patch.object(rt, "configuration", return_value={}), \
                patch.object(rt, "irq_configuration", return_value={}), \
                patch.object(rt, "command_output", return_value={"unavailable": "no permission"}), \
                patch.object(rt, "kernel_stream", return_value=(None, None)), \
                patch.object(rt, "read", return_value={"value": "cpu0 1 0 1 8 0 0 0 0"}), \
                patch.object(rt, "processes", return_value={}), \
                patch.object(rt, "cpu_snapshot", return_value={}), \
                patch.object(rt, "temperature_snapshot", return_value={}), \
                patch.object(rt.os, "sysconf", return_value=100, create=True), \
                patch.object(rt.STOP, "is_set", side_effect=[False, True]), \
                patch.object(rt.STOP, "wait"):
            rt.collect(argparse.Namespace(output=Path(directory), root=Path("/project"), interval=1, parent_pid=None))
            record = json.loads((Path(directory) / "rt-samples.jsonl").read_text())
            self.assertIn("collection_ms", record)
            self.assertIn("collector_delay_ms", record)
            self.assertEqual(json.loads((Path(directory) / "rt-events.jsonl").read_text())["event"], "stopped")


if __name__ == "__main__":
    unittest.main()
