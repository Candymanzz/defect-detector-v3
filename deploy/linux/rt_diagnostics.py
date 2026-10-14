#!/usr/bin/env python3
"""Read-only RT/CPU telemetry during the application's actual workload."""
import argparse
from datetime import datetime, timezone
import gzip
import json
import logging
from logging.handlers import RotatingFileHandler
import os
from pathlib import Path
import platform
import signal
import subprocess
import sys
import threading
import time

from stack_processes import processes, select_services, stat_fields

CPU_ROOT = Path("/sys/devices/system/cpu")
STOP = threading.Event()


def read(path, limit=2 * 1024 * 1024):
    try:
        with open(path, "r", errors="replace") as handle:
            value = handle.read(limit + 1)
        return {"value": value[:limit].strip(), "truncated": len(value) > limit}
    except OSError as exc:
        return {"unavailable": str(exc)}


def cpu_list(text):
    result = set()
    for part in text.strip().split(","):
        if not part or part == "(null)":
            continue
        bounds = part.split("-")
        if len(bounds) > 2:
            raise ValueError("Invalid CPU list")
        first, last = int(bounds[0]), int(bounds[-1])
        if first < 0 or last < first or last - first > 65536:
            raise ValueError("Invalid CPU range")
        result.update(range(first, last + 1))
    return result


def pin_collector():
    inherited = sorted(os.sched_getaffinity(0))
    try:
        requested = os.environ.get("IML_RT_DIAGNOSTICS_CPUSET", "")
        if requested:
            target = cpu_list(requested)
        else:
            online = cpu_list(read(CPU_ROOT / "online").get("value", ""))
            isolated = cpu_list(read(CPU_ROOT / "isolated").get("value", ""))
            isolated |= cpu_list(read(CPU_ROOT / "nohz_full").get("value", ""))
            isolated |= cpu_list(os.environ.get("IML_CPUSET", ""))
            housekeeping = online - isolated
            target = {min(housekeeping)} if housekeeping else {min(inherited)}
        os.sched_setaffinity(0, target)
        os.nice(10)
        return {"inherited": inherited, "collector": sorted(os.sched_getaffinity(0)), "nice": os.getpriority(os.PRIO_PROCESS, 0)}
    except (OSError, ValueError) as exc:
        return {"inherited": inherited, "collector": sorted(os.sched_getaffinity(0)), "warning": str(exc)}


class Journal:
    def __init__(self, directory):
        self.handlers = {}
        directory.mkdir(parents=True, exist_ok=True)
        for name in ("samples", "configuration", "threads", "kernel", "events"):
            handler = RotatingFileHandler(directory / f"rt-{name}.jsonl", maxBytes=10 * 1024 * 1024,
                                          backupCount=5, encoding="utf-8")
            handler.setFormatter(logging.Formatter("%(message)s"))
            self.handlers[name] = handler

    def write(self, channel, **data):
        record = {"ts": datetime.now(timezone.utc).isoformat(timespec="milliseconds"),
                  "monotonic_ns": time.monotonic_ns(), **data}
        self.handlers[channel].emit(logging.LogRecord("rt", logging.INFO, "", 0,
                                                     json.dumps(record, ensure_ascii=False), (), None))

    def close(self):
        for handler in self.handlers.values():
            handler.close()


def cpu_snapshot():
    result = {}
    for folder in sorted(CPU_ROOT.glob("cpu[0-9]*")):
        fields = {}
        for group, pattern in (("topology", "*"), ("cpufreq", "*freq*"),
                               ("cpufreq", "*governor*"), ("thermal_throttle", "*")):
            for path in (folder / group).glob(pattern):
                if path.is_file():
                    fields[str(path.relative_to(folder))] = read(path, 4096)
        for state in (folder / "cpuidle").glob("state*"):
            for name in ("name", "desc", "latency", "residency", "usage", "time", "disable", "rejected"):
                fields[str(state.relative_to(folder) / name)] = read(state / name, 4096)
        result[folder.name] = fields
    return result


