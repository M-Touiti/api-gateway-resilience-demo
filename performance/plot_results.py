#!/usr/bin/env python3
"""Builds the report's charts and tables from performance/results/*.json.

    pip install matplotlib
    python performance/plot_results.py

Charts go to performance/charts/. Markdown tables are printed to stdout.
"""
import json
import statistics
from collections import defaultdict
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import matplotlib.ticker  # noqa: E402

PERF = Path(__file__).resolve().parent
RESULTS = PERF / "results"
BEFORE_FIXES = RESULTS / "before-fixes"
CHARTS = PERF / "charts"

SURFACE = "#fcfcfb"
INK = "#0b0b0b"
INK_2 = "#52514e"
GRID = "#e6e5e0"
BLUE, ORANGE, AQUA = "#2a78d6", "#eb6834", "#1baf7a"

plt.rcParams.update({
    "figure.facecolor": SURFACE, "axes.facecolor": SURFACE, "savefig.facecolor": SURFACE,
    "font.size": 11, "text.color": INK, "axes.labelcolor": INK_2,
    "xtick.color": INK_2, "ytick.color": INK_2, "axes.edgecolor": GRID,
    "axes.spines.top": False, "axes.spines.right": False, "axes.spines.left": False,
    "axes.grid": True, "axes.axisbelow": True, "grid.color": GRID, "grid.linewidth": 0.8,
    "xtick.major.size": 0, "ytick.major.size": 0, "axes.titlelocation": "left",
    "axes.titlesize": 12, "axes.titleweight": "bold", "legend.frameon": False,
})


def load(name):
    """Prefers a result from the current code, and otherwise uses the one from before the fixes."""
    path = RESULTS / f"{name}.json"
    if not path.exists():
        path = BEFORE_FIXES / f"{name}.json"
    return json.loads(path.read_text(encoding="utf-8"))


def capacity_rows(name):
    data = load(f"capacity-{name}")
    grouped = defaultdict(list)
    for run in data["runs"]:
        if not run.get("host_paused"):  # a suspended host says nothing about the gateway
            grouped[run["target_rps"]].append(run)
    return grouped


def med(runs, key):
    return statistics.median(run["latency_ms"][key] for run in runs)


def worst(runs, key):
    return max(run["latency_ms"][key] for run in runs)


def gateway_cpu(runs):
    values = [v["cpu_avg_pct"] for run in runs for k, v in run["containers"].items()
              if k.startswith("gateway")]
    return statistics.mean(values)


def titled(ax, title, subtitle):
    ax.set_title(title, pad=24)
    ax.text(0, 1.02, subtitle, transform=ax.transAxes, fontsize=9.5, color=INK_2, va="bottom")


def chart_capacity():
    series = [("shipped-1gw", "1 gateway, as shipped", BLUE),
              ("tuned-1gw", "1 gateway, tuned config", ORANGE),
              ("shipped-2gw", "2 gateways, as shipped", AQUA)]
    fig, ax = plt.subplots(figsize=(9, 4.8))
    repeats = {}
    for name, label, color in series:
        grouped = capacity_rows(name)
        rates = sorted(grouped)
        repeats[name] = max(len(runs) for runs in grouped.values())
        for rate in rates:
            ax.scatter([rate] * len(grouped[rate]), [r["latency_ms"]["p95"] for r in grouped[rate]],
                       s=18, color=color, alpha=0.35, linewidths=0, zorder=2)
        medians = [med(grouped[rate], "p95") for rate in rates]
        ax.plot(rates, medians, color=color, linewidth=2, marker="o", markersize=6,
                markeredgecolor=SURFACE, markeredgewidth=1.5, label=label, zorder=3)
    ax.set_yscale("log")
    ax.set_yticks([1, 10, 100, 1000])
    ax.set_yticklabels(["1 ms", "10 ms", "100 ms", "1 s"])
    ax.set_xlim(300, 5200)
    ax.set_xlabel("Offered load (requests per second)")
    ax.set_ylabel("p95 latency (log scale)")
    runs_text = (f"{repeats['shipped-1gw']} runs" if len(set(repeats.values())) == 1
                 else "the runs")
    titled(ax, "Two gateways carry more load than one at the same latency",
           f"Line: median of {runs_text}. Dots: each run. Gateways limited to 2 CPUs each.")
    ax.xaxis.set_major_formatter(matplotlib.ticker.StrMethodFormatter("{x:,.0f}"))
    ax.legend(loc="upper left")
    ax.grid(axis="x", visible=False)
    fig.tight_layout()
    fig.savefig(CHARTS / "capacity-p95.svg")
    plt.close(fig)


def per_second(data, route):
    """One row per second, including seconds in which no response completed."""
    rows = {row["t"]: row for row in data["timeline"] if row["route"] == route}
    empty = {"count": 0, "status": {}, "p50_ms": 0, "p95_ms": 0, "max_ms": 0}
    return [{"t": t, **rows.get(t, empty)} for t in range(data["duration_s"] + 1)]


