"""Stop supervisor first, then all managed services; fail if any survive."""
import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time

from stack_processes import ancestors, is_kiosk_controller, processes, select_services, stat_fields


def stop_systemd(root):
    stopping = []
    if not shutil.which("systemctl") or not Path("/run/systemd/system").is_dir():
        return stopping
    unit = os.environ.get("IML_SYSTEMD_UNIT", "defect-detector.service")
    # A launcher started by this unit must not stop its own systemd cgroup.
    own_cgroup = Path("/proc/self/cgroup").read_text()
    if any(unit in line.split(":", 2)[-1].split("/") for line in own_cgroup.splitlines()):
        return stopping
    for scope in ([], ["--user"]):
        command = ["systemctl", *scope]
        query = subprocess.run(command + ["show", unit, "--property=ActiveState,WorkingDirectory,ExecStart"],
                               capture_output=True, text=True, timeout=10)
        if query.returncode:
            continue
        properties = dict(line.split("=", 1) for line in query.stdout.splitlines() if "=" in line)
        if properties.get("ActiveState") not in {"active", "activating", "reloading", "deactivating"}:
            continue
        # Do not stop another checkout's service, even if it has the same unit name.
        if properties.get("WorkingDirectory") != str(root) and str(root) + "/" not in properties.get("ExecStart", ""):
            continue
        privileged = command
        if not scope and os.geteuid() != 0:
            if not shutil.which("sudo"):
                raise RuntimeError(f"Run sudo systemctl stop {unit} before rebuilding")
            privileged = ["sudo", "-n", *command]
        # Queue the stop job; waiting here prevents us from stopping stuck children.
        completed = subprocess.run(privileged + ["--no-block", "stop", unit],
                                   capture_output=True, text=True, timeout=10)
        if completed.returncode:
            raise RuntimeError(f"Cannot stop {unit}: {completed.stderr.strip()}. Run {' '.join(command)} stop {unit} with sufficient permissions")
        print(f"Stopping systemd unit {unit} (non-blocking)", flush=True)
        stopping.append((command, privileged, unit))
    return stopping


def unit_status(command, unit):
    query = subprocess.run(command + ["show", unit, "--property=ActiveState,SubState,MainPID,ControlGroup,Job"],
                           capture_output=True, text=True, timeout=5)
    if query.returncode:
        raise RuntimeError(f"Cannot verify stopped unit {unit}: {query.stderr.strip()}")
    properties = dict(line.split("=", 1) for line in query.stdout.splitlines() if "=" in line)
    group = properties.get("ControlGroup", "")
    populated = False
    if group:
        group_path = Path("/sys/fs/cgroup") / group.lstrip("/") / "cgroup.procs"
        try:
            populated = bool(group_path.read_text().strip())
        except FileNotFoundError:
            pass
        except OSError as exc:
            raise RuntimeError(f"Cannot verify cgroup for {unit}: {exc}") from exc
    stopped = properties.get("ActiveState") in {"inactive", "failed"}
    stopped = stopped and properties.get("MainPID", "0") == "0" and not populated
    stopped = stopped and properties.get("Job", "0").split(" ", 1)[0] in {"", "0"}
    return stopped, properties


def finish_systemd(stopping, timeout):
    for command, privileged, unit in stopping:
        deadline = time.monotonic() + timeout
        while True:
            stopped, properties = unit_status(command, unit)
            if stopped:
                break
            if time.monotonic() >= deadline:
                print(f"Unit {unit} is still {properties.get('ActiveState')}; terminating its remaining processes", flush=True)
                killed = subprocess.run(privileged + ["kill", "--signal=SIGKILL", unit],
                                        capture_output=True, text=True, timeout=10)
                # The unit can disappear between show and kill; always check state again.
                final_deadline = time.monotonic() + 5
                while True:
                    stopped, properties = unit_status(command, unit)
                    if stopped:
                        break
                    if time.monotonic() >= final_deadline:
                        raise RuntimeError(f"Unit {unit} did not stop: {properties}; {killed.stderr.strip()}. Check sudo systemctl status {unit} and sudo journalctl -u {unit} -n 100")
                    time.sleep(0.2)
                break
            time.sleep(0.2)
        print(f"Stopped systemd unit {unit}; it remains stopped during the manual launch", flush=True)


def alive(entry):
    try:
        current = stat_fields(Path(f"/proc/{entry['pid']}/stat").read_text())
        return current["starttime"] == entry["starttime"] and current["state"] != "Z"
    except (OSError, ValueError, IndexError):
        return False


