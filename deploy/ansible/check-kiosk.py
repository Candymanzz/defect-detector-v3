#!/usr/bin/env python3
"""Read-only verification using the installed application's process recognizer."""
import importlib.util
import json
import sys
from pathlib import Path

sys.dont_write_bytecode = True
root = Path(sys.argv[1]).resolve()
spec = importlib.util.spec_from_file_location("installed_stack_processes", root / "deploy/linux/stack_processes.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
entries = module.processes()
mode = sys.argv[2]
if mode == "running":
    controllers = [e for e in entries.values() if module.is_kiosk_controller(e, root)]
    if not controllers or any(e["state"] in {"T", "t", "Z"} for e in controllers):
        sys.exit("Kiosk controller missing or suspended")
elif mode == "stopped":
    saved = json.loads((root / ".kiosk-paused.json").read_text())
    if not isinstance(saved, list) or not saved:
        sys.exit("No saved kiosk controllers")
    saved_ids = set()
    for old in saved:
        current = entries.get(old["pid"])
        if not current or current["starttime"] != old["starttime"] or not module.is_kiosk_controller(current, root) or current["state"] not in {"T", "t"}:
            sys.exit("Saved kiosk controller is missing, replaced, or not suspended")
        saved_ids.add(current["pid"])
    if any(module.is_kiosk_controller(e, root) and e["pid"] not in saved_ids for e in entries.values()):
        sys.exit("Unexpected additional kiosk controller")
    remaining = module.select_services(entries, root, module.ancestors(entries, __import__("os").getpid()))
    if remaining:
        sys.exit("Application processes still running: " + ",".join(map(str, remaining)))
else:
    sys.exit("Unknown check mode")
print("Kiosk state verified: " + mode)