def mark_events(ax, data, labels=False):
    for key, text in (("killed_at_s", "killed"), ("restarted_at_s", "restarted")):
        ax.axvline(data[key], color=INK_2, linewidth=1, linestyle=(0, (4, 3)), zorder=1)
        if labels:
            ax.text(data[key] + 1, 1.0, text, transform=ax.get_xaxis_transform(),
                    fontsize=9.5, color=INK_2, va="top")


def chart_user_outage():
    data = load("outage-user-service")
    fig, (top, bottom) = plt.subplots(2, 1, figsize=(9, 5.8), sharex=True,
                                      gridspec_kw={"height_ratios": [1.2, 1]})
    users = per_second(data, "users")
    seconds = [row["t"] for row in users]
    ok = [row["status"].get("200", 0) for row in users]
    fallback = [row["status"].get("503", 0) for row in users]
    top.stackplot(seconds, ok, fallback, colors=[BLUE, ORANGE], labels=["200 from user-service",
                                                                        "503 from fallback"],
                  edgecolor=SURFACE, linewidth=0.5)
    top.set_title("Users route: the breaker turns a dead service into instant 503s, then recovers")
    top.set_ylabel("Responses per second")
    top.set_ylim(0, 520)
    top.legend(loc="upper right")
    top.grid(axis="x", visible=False)
    mark_events(top, data, labels=True)

    payments = per_second(data, "payments")
    bottom.plot([r["t"] for r in payments], [r["p95_ms"] for r in payments], color=AQUA, linewidth=2)
    bottom.set_title("Payments route: every request still served, with no sustained latency change")
    bottom.set_ylabel("p95 latency (ms)")
    bottom.set_xlabel("Seconds since the test started")
    bottom.set_ylim(bottom=0)
    bottom.set_xlim(0, data["duration_s"])
    bottom.grid(axis="x", visible=False)
    mark_events(bottom, data)
    fig.tight_layout()
    fig.savefig(CHARTS / "outage-user-service.svg")
    plt.close(fig)


def successes(name, window=5):
    data = load(name)
    totals = defaultdict(int)
    for row in data["timeline"]:
        if row["t"] <= data["duration_s"]:
            totals[row["t"]] += row["status"].get("200", 0)
    seconds = list(range(0, data["duration_s"] + 1))
    raw = [totals[s] for s in seconds]
    smooth = [statistics.mean(raw[max(0, i - window + 1):i + 1]) for i in range(len(raw))]
    return seconds, smooth, data


def chart_redis_outage():
    series = [("outage-redis", "After the fix: Redis killed", BLUE),
              ("outage-redis-heap-256m", "Before the fix: Redis killed", ORANGE),
              ("outage-redis-hang", "After the fix: Redis frozen", AQUA)]
    fig, ax = plt.subplots(figsize=(9, 4.4))
    data = None
    for name, label, color in series:
        seconds, smooth, data = successes(name)
        ax.plot(seconds, smooth, color=color, linewidth=2, label=label)
    mark_events(ax, data, labels=True)
    ax.set_xlim(0, data["duration_s"])
    ax.set_ylim(0, 400)
    ax.set_xlabel("Seconds since the test started")
    ax.set_ylabel("Successful responses per second")
    titled(ax, "The same 256 MB gateway now serves every request through a Redis outage",
           "250 req/s offered. 5-second moving average.")
    ax.annotate("After the fix: all requests succeed", (190, 250), xytext=(0, 8),
                textcoords="offset points", ha="center", fontsize=10, color=INK)
    ax.annotate("Before the fix: no successful responses", (180, 0), xytext=(0, 8),
                textcoords="offset points", ha="center", fontsize=10, color=INK)
    ax.legend(loc="center right", bbox_to_anchor=(1.0, 0.38))
    ax.grid(axis="x", visible=False)
    fig.tight_layout()
    fig.savefig(CHARTS / "outage-redis.svg")
    plt.close(fig)


def table_capacity():
    print("| Setup | Offered req/s | Achieved req/s | p50 ms | p95 ms | p99 ms | Worst p99 ms | "
          "Failed | Gateway CPU |")
    print("|---|--:|--:|--:|--:|--:|--:|--:|--:|")
    labels = {"shipped-1gw": "1 gateway, as shipped", "shipped-2gw": "2 gateways, as shipped",
              "tuned-1gw": "1 gateway, tuned"}
    for name, label in labels.items():
        grouped = capacity_rows(name)
        for rate in sorted(grouped):
            runs = grouped[rate]
            achieved = statistics.median(run["achieved_rps"] for run in runs)
            failed = statistics.mean(run["failed_rate"] for run in runs)
            print(f"| {label} | {rate:,} | {achieved:,.0f} | {med(runs, 'med'):.1f} | "
                  f"{med(runs, 'p95'):.1f} | {med(runs, 'p99'):.1f} | {worst(runs, 'p99'):,.0f} | "
                  f"{failed:.2%} | {gateway_cpu(runs):.0f}% |")


