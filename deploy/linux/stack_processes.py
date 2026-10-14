"""Identify this checkout's application processes without killing by port number."""
import os
from pathlib import Path


def stat_fields(text):
    end = text.rfind(")")
    fields = text[end + 2:].split()
    return {"name": text[text.find("(") + 1:end], "state": fields[0],
            "ppid": int(fields[1]), "utime": int(fields[11]), "stime": int(fields[12]),
            "priority": int(fields[15]), "nice": int(fields[16]),
            "starttime": int(fields[19]), "cpu": int(fields[36]),
            "rt_priority": int(fields[37]), "policy": int(fields[38])}


def processes(proc=Path("/proc")):
    result = {}
    for folder in proc.iterdir():
        if not folder.name.isdigit():
            continue
        try:
            entry = stat_fields((folder / "stat").read_text())
            try:
                cwd = os.readlink(folder / "cwd")
            except OSError:
                cwd = ""
            try:
                args = (folder / "cmdline").read_bytes().decode(errors="replace").split("\0")[:-1]
            except OSError:
                args = []
            entry.update(pid=int(folder.name), cwd=cwd, args=args)
            result[entry["pid"]] = entry
        except (OSError, ValueError, IndexError):
            continue
    return result


def is_service(entry, root):
    args = entry["args"]
    if not args:
        return False
    command = " ".join(args)
    executable = Path(args[0]).name.lower()
    frontend = os.environ.get("IML_INSTALLED_FRONTEND_DIR", "/opt/defect-detector-ui")
    if executable in {"electron", "node"} and (entry["cwd"] == frontend or entry["cwd"].startswith(frontend + "/")):
        return True
    root = str(Path(root).resolve())
    belongs = entry["cwd"] == root or entry["cwd"].startswith(root + "/")
    belongs = belongs or any(arg == root or arg.startswith(root + "/") for arg in args)
    if not belongs:
        return False
    if executable in {"java", "java.exe"}:
        return any(marker in command for marker in (
            "StackSupervisorMain", "OrchestratorMain", "orchestrator-", "java-geometry-service",
            "java-positioning-service", "GeometryRunnerMain", "PositioningRunnerMain"))
    if executable.startswith("python") or executable == "uvicorn":
        return "uvicorn" in args or "app.main:app" in command
    if executable in {"camera_worker", "camera_worker.exe"}:
        return True
    if executable in {"node", "electron", "npm", "npm run dev", "npm run start"}:
        return "/front-end" in entry["cwd"] or "/front-end/" in command
    return executable in {"bash", "sh"} and any(
        Path(arg).name in {"run-supervised.sh", "run-rt.sh"} for arg in args[1:])


def is_kiosk_controller(entry, root):
    args = entry["args"]
    if not args or Path(args[0]).name not in {"sh", "bash"}:
        return False
    known = {"/usr/local/bin/defect-detector-kiosk", str(Path(root).resolve() / "deploy/kiosk/session.sh")}
    return any(arg in known for arg in args[1:])


def select_services(entries, root, excluded=()):
    selected = {pid for pid, entry in entries.items() if is_service(entry, root) and pid not in excluded}
    while True:
        children = {pid for pid, entry in entries.items() if entry["ppid"] in selected and pid not in excluded}
        expanded = selected | children
        if expanded == selected:
            return {pid: entries[pid] for pid in selected if entries[pid]["state"] != "Z"}
        selected = expanded


def ancestors(entries, pid):
    result = set()
    while pid and pid not in result:
        result.add(pid)
        pid = entries.get(pid, {}).get("ppid", 0)
    return result
