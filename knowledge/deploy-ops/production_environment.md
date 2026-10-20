# Production environment — six cores and RT

Back to [project context](../PROJECT_CONTEXT.md) · [deployment runbook](./overview.md) · [latency requirement](../performance/latency_requirements.md)

Updated **2026-10-20**, documentation baseline `testlinux` / `9d8a4b66`. No remote hardware inspection or configuration change was performed.

## 1. Current owner-provided context

**[owner: 2026-10-20] The production computer has six CPU cores and an RT environment.** Use this as the current hardware context instead of the older four-core assumption in discussions. RT describes the configured execution environment; it is not a separate kind of CPU core.

The repository describes Ubuntu 24.04 with a PREEMPT_RT kernel. The exact running kernel version, physical/logical CPU mapping, SMT, effective affinity and per-thread scheduling policy have not been freshly checked. Do not infer that all six cores are exclusively available to the application or that all Java/Python/C threads run under `SCHED_FIFO`/`SCHED_RR`.

## 2. Existing profile versus current machine

| Source | What it establishes | What it does not establish |
|---|---|---|
| Current owner statement | Six-core production machine, RT environment | Effective CPU set and scheduling priority of each service |
| `run-rt.sh` [code] | Default `IML_CPUSET=2-5,8-11`; validates and applies affinity through `taskset` before launching the supervisor | Current service startup command, IRQ isolation, RT scheduling policy or a 500 ms guarantee |
| `RT-LINUX.md` [doc] | Recorded six-physical-core/12-logical-CPU topology; default app set contains four physical cores and their siblings, housekeeping uses the other two | That this allocation remains the actual production configuration |
| `config.exemple/blocks/01-core.yaml` [code] | Six Python servers, three inspection threads per process in the template | Six reserved CPU cores or actual production worker settings |

These statements are compatible as descriptions of different layers, but the effective allocation is unresolved. **Do not silently change the CPU mask or rewrite the historical profile to claim six app cores.** Record a new measured profile if deployment has changed. Production `config/`, the installed systemd unit and some `tools/` files are not versioned here.

## 3. Read-only checks when machine access is available

Run on the production Linux host, not on the developer's Windows computer:

```bash
uname -r
lscpu -e=CPU,CORE,SOCKET,ONLINE
cat /proc/cmdline
cat /sys/devices/system/cpu/nohz_full
systemctl show defect-detector.service -p MainPID -p CPUAffinity -p AllowedCPUs -p CPUSchedulingPolicy -p CPUSchedulingPriority
ps -eLo pid,tid,cls,rtprio,psr,comm
```

For an actual orchestrator or child-service PID, `taskset -apc <PID>` reports every thread's affinity. Check child services too; systemd property defaults alone do not prove their effective state. Existing RT diagnostics also collect thread policies, affinity, CPU/IRQ/cgroup information; see [runbook](./overview.md).

Attach the date, host identifier, deployed commit, effective configuration, CPU/core mapping, service affinities, thread policies and CPU utilisation to benchmarks. Distinguish physical cores from SMT threads and from Python worker counts.

## 4. Implication for optimisation

Optimisation targets the six-core RT host, with the usable app CPU budget measured first. Benchmark complete enabled-camera groups while geometry, acquisition, Java, Python and background artifacts compete for those CPUs. More processes or threads can increase queueing; choose limits from full-cycle P95/P99/max, including learning updates. RT configuration does not remove image-processing cost, blocking I/O, JVM pauses or frame-wait delays. See the [500 ms acceptance requirement](../performance/latency_requirements.md).