def table_soak():
    print("\n| Run | Requests | Failed | p50 ms | p95 ms | p99 ms | Max ms | GC pauses | "
          "Throttled | Peak heap sample |")
    print("|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|")
    for name, label in (("soak", "As shipped, run 1"), ("soak-shipped-run2", "As shipped, run 2"),
                        ("soak-tuned", "Tuned")):
        data = load(name)
        lat = data["latency_ms"]
        gc_seconds = sum(v["seconds"] for v in data["gc"].values())
        throttled = data.get("cpu_throttling")
        throttle = f"{throttled['throttled_usec'] / 1e6:.1f} s" if throttled else "not recorded"
        peak = max(sample["heap_mib"] for sample in data["heap"])
        print(f"| {label} | {data['requests']:,} | {data['failed_rate']:.2%} | {lat['med']:.1f} | "
              f"{lat['p95']:,.1f} | {lat['p99']:,.1f} | {lat['max']:,.0f} | {gc_seconds:.1f} s | "
              f"{throttle} | {peak:.0f} MiB |")


def table_overhead():
    data = load("overhead")
    print("\n| Path | p50 ms | p95 ms | p99 ms |")
    print("|---|--:|--:|--:|")
    for path in ("direct", "gateway"):
        runs = [run for run in data["runs"] if run["path"] == path]
        print(f"| {path} | {med(runs, 'med'):.2f} | {med(runs, 'p95'):.2f} | {med(runs, 'p99'):.2f} |")


def table_rate_limit():
    print("\n| Case | Allowed | Rejected | Expected allowed |")
    print("|---|--:|--:|--:|")
    for case in load("rate-limit")["cases"]:
        print(f"| {case['case']} | {case['allowed']:,} | {case['limited']:,} | "
              f"{case['expected_allowed']:,} |")


def table_cold_start():
    print("\n| Minute after start | p50 ms (range) | p95 ms (range) |")
    print("|--:|--:|--:|")
    grouped = defaultdict(list)
    for run in load("cold-start")["runs"]:
        grouped[run["minute"]].append(run["latency_ms"])
    for minute, rows in sorted(grouped.items()):
        p50 = sorted(row["med"] for row in rows)
        p95 = sorted(row["p95"] for row in rows)
        print(f"| {minute} | {p50[0]:.1f} to {p50[-1]:.1f} | {p95[0]:,.1f} to {p95[-1]:,.1f} |")


def table_overload():
    print("\n| Shedding | Offered req/s | Achieved req/s | p50 ms | p95 ms | p99 ms | Max ms | "
          "503s | Shed | Other | Dropped by k6 |")
    print("|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|")
    for name, label in (("overload-shedding-on", "On"), ("overload-shedding-off", "Off")):
        grouped = capacity_rows(name)
        for rate in sorted(grouped):
            runs = grouped[rate]
            def median_of(read):
                return statistics.median(read(run) for run in runs)
            print(f"| {label} | {rate:,} | {median_of(lambda r: r['achieved_rps']):,.0f} | "
                  f"{med(runs, 'med'):.1f} | {med(runs, 'p95'):,.1f} | {med(runs, 'p99'):,.1f} | "
                  f"{worst(runs, 'max'):,.0f} | {median_of(lambda r: r['responses']['503']):,.0f} | "
                  f"{median_of(lambda r: r.get('shed', 0)):,.0f} | "
                  f"{median_of(lambda r: r['responses']['other']):,.0f} | "
                  f"{median_of(lambda r: r['dropped_iterations']):,.0f} |")


def table_stalls():
    print("\n| Run | p95 ms | p99 ms | Max ms | Failed | Slow seconds | JVM pause | Control slow | "
          "Both | Neither | JVM pauses | Longest second | Scheduling max | Throttled |")
    print("|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|")
    for path in sorted(RESULTS.glob("stall-*.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        lat, why = data["latency_ms"], data["slow_second_evidence"]
        print(f"| {path.stem} | {lat['p95']:,.1f} | {lat['p99']:,.1f} | {lat['max']:,.0f} | "
              f"{data['failed_rate']:.2%} | {data['slow_seconds']} | {why['jvm_pause']} | "
              f"{why['control_also_slow']} | {why['both']} | {why['neither']} | "
              f"{data['jvm_pause_total_s']:.1f} s | {data['jvm_pause_longest_second_s']:.2f} s | "
              f"{data['scheduling_probe_max_ms']} ms | {data['cpu_throttled_s']} s |")


def main():
    CHARTS.mkdir(exist_ok=True)
    chart_capacity()
    chart_user_outage()
    chart_redis_outage()
    table_capacity()
    table_soak()
    table_overhead()
    table_rate_limit()
    table_cold_start()
    table_overload()
    table_stalls()


if __name__ == "__main__":
    main()
