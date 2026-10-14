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
    if not shutil.which("systemctl") or not Path("/run/systemd/system").is_dir():
        return
    unit = os.environ.get("IML_SYSTEMD_UNIT", "defect-detector.service")
    # A launcher started by this unit must not stop its own systemd cgroup.
    own_cgroup = Path("/proc/self/cgroup").read_text()
    if any(unit in line.split(":", 2)[-1].split("/") for line in own_cgroup.splitlines()):
        return
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
        stop = command + ["stop", unit]
        if not scope and os.geteuid() != 0:
            if not shutil.which("sudo"):
                raise RuntimeError(f"Run sudo systemctl stop {unit} before rebuilding")
            stop = ["sudo", "-n", *stop]
        completed = subprocess.run(stop, capture_output=True, text=True, timeout=45)
        if completed.returncode:
            raise RuntimeError(f"Cannot stop {unit}: {completed.stderr.strip()}. Run {' '.join(command)} stop {unit} with sufficient permissions")
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


def stop(root, timeout):
    # Snapshot descendants before a supervisor shutdown can reparent them.
    snapshot = processes()
    excluded = ancestors(snapshot, os.getpid())
    targets = select_services(snapshot, root, excluded)
    stop_systemd(root)
    pause_kiosk(snapshot, root)
    supervisors = [entry for entry in targets.values() if "StackSupervisorMain" in " ".join(entry["args"])]
    for entry in supervisors:
        send(entry, signal.SIGTERM)
    wait_for(supervisors, min(timeout, 10))
    for entry in supervisors:
        send(entry, signal.SIGKILL)
    # Include any services started while the old supervisor was shutting down.
    targets.update(select_services(processes(), root, excluded))
    for entry in targets.values():
        print(f"Stop pid={entry['pid']} name={entry['name']}", flush=True)
        send(entry, signal.SIGTERM)
    wait_for(list(targets.values()), timeout)
    # A launcher's EXIT trap may have started a cleanup child during SIGTERM.
    targets.update(select_services(processes(), root, excluded))
    for entry in targets.values():
        send(entry, signal.SIGKILL)
    wait_for(list(targets.values()), 3)
    survivors = select_services(processes(), root, excluded)
    if survivors:
        raise RuntimeError("Services are still running: " + ", ".join(map(str, survivors)))
    print("Application stack stopped", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--timeout", type=float, default=15)
    parser.add_argument("--resume-kiosk", action="store_true")
    args = parser.parse_args()
    if not sys.platform.startswith("linux"):
        parser.error("This tool requires Linux /proc")
    if not 0 < args.timeout <= 60:
        parser.error("--timeout must be between 0 and 60 seconds")
    try:
        if args.resume_kiosk:
            resume_kiosk(args.root.resolve())
        else:
            stop(args.root.resolve(), args.timeout)
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as exc:
        print(f"ERROR: {exc}; rebuild cancelled", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