def configuration():
    paths = ["/proc/cmdline", "/proc/version", "/sys/kernel/realtime",
             "/sys/devices/system/cpu/online", "/sys/devices/system/cpu/offline",
             "/sys/devices/system/cpu/isolated", "/sys/devices/system/cpu/nohz_full",
             "/sys/devices/system/clocksource/clocksource0/current_clocksource",
             "/sys/devices/system/clocksource/clocksource0/available_clocksource",
             "/proc/sys/kernel/sched_rt_period_us", "/proc/sys/kernel/sched_rt_runtime_us",
             "/proc/sys/kernel/sched_rr_timeslice_ms", "/proc/sys/kernel/sched_schedstats",
             "/proc/sys/kernel/timer_migration", "/proc/sys/kernel/watchdog",
             "/proc/sys/kernel/nmi_watchdog", "/proc/sys/kernel/watchdog_cpumask",
             "/proc/sys/kernel/numa_balancing", "/proc/sys/kernel/perf_event_paranoid",
             "/proc/sys/kernel/randomize_va_space", "/proc/sys/vm/swappiness",
             "/sys/devices/system/cpu/smt/control", "/sys/devices/system/cpu/intel_pstate/status",
             "/sys/devices/system/cpu/intel_pstate/no_turbo", "/sys/kernel/debug/sched/features"]
    settings = {path: read(path) for path in paths}
    config_path = Path("/boot") / ("config-" + platform.release())
    config = read(config_path)
    if "unavailable" in config and Path("/proc/config.gz").exists():
        try:
            with gzip.open("/proc/config.gz", "rt") as handle:
                config = {"value": handle.read(2 * 1024 * 1024)}
        except OSError as exc:
            config = {"unavailable": str(exc)}
    keys = ("CONFIG_PREEMPT", "CONFIG_NO_HZ", "CONFIG_RCU", "CONFIG_HZ", "CONFIG_SCHED",
            "CONFIG_IRQ", "CONFIG_CPU_FREQ", "CONFIG_CPU_IDLE", "CONFIG_PSI", "CONFIG_HIGH_RES")
    selected = [line for line in config.get("value", "").splitlines()
                if any(line.startswith(key) or line.startswith("# " + key) for key in keys)]
    return {"uname": platform.uname()._asdict(), "settings": settings, "kernel_config":
            selected if selected else config, "cpus": cpu_snapshot(),
            "clock_ticks_per_second": os.sysconf("SC_CLK_TCK"),
            "cpu_load_note": "Percentages are /proc/stat deltas; guest time is included in user/nice. iowait is an estimate."}


def irq_configuration():
    result = {}
    for folder in Path("/proc/irq").glob("[0-9]*"):
        result[folder.name] = {name: read(folder / name, 4096) for name in
                               ("smp_affinity_list", "effective_affinity_list", "node")}
    result["default_smp_affinity"] = read("/proc/irq/default_smp_affinity")
    return result


def cpu_delta(current, previous):
    rows = {}
    for line in current.splitlines():
        parts = line.split()
        if not parts or not parts[0].startswith("cpu"):
            continue
        rows[parts[0]] = [int(value) for value in parts[1:9]]
    result = {}
    labels = ("user", "nice", "system", "idle", "iowait", "irq", "softirq", "steal")
    for name, values in rows.items():
        old = previous.get(name)
        if old is None or len(old) != len(values):
            continue
        diffs = [max(0, value - baseline) for value, baseline in zip(values, old)]
        total = sum(diffs)
        if total:
            result[name] = {label + "_pct": round(value * 100 / total, 3) for label, value in zip(labels, diffs)}
            result[name]["busy_pct"] = round((total - diffs[3] - diffs[4]) * 100 / total, 3)
    return result, rows


def thread_snapshot(entries, limit=4096):
    result = []
    clipped = False
    for pid, process in entries.items():
        for task in Path(f"/proc/{pid}/task").glob("[0-9]*"):
            if len(result) >= limit:
                clipped = True
                break
            try:
                stat = stat_fields((task / "stat").read_text())
                status = read(task / "status")
                fields = dict(line.split(":", 1) for line in status.get("value", "").splitlines() if ":" in line)
                result.append({"pid": pid, "tid": int(task.name), **stat,
                               "affinity": fields.get("Cpus_allowed_list", "").strip(),
                               "voluntary_context_switches": fields.get("voluntary_ctxt_switches", "").strip(),
                               "involuntary_context_switches": fields.get("nonvoluntary_ctxt_switches", "").strip(),
                               "schedstat": read(task / "schedstat"), "sched": read(task / "sched", 16384),
                               "io": read(task / "io"), "cgroup": read(task / "cgroup"),
                               "wchan": read(task / "wchan", 4096)})
            except (OSError, ValueError, IndexError):
                continue
    return {"tasks": result, "truncated": clipped, "limit": limit}


def cgroups(entries):
    result = {}
    for pid in entries:
        for line in read(f"/proc/{pid}/cgroup").get("value", "").splitlines():
            if not line.startswith("0::"):
                continue
            group = line[3:]
            folder = Path("/sys/fs/cgroup") / group.lstrip("/")
            result[group] = {name: read(folder / name, 16384) for name in (
                "cpu.stat", "cpu.max", "cpu.weight", "cpu.pressure", "cpuset.cpus.effective",
                "cpuset.mems.effective", "memory.current", "memory.events", "memory.pressure", "io.pressure")}
    return result


