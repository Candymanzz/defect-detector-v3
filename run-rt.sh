#!/usr/bin/env bash
# CPU affinity for this workstation: cores 2-5 and their SMT siblings 8-11.
# Override: IML_CPUSET=2,8 bash ./run-rt.sh --no-frontend
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IML_CPUSET="${IML_CPUSET:-2-5,8-11}"

if ! command -v taskset >/dev/null 2>&1; then
  echo "taskset is required (Ubuntu package: util-linux)." >&2
  exit 1
fi
if ! [[ "$IML_CPUSET" =~ ^[0-9]+(-[0-9]+)?(,[0-9]+(-[0-9]+)?)*$ ]]; then
  echo "Invalid IML_CPUSET: $IML_CPUSET. Example: 2-5,8-11" >&2
  exit 1
fi

# Verify every requested CPU is available before the launcher stops services.
taskset -c "$IML_CPUSET" python3 -c '
import os, sys
requested = set()
for item in sys.argv[1].split(","):
    bounds = [int(value) for value in item.split("-")]
    first, last = bounds[0], bounds[-1]
    if last < first:
        sys.exit("CPU range must be ascending: " + item)
    requested.update(range(first, last + 1))
actual = os.sched_getaffinity(0)
if actual != requested:
    sys.exit("Unavailable CPUs: " + str(sorted(requested - actual)))
print("Application CPUs: " + ",".join(map(str, sorted(actual))), flush=True)
' "$IML_CPUSET"

exec taskset -c "$IML_CPUSET" bash "$REPO_ROOT/run-supervised.sh" "$@"