def send(entry, sig):
    if alive(entry):
        try:
            os.kill(entry["pid"], sig)
        except ProcessLookupError:
            pass
        except PermissionError:
            if not shutil.which("sudo"):
                raise RuntimeError(f"No permission to signal application pid={entry['pid']}")
            result = subprocess.run(["sudo", "-n", "kill", f"-{int(sig)}", str(entry["pid"])],
                                    capture_output=True, text=True, timeout=5)
            if result.returncode and alive(entry):
                raise RuntimeError(f"No permission to stop application pid={entry['pid']}. Run sudo bash stop-dev.sh first")


def pause_kiosk(snapshot, root):
    state = root / ".kiosk-paused.json"
    controllers = [entry for entry in snapshot.values() if is_kiosk_controller(entry, root)]
    if not controllers:
        return
    # Suspend the respawn loop rather than terminate the graphical login session.
    # Save identity first so a partial stop can always be resumed safely.
    saved = [{"pid": entry["pid"], "starttime": entry["starttime"]} for entry in controllers]
    state.write_text(json.dumps(saved), encoding="utf-8")
    for entry in controllers:
        send(entry, signal.SIGSTOP)
    print("Kiosk respawn loop paused during rebuild", flush=True)


def resume_kiosk(root):
    state = root / ".kiosk-paused.json"
    if not state.exists():
        return
    saved = json.loads(state.read_text(encoding="utf-8"))
    snapshot = processes()
    for entry in saved:
        current = snapshot.get(entry["pid"])
        if current and current["starttime"] == entry["starttime"] and is_kiosk_controller(current, root):
            send(current, signal.SIGCONT)
    state.unlink()
    print("Kiosk respawn loop resumed", flush=True)


def wait_for(entries, seconds):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if not any(alive(entry) for entry in entries):
            return
        time.sleep(0.1)


def stop(root, timeout, processes_only=False, skip_kiosk=False):
    # Snapshot descendants before a supervisor shutdown can reparent them.
    snapshot = processes()
    excluded = ancestors(snapshot, os.getpid())
    targets = select_services(snapshot, root, excluded, include_kiosk=not skip_kiosk)
    stopping = [] if processes_only else (stop_systemd(root) or [])
    if not skip_kiosk:
        pause_kiosk(snapshot, root)
    supervisors = [entry for entry in targets.values() if "StackSupervisorMain" in " ".join(entry["args"])]
    for entry in supervisors:
        send(entry, signal.SIGTERM)
    wait_for(supervisors, min(timeout, 10))
    for entry in supervisors:
        send(entry, signal.SIGKILL)
    # Include any services started while the old supervisor was shutting down.
    targets.update(select_services(processes(), root, excluded, include_kiosk=not skip_kiosk))
    for entry in targets.values():
        print(f"Stop pid={entry['pid']} name={entry['name']}", flush=True)
        send(entry, signal.SIGTERM)
    wait_for(list(targets.values()), timeout)
    # A launcher's EXIT trap may have started a cleanup child during SIGTERM.
    targets.update(select_services(processes(), root, excluded, include_kiosk=not skip_kiosk))
    for entry in targets.values():
        send(entry, signal.SIGKILL)
    wait_for(list(targets.values()), 3)
    finish_systemd(stopping, timeout)
    survivors = select_services(processes(), root, excluded, include_kiosk=not skip_kiosk)
    if survivors:
        raise RuntimeError("Services are still running: " + ", ".join(map(str, survivors)))
    print("Application stack stopped", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--timeout", type=float, default=15)
    parser.add_argument("--resume-kiosk", action="store_true")
    parser.add_argument("--processes-only", action="store_true",
                        help="Launcher EXIT cleanup: stop processes without creating a systemd stop job")
    parser.add_argument("--skip-kiosk", action="store_true",
                        help="Backend launcher: leave the separate graphical kiosk session running")
    args = parser.parse_args()
    if not sys.platform.startswith("linux"):
        parser.error("This tool requires Linux /proc")
    if not 0 < args.timeout <= 60:
        parser.error("--timeout must be between 0 and 60 seconds")
    try:
        if args.resume_kiosk:
            resume_kiosk(args.root.resolve())
        else:
            stop(args.root.resolve(), args.timeout, args.processes_only, args.skip_kiosk)
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as exc:
        print(f"ERROR: {exc}; rebuild cancelled", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