def kernel_stream(journal):
    try:
        process = subprocess.Popen(["journalctl", "-k", "--since=now", "--follow", "--no-pager", "-o", "short-iso"],
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    except OSError as exc:
        journal.write("events", event="kernel-journal-unavailable", error=str(exc))
        return None, None

    def consume():
        for line in process.stdout:
            journal.write("kernel", line=line.strip()[:8192])

    thread = threading.Thread(target=consume, daemon=True)
    thread.start()
    return process, thread


def collect(args):
    journal = Journal(args.output)
    journal.write("configuration", event="startup", affinity=pin_collector(), root=str(args.root),
                  interval_seconds=args.interval, **configuration())
    journal.write("configuration", event="irq-affinity", irqs=irq_configuration())
    journal.write("kernel", event="initial-dmesg", result=command_output(["dmesg", "--ctime", "--level=emerg,alert,crit,err,warn"]))
    kernel, kernel_thread = kernel_stream(journal)
    previous = {}
    previous_processes = {}
    services = {}
    next_details = next_config = next_sample = time.monotonic()
    last_details = next_details
    parent_start = stat_fields(Path(f"/proc/{args.parent_pid}/stat").read_text())["starttime"] if args.parent_pid else None
    try:
        while not STOP.is_set():
            if args.parent_pid:
                try:
                    parent = stat_fields(Path(f"/proc/{args.parent_pid}/stat").read_text())
                    if parent["starttime"] != parent_start or parent["state"] == "Z":
                        break
                except OSError:
                    break
            started = time.monotonic()
            raw = read("/proc/stat")
            percentages, previous = cpu_delta(raw.get("value", ""), previous)
            paths = ("/proc/interrupts", "/proc/softirqs", "/proc/schedstat", "/proc/loadavg",
                     "/proc/pressure/cpu", "/proc/pressure/memory", "/proc/pressure/io",
                     "/proc/meminfo", "/proc/vmstat", "/proc/diskstats", "/proc/net/dev")
            data = {path: read(path) for path in paths}
            if started >= next_details:
                all_processes = processes()
                services = select_services(all_processes, args.root, {os.getpid()})
                busy = []
                elapsed = max(0.001, started - last_details)
                last_details = started
                ticks = os.sysconf("SC_CLK_TCK")
                for pid, entry in all_processes.items():
                    key = (pid, entry["starttime"])
                    used = entry["utime"] + entry["stime"]
                    if key in previous_processes:
                        busy.append({"pid": pid, "name": entry["name"], "last_cpu": entry["cpu"],
                                     "cpu_pct": round(max(0, used - previous_processes[key]) * 100 / ticks / elapsed, 2),
                                     "is_service": pid in services})
                    previous_processes[key] = used
                previous_processes = {(pid, item["starttime"]): item["utime"] + item["stime"] for pid, item in all_processes.items()}
                journal.write("threads", **thread_snapshot(services), cgroups=cgroups(services),
                              busiest_processes=sorted(busy, key=lambda item: item["cpu_pct"], reverse=True)[:30],
                              cpu_state=cpu_snapshot(), temperatures=temperature_snapshot())
                next_details = started + 5
            if started >= next_config:
                journal.write("configuration", event="irq-affinity", irqs=irq_configuration())
                next_config = started + 30
            journal.write("samples", cpu_percentages=percentages, proc_stat=raw, sources=data,
                          service_pids=sorted(services), collector_delay_ms=round(max(0, started - next_sample) * 1000, 3),
                          collection_ms=round((time.monotonic() - started) * 1000, 3))
            next_sample = started + args.interval
            STOP.wait(max(0, next_sample - time.monotonic()))
    finally:
        if kernel is not None:
            kernel.terminate()
            try:
                kernel.wait(timeout=3)
            except subprocess.TimeoutExpired:
                kernel.kill()
                kernel.wait(timeout=3)
            kernel_thread.join(timeout=3)
        journal.write("events", event="stopped")
        journal.close()


def command_output(command):
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=5)
        return {"exit_code": result.returncode, "stdout": result.stdout[-262144:], "stderr": result.stderr[-4096:]}
    except (OSError, subprocess.TimeoutExpired) as exc:
        return {"unavailable": str(exc)}


def temperature_snapshot():
    result = {}
    for folder in Path("/sys/class/thermal").glob("thermal_zone*"):
        result[str(folder)] = {name: read(folder / name, 4096) for name in ("type", "temp", "policy")}
    for folder in Path("/sys/class/hwmon").glob("hwmon*"):
        result[str(folder)] = {path.name: read(path, 4096) for path in folder.glob("temp*_*")}
        result[str(folder)]["name"] = read(folder / "name", 4096)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--interval", type=float, default=1)
    parser.add_argument("--parent-pid", type=int)
    args = parser.parse_args()
    if not sys.platform.startswith("linux"):
        parser.error("Linux is required")
    if not 0.2 <= args.interval <= 60:
        parser.error("interval must be between 0.2 and 60 seconds")
    args.root = args.root.resolve()
    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, lambda *_: STOP.set())
    collect(args)


if __name__ == "__main__":
    main()
