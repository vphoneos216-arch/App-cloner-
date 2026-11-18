#!/usr/bin/env python3
"""Turns the frame timestamps captured by test-matrix.sh into pacing figures.

Uses the same arithmetic as the app's FramePacing: nearest-rank percentiles, and a jank
threshold of twice the display refresh period. No smoothing, and a window with too few
completed frames is reported as having no data rather than given a number.

Usage:
  tools/frame-report.py results/<leg>/raw/latency-*.txt
"""
import math
import sys

PENDING = 2**63 - 1


def parse(path):
    with open(path, "r", errors="replace") as handle:
        lines = [line.strip() for line in handle if line.strip()]
    if not lines:
        return None, []
    refresh = None
    first = lines[0].split()
    if len(first) == 1 and first[0].isdigit() and int(first[0]) > 0:
        refresh = int(first[0])
        lines = lines[1:]
    present = []
    for line in lines:
        columns = line.replace(",", " ").split()
        if len(columns) < 3:
            continue
        try:
            actual = int(columns[1])
        except ValueError:
            continue
        if 0 < actual != PENDING:
            present.append(actual)
    return refresh, sorted(present)


def percentile(sorted_values, pct):
    if not sorted_values:
        return None
    if len(sorted_values) == 1:
        return sorted_values[0]
    rank = min(max(math.ceil(pct / 100.0 * len(sorted_values)), 1), len(sorted_values))
    return sorted_values[rank - 1]


def analyse(refresh, timestamps):
    if len(timestamps) < 3:
        return None
    intervals = [b - a for a, b in zip(timestamps, timestamps[1:]) if b - a > 0]
    if len(intervals) < 2:
        return None
    span = timestamps[-1] - timestamps[0]
    if span <= 0:
        return None
    ordered = sorted(intervals)
    median = percentile(ordered, 50.0)
    threshold = round(refresh * 2.0) if refresh else round(median * 1.5)
    return {
        "frames": len(intervals) + 1,
        "fps": len(intervals) * 1e9 / span,
        "p50_ms": median / 1e6,
        "p95_ms": percentile(ordered, 95.0) / 1e6,
        "p99_ms": percentile(ordered, 99.0) / 1e6,
        "max_ms": ordered[-1] / 1e6,
        "jank": sum(1 for i in intervals if i > threshold),
        "threshold_ms": threshold / 1e6,
    }


def main(paths):
    if not paths:
        print(__doc__)
        return 2
    all_intervals = []
    refresh_seen = None
    print(f"{'file':<28} {'frames':>7} {'fps':>7} {'p50':>7} {'p95':>7} {'p99':>8} {'jank':>6}")
    for path in paths:
        refresh, timestamps = parse(path)
        refresh_seen = refresh_seen or refresh
        stats = analyse(refresh, timestamps)
        name = path.split("/")[-1][:28]
        if stats is None:
            print(f"{name:<28} {'no data':>7}")
            continue
        print(
            f"{name:<28} {stats['frames']:>7} {stats['fps']:>7.1f} "
            f"{stats['p50_ms']:>7.2f} {stats['p95_ms']:>7.2f} {stats['p99_ms']:>8.2f} "
            f"{stats['jank']:>6}"
        )
        all_intervals += [b - a for a, b in zip(timestamps, timestamps[1:]) if b - a > 0]

    if len(all_intervals) >= 2:
        ordered = sorted(all_intervals)
        total = sum(ordered)
        threshold = round(refresh_seen * 2.0) if refresh_seen else round(percentile(ordered, 50.0) * 1.5)
        print()
        print("across all windows")
        print(f"  frames          {len(ordered) + 1}")
        print(f"  mean fps        {len(ordered) * 1e9 / total:.1f}")
        print(f"  p50 frame time  {percentile(ordered, 50.0) / 1e6:.2f} ms")
        print(f"  p95 frame time  {percentile(ordered, 95.0) / 1e6:.2f} ms")
        print(f"  p99 frame time  {percentile(ordered, 99.0) / 1e6:.2f} ms")
        print(f"  worst frame     {ordered[-1] / 1e6:.2f} ms")
        print(f"  jank frames     {sum(1 for i in ordered if i > threshold)} "
              f"(> {threshold / 1e6:.2f} ms)")
        if refresh_seen:
            print(f"  display refresh {refresh_seen / 1e6:.2f} ms")
        else:
            print("  display refresh not reported; jank threshold fell back to 1.5x median")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
