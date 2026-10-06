"""Статистика по стадиям из логов analisSurface (responses.log): среднее/медиана/p95.

  python benchmarks/log_stats.py logs\\2026-10-05_11-22-01\\responses.log
  python benchmarks/log_stats.py "logs/*/responses.log"

Сравнивайте запуск до и после обновления на одном и том же потоке кадров.
Нужен python_detector.file_logging: true.
"""

from __future__ import annotations

import glob
import re
import statistics
import sys

STAGES = ["py_align_ms", "py_diff_ms", "py_anomaly_ms", "py_fp_recheck_ms", "py_heatmap_ms", "py_total_ms"]


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    files = sorted({f for pattern in sys.argv[1:] for f in glob.glob(pattern)})
    data = {s: [] for s in STAGES}
    for path in files:
        with open(path, encoding="utf-8", errors="replace") as fh:
            for line in fh:
                for stage in STAGES:
                    for m in re.finditer(rf'"{stage}": ([0-9.]+)', line):
                        data[stage].append(float(m.group(1)))
    print(f"файлов: {len(files)}")
    print(f"{'стадия':<18}{'n':>7}{'среднее':>10}{'медиана':>10}{'p95':>10}")
    for stage in STAGES:
        v = sorted(data[stage])
        if not v:
            continue
        p95 = v[min(len(v) - 1, int(len(v) * 0.95))]
        print(f"{stage:<18}{len(v):>7}{statistics.mean(v):>10.1f}{statistics.median(v):>10.1f}{p95:>10.1f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
