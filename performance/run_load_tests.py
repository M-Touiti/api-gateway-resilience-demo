#!/usr/bin/env python3
"""Runs the load-test suite against the isolated stack in docker-compose.perf.yml.

Needs Docker and Python 3 only (k6 runs from its container image).

    python performance/run_load_tests.py all
    python performance/run_load_tests.py capacity --only shipped-1gw --repeats 3
    python performance/run_load_tests.py rate-limit
    python performance/run_load_tests.py cold-start
    python performance/run_load_tests.py overhead
    python performance/run_load_tests.py outage         # user-service, then all Redis cases
    python performance/run_load_tests.py outage-redis
    python performance/run_load_tests.py outage-hang    # Redis or user-service frozen, not killed
    python performance/run_load_tests.py redis-down-at-start
    python performance/run_load_tests.py redis-recovery      # limits return after a kill or freeze
    python performance/run_load_tests.py rate-limit-warmth   # flood on a cold and a warm JVM
    python performance/run_load_tests.py overload       # load shedding on and off
    python performance/run_load_tests.py soak --duration 600 --profile tuned --name soak-tuned
    python performance/run_load_tests.py stall-diagnosis
    python performance/run_load_tests.py down

Each test writes a compact JSON file to performance/results/. Bulky k6 CSV output
goes to performance/results/raw/, which git ignores.
"""
import argparse
import calendar
import csv
import ctypes
import json
import os
import re
import statistics
import subprocess
import sys
import threading
import time
from collections import defaultdict
from pathlib import Path

PERF_DIR = Path(__file__).resolve().parent
RESULTS = PERF_DIR / "results"
RAW = RESULTS / "raw"
PROJECT = "gateway-perf"
NETWORK = f"{PROJECT}_default"
K6_IMAGE = "grafana/k6:0.54.0"
K6_CONTAINER = f"{PROJECT}-k6"
COMPOSE = ["docker", "compose", "-f", str(PERF_DIR / "docker-compose.perf.yml"),
           "-p", PROJECT, "--profile", "scale"]
GW1 = "http://gateway-service:8080"
GW2 = "http://gateway-2:8080"
SLOW_MS = 200  # stall diagnosis: a second containing a request this slow counts as a slow second

# Tuning here is configuration only: no application code changes.
SHIPPED = {"GW_LOG_LEVEL": "INFO", "GW_JAVA_OPTS": "", "GW_MAX_CONCURRENT": "512"}
TUNED = {**SHIPPED, "GW_JAVA_OPTS": "-XX:+UseG1GC -XX:MaxRAMPercentage=75"}
CAPACITY_PROFILES = {
    "shipped-1gw": (SHIPPED, 1, GW1, [500, 1000, 1500, 2000, 2500]),
    "shipped-2gw": (SHIPPED, 2, f"{GW1},{GW2}", [1000, 2000, 3000, 4000, 5000]),
    "tuned-1gw": (TUNED, 1, GW1, [500, 1000, 1500, 2000, 2500]),
}
HOST_PAUSE_MS = 15_000
OVERLOAD_RATES = [2000, 3000, 4000, 5000]
OVERLOAD_PROFILES = {
    "overload-shedding-on": (SHIPPED, 1, GW1, OVERLOAD_RATES),
    "overload-shedding-off": ({**SHIPPED, "GW_MAX_CONCURRENT": "0"}, 1, GW1, OVERLOAD_RATES),
}


def run(cmd, check=True, capture=False):
    return subprocess.run(cmd, check=check, text=True, encoding="utf-8", errors="replace",
                          stdout=subprocess.PIPE if capture else None,
                          stderr=subprocess.STDOUT if capture else None)


def compose(*args, **kwargs):
    return run(COMPOSE + list(args), **kwargs)


def fresh_stack(gateways=1):
    """Recreates everything, so every test starts on cold JVMs, closed breakers and empty Redis."""
    compose("down", "-v", "--remove-orphans")
    services = ["redis", "user-service", "payment-service", "gateway-service"]
    if gateways == 2:
        services.append("gateway-2")
    compose("up", "-d", "--wait", *services)


def k6(script, name, env=None, csv_out=False):
    env = {"RESULT_NAME": name, **(env or {})}
    cmd = ["docker", "run", "--rm", "--name", K6_CONTAINER, "--network", NETWORK, "--cpus", "4",
           "--memory", "6g",
           "-v", f"{PERF_DIR / 'k6'}:/scripts:ro", "-v", f"{RESULTS}:/results"]
    for key, value in env.items():
        cmd += ["-e", f"{key}={value}"]
    cmd += [K6_IMAGE, "run", "--quiet", "--no-usage-report"]
    if csv_out:
        cmd += ["--out", f"csv=/results/raw/{name}.csv"]
    cmd.append(f"/scripts/{script}")
    run(cmd, check=False)  # k6 exits non-zero on threshold failures; the summary is still written.
    path = RESULTS / f"{name}.summary.json"
    summary = json.loads(path.read_text(encoding="utf-8"))
    path.unlink()
    return summary


class StatsSampler(threading.Thread):
    """Samples docker stats for the stack, to show which container hits its limit."""

    def __init__(self):
        super().__init__(daemon=True)
        self.samples = defaultdict(lambda: {"cpu": [], "mem_mib": []})
        self._halt = threading.Event()

    def run(self):
        while not self._halt.wait(1):
            out = run(["docker", "stats", "--no-stream", "--format", "{{json .}}"],
                      check=False, capture=True).stdout
            for line in out.splitlines():
                try:
                    row = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if PROJECT not in row["Name"]:
                    continue
                name = row["Name"].removeprefix(f"{PROJECT}-").removesuffix("-1")
                self.samples[name]["cpu"].append(float(row["CPUPerc"].rstrip("%")))
                used = row["MemUsage"].split("/")[0].strip()
                self.samples[name]["mem_mib"].append(to_mib(used))

    def stop(self):
        self._halt.set()
        self.join()
        # The first samples can catch the ramp-up, so they are dropped from the averages.
        return {name: {"cpu_avg_pct": round(statistics.mean(s["cpu"][2:] or s["cpu"]), 1),
                       "cpu_max_pct": round(max(s["cpu"]), 1),
                       "mem_max_mib": round(max(s["mem_mib"]))}
                for name, s in self.samples.items() if s["cpu"]}


def gateway_metric(path, service="gateway-service"):
    out = run(["docker", "exec", f"{PROJECT}-{service}-1", "wget", "-T", "4", "-qO-",
               f"http://localhost:8080/actuator/{path}"], check=False, capture=True).stdout
    return out


def shed_total(gateways):
    """Requests shed so far by all gateways. The counter doesn't exist until the first one."""
    total = 0.0
    for service in ("gateway-service", "gateway-2")[:gateways]:
        for line in gateway_metric("prometheus", service).splitlines():
            if line.startswith("gateway_requests_shed_total"):
                total += float(line.rsplit(" ", 1)[1])
    return int(total)


class HeapPoller(threading.Thread):
    """Records used heap over time. Gaps in the series mean the gateway did not answer."""

    def __init__(self, interval=5):
        super().__init__(daemon=True)
        self.interval = interval
        self.samples = []
        self._halt = threading.Event()
        self._start_time = time.time()

    def run(self):
        while not self._halt.wait(self.interval):
            # wget prints no body on a 503, so these are false when the endpoint reports DOWN.
            healthy = '"status":"UP"' in gateway_metric("health")
            ready = '"status":"UP"' in gateway_metric("health/readiness")
            try:
                body = json.loads(gateway_metric("metrics/jvm.memory.used?tag=area:heap"))
                used = body["measurements"][0]["value"]
            except (json.JSONDecodeError, KeyError, IndexError):
                continue
            self.samples.append({"t": round(time.time() - self._start_time),
                                 "heap_mib": round(used / 1048576, 1), "healthy": healthy,
                                 "ready": ready})

    def stop(self):
        self._halt.set()
        self.join()
        return self.samples


def gc_totals():
    """Sums GC pauses by collector action from the Prometheus endpoint."""
    totals = defaultdict(lambda: {"count": 0, "seconds": 0.0})
    for line in gateway_metric("prometheus").splitlines():
        if line.startswith("jvm_gc_pause_seconds_"):
            key, value = line.rsplit(" ", 1)
            action = key.split('action="')[1].split('"')[0]
            if "_count{" in key:
                totals[action]["count"] += int(float(value))
            elif "_sum{" in key:
                totals[action]["seconds"] = round(totals[action]["seconds"] + float(value), 3)
    return dict(totals)


def cpu_throttling():
    """Reads CFS throttling counters, which show when the CPU limit is pausing the JVM."""
    out = run(["docker", "exec", f"{PROJECT}-gateway-service-1", "cat", "/sys/fs/cgroup/cpu.stat"],
              check=False, capture=True).stdout
    return {key: int(value) for key, value in
            (line.split() for line in out.splitlines() if len(line.split()) == 2)
            if key in ("nr_periods", "nr_throttled", "throttled_usec")}


def to_mib(text):
    for unit, factor in (("GiB", 1024), ("MiB", 1), ("KiB", 1 / 1024), ("B", 1 / 1048576)):
        if text.endswith(unit):
            return float(text[:-len(unit)]) * factor
    return 0.0


def metric(summary, name):
    return summary["metrics"].get(name, {}).get("values", {})


def digest(summary):
    latency = metric(summary, "http_req_duration")
    return {
        "requests": metric(summary, "http_reqs").get("count", 0),
        "achieved_rps": round(metric(summary, "http_reqs").get("rate", 0), 1),
        "failed_rate": round(metric(summary, "http_req_failed").get("rate", 0), 5),
        "dropped_iterations": metric(summary, "dropped_iterations").get("count", 0),
        "max_vus": metric(summary, "vus_max").get("value", 0),
        "latency_ms": {k.replace("(", "").replace(")", ""): round(v, 2)
                       for k, v in latency.items()},
        "success_latency_ms": {k.replace("(", "").replace(")", ""): round(v, 2) for k, v in
                               metric(summary, "http_req_duration{expected_response:true}").items()},
        "responses": {name: metric(summary, f"responses_{name}").get("count", 0)
                      for name in ("200", "503", "other")},
    }


def save(name, payload):
    payload = {"generated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"), **payload}
    path = RESULTS / f"{name}.json"
    path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(f"--> wrote {path}", flush=True)


def warm_up(base_urls, rate=300, duration="90s"):
    """A cold JVM is far slower than a warm one, so warm-up traffic is never measured."""
    rate *= len(base_urls.split(","))
    k6("capacity.js", "warmup", {"BASE_URLS": base_urls, "RATE": rate, "DURATION": duration})


def capacity_sweep(label, settings, gateways, base_urls, steps, repeats, duration):
    os.environ.update(settings)
    runs = []
    for repeat in range(1, repeats + 1):
        fresh_stack(gateways)
        warm_up(base_urls)
        for rate in steps:
            time.sleep(5)  # let the previous step drain
            sampler = StatsSampler()
            sampler.start()
            shed_before = shed_total(gateways)
            summary = k6("capacity.js", f"{label}-{rate}",
                         {"BASE_URLS": base_urls, "RATE": rate, "DURATION": f"{duration}s"})
            # 503s beyond this count came from a breaker fallback rather than load shedding.
            row = {"repeat": repeat, "target_rps": rate, **digest(summary),
                   "shed": shed_total(gateways) - shed_before, "containers": sampler.stop()}
            # k6 gives up on a request after 10 s, so anything far longer means the host was paused.
            if row["latency_ms"]["max"] > HOST_PAUSE_MS:
                row["host_paused"] = True
                print(f"WARNING: step {rate} is invalid, the host was paused", flush=True)
            print(json.dumps({k: row[k] for k in ("repeat", "target_rps", "achieved_rps",
                                                  "failed_rate", "dropped_iterations")}
                             | row["latency_ms"]), flush=True)
            runs.append(row)
            # Saved after every step, so an interrupted sweep keeps what it measured.
            save(f"capacity-{label}", {"label": label, "gateways": gateways, "settings": settings,
                                       "base_urls": base_urls, "step_duration_s": duration,
                                       "repeats_planned": repeats, "runs": runs})
    os.environ.update(SHIPPED)


def cmd_capacity(args):
    for label in args.only.split(","):
        settings, gateways, base_urls, steps = CAPACITY_PROFILES[label]
        capacity_sweep(label, settings, gateways, base_urls, steps, args.repeats, args.duration)


def cmd_overload(args):
    for label, (settings, gateways, base_urls, steps) in OVERLOAD_PROFILES.items():
        capacity_sweep(label, settings, gateways, base_urls, steps, args.repeats, args.duration)


def cmd_cold_start(args):
    """Measures consecutive minutes at a fixed rate, starting from a freshly started JVM."""
    runs = []
    for repeat in range(1, args.repeats + 1):
        fresh_stack(1)
        for minute in range(1, 5):
            summary = k6("capacity.js", "cold-start",
                         {"BASE_URLS": GW1, "RATE": 300, "DURATION": "60s"})
            runs.append({"repeat": repeat, "minute": minute, "target_rps": 300, **digest(summary)})
            print(json.dumps({"repeat": repeat, "minute": minute, **runs[-1]["latency_ms"]}),
                  flush=True)
    save("cold-start", {"runs": runs})


def cmd_overhead(args):
    """Same rate, same container limits: direct to user-service, then through the gateway."""
    rate = args.rate or 500
    fresh_stack(1)
    warm_up(GW1)
    runs = []
    for repeat in range(1, args.repeats + 1):
        for label, base in (("direct", "http://user-service:8081"), ("gateway", GW1)):
            time.sleep(5)
            summary = k6("capacity.js", f"overhead-{label}",
                         {"BASE_URLS": base, "RATE": rate, "DURATION": f"{args.duration}s"})
            row = {"repeat": repeat, "path": label, "target_rps": rate, **digest(summary)}
            print(json.dumps({"path": label, **row["latency_ms"]}), flush=True)
            runs.append(row)
    save("overhead", {"step_duration_s": args.duration, "runs": runs})


def cmd_rate_limit(args):
    rate = args.rate or 200
    cases = [
        ("users-1gw", 1, GW1, {"MODE": "user", "ROUTE": "users"}, 100, 50),
        ("users-2gw", 2, f"{GW1},{GW2}", {"MODE": "user", "ROUTE": "users"}, 100, 50),
        ("payments-1gw", 1, GW1, {"MODE": "user", "ROUTE": "payments"}, 20, 10),
        ("auth-by-ip", 1, GW1, {"MODE": "ip"}, 40, 20),
        ("auth-spoofed-xff", 1, GW1, {"MODE": "ip-spoofed-xff"}, 40, 20),
    ]
    rows = []
    for name, gateways, base_urls, env, burst, replenish in cases:
        fresh_stack(gateways)
        summary = k6("rate-limit.js", f"rl-{name}", {
            "BASE_URLS": base_urls, "RATE": rate, "DURATION": f"{args.duration}s", **env})
        rows.append({
            "case": name, "gateways": gateways, "offered_rps": rate,
            "duration_s": args.duration,
            "allowed": metric(summary, "requests_allowed").get("count", 0),
            "limited": metric(summary, "requests_limited").get("count", 0),
            "other": metric(summary, "requests_other").get("count", 0),
            "expected_allowed": burst + replenish * args.duration,
            "latency_allowed_ms": metric(summary, "latency_allowed"),
            "latency_limited_ms": metric(summary, "latency_limited"),
        })
        print(json.dumps({k: rows[-1][k] for k in
                          ("case", "allowed", "limited", "other", "expected_allowed")}), flush=True)
    save("rate-limit", {"cases": rows})


def flood_counts(name, duration, route="users"):
    """One user far above their limit, returning what was admitted and what was rejected."""
    summary = k6("rate-limit.js", name, {"BASE_URLS": GW1, "RATE": 200, "DURATION": f"{duration}s",
                                         "MODE": "user", "ROUTE": route})
    return {key: metric(summary, f"requests_{key}").get("count", 0)
            for key in ("allowed", "limited", "other")}


def cmd_rate_limit_warmth(_args):
    """Does a cold JVM admit too much? A slow first Redis call hits the timeout and fails open."""
    duration = 20
    rows = []
    for repeat in range(1, 4):
        for state in ("cold", "warm"):
            fresh_stack(1)
            if state == "warm":
                warm_up(GW1)  # uses other users, so it leaves this flood's bucket full
            rows.append({"repeat": repeat, "state": state, "expected_allowed": 20 + 10 * duration,
                         **flood_counts("rl-warmth", duration, route="payments")})
            print(json.dumps(rows[-1]), flush=True)
    save("rate-limit-cold-warm", {"route": "payments", "duration_s": duration, "runs": rows})


def cmd_redis_recovery(_args):
    """Floods one user through and after a Redis failure, to show limits come back."""
    duration = 30
    rows = []
    for fail, recover in (("kill", "start"), ("pause", "unpause")):
        fresh_stack(1)
        warm_up(GW1)
        compose(fail, "redis")
        during = flood_counts("redis-recovery-during", 20)
        compose(recover, "redis")
        time.sleep(10)
        rows.append({"failure": fail, "during_20s": during, "after": flood_counts(
            "redis-recovery-after", duration), "expected_allowed_after": 100 + 50 * duration})
        print(json.dumps(rows[-1]), flush=True)
    save("redis-recovery", {"runs": rows})


def timeline(csv_path, start_epoch, states=("cb_user_service", "cb_payment_service")):
    """Reduces k6 CSV output to per-second rows per route."""
    buckets = defaultdict(lambda: {"count": 0, "failed": 0, "lat": [], "status": defaultdict(int)})
    breaker = defaultdict(dict)
    with open(csv_path, newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            name = row["metric_name"]
            second = int(row["timestamp"]) - start_epoch
            if name in states:
                breaker[second][name] = float(row["metric_value"])
            elif name == "http_req_duration":
                route = dict(pair.split("=", 1) for pair in row["extra_tags"].split("&")
                             if "=" in pair).get("route", "")
                if route in ("users", "payments"):
                    bucket = buckets[(second, route)]
                    bucket["count"] += 1
                    bucket["lat"].append(float(row["metric_value"]))
                    status = row["status"] or "0"
                    bucket["status"][status] += 1
    rows = []
    for (second, route), bucket in sorted(buckets.items()):
        lat = sorted(bucket["lat"])
        rows.append({"t": second, "route": route, "count": bucket["count"],
                     "status": dict(bucket["status"]),
                     "p50_ms": round(lat[len(lat) // 2], 2),
                     "p95_ms": round(lat[int(len(lat) * 0.95)], 2),
                     "max_ms": round(lat[-1], 2)})
    states_rows = [{"t": t, **values} for t, values in sorted(breaker.items())]
    return rows, states_rows


def outage(name, victim, kill_at, restart_at, total, users_rate, payments_rate, settings=None,
           failure=("kill", "start")):
    os.environ.update(settings or {})
    fresh_stack(1)
    warm_up(GW1)
    time.sleep(5)
    events = {}

    def chaos():
        time.sleep(kill_at)
        events["killed_wall"] = time.time()
        compose(failure[0], victim)
        time.sleep(restart_at - kill_at)
        events["restart_wall"] = time.time()
        compose(failure[1], victim)

    sampler = StatsSampler()
    sampler.start()
    heap = HeapPoller()
    heap.start()
    thread = threading.Thread(target=chaos, daemon=True)
    thread.start()
    summary = k6("outage.js", name, {
        "BASE_URLS": GW1, "DURATION": f"{total}s",
        "USERS_RATE": users_rate, "PAYMENTS_RATE": payments_rate}, csv_out=True)
    thread.join()
    containers = sampler.stop()
    heap_samples = heap.stop()
    responsive = '"status"' in gateway_metric("health/readiness")  # wget errors are captured too
    gc = gc_totals() if responsive else None
    for key in settings or {}:
        os.environ.pop(key, None)
    os.environ.update(SHIPPED)

    csv_path = RAW / f"{name}.csv"
    with open(csv_path, newline="", encoding="utf-8") as handle:
        first = next(row for row in csv.DictReader(handle) if row["metric_name"] == "http_reqs"
                     and row["scenario"] != "")
    start = int(first["timestamp"])
    rows, breaker_rows = timeline(csv_path, start)
    csv_path.unlink()  # these files reach hundreds of MB, and the timeline keeps what the report uses
    save(name, {
        "victim": victim, "failure": failure[0], "settings": settings or {},
        "gateway_responsive_at_end": responsive, "gc": gc, "heap": heap_samples,
        "users_rate": users_rate, "payments_rate": payments_rate,
        "duration_s": total,
        "killed_at_s": round(events["killed_wall"] - start, 1),
        "restarted_at_s": round(events["restart_wall"] - start, 1),
        "overall": digest(summary), "containers": containers,
        "timeline": rows, "breaker_states": breaker_rows,
    })


def cmd_outage(args):
    outage("outage-user-service", "user-service", 60, 120, 180, 200, 50)
    cmd_outage_redis(args)


def cmd_outage_redis(_args):
    """Redis down for 60s at 250 req/s, then 120s to see whether the gateway recovers."""
    outage("outage-redis", "redis", 60, 120, 240, 200, 50)


def cmd_outage_hang(_args):
    """Frozen rather than dead: connections stay open and nothing answers, the harder failure."""
    outage("outage-redis-hang", "redis", 60, 120, 240, 200, 50, failure=("pause", "unpause"))
    outage("outage-user-service-hang", "user-service", 60, 120, 180, 200, 50,
           failure=("pause", "unpause"))


def cmd_redis_down_at_start(args):
    """The gateway starts without Redis, serves traffic, then picks Redis up when it appears."""
    compose("down", "-v", "--remove-orphans")
    compose("up", "-d", "--wait", "user-service", "payment-service")
    compose("up", "-d", "--no-deps", "gateway-service")
    healthy_after = None
    start = time.time()
    while time.time() - start < 120:
        if '"status":"UP"' in gateway_metric("health/readiness"):
            healthy_after = round(time.time() - start)
            break
        time.sleep(2)
    without = k6("capacity.js", "redis-down-at-start",
                 {"BASE_URLS": GW1, "RATE": 200, "DURATION": f"{args.duration}s"})
    compose("up", "-d", "--wait", "redis")
    time.sleep(10)
    # Well above one user's limit, so 429s prove that limiting came back.
    limited = k6("rate-limit.js", "redis-down-at-start-limited",
                 {"BASE_URLS": GW1, "RATE": 200, "DURATION": "30s", "MODE": "user", "ROUTE": "users"})
    save("redis-down-at-start", {
        "healthy_after_s": healthy_after,
        "without_redis": digest(without),
        "after_redis_started": {
            "allowed": metric(limited, "requests_allowed").get("count", 0),
            "limited": metric(limited, "requests_limited").get("count", 0),
            "other": metric(limited, "requests_other").get("count", 0)},
    })


def cmd_soak(args):
    rate = args.rate or 500
    os.environ.update(TUNED if args.profile == "tuned" else SHIPPED)
    fresh_stack(1)
    warm_up(GW1)
    throttle_before = cpu_throttling()
    heap = HeapPoller(interval=15)
    heap.start()
    sampler = StatsSampler()
    sampler.start()
    summary = k6("capacity.js", "soak", {
        "BASE_URLS": GW1, "RATE": rate, "DURATION": f"{args.duration}s"})
    containers = sampler.stop()
    throttle_after = cpu_throttling()
    throttling = {key: throttle_after[key] - throttle_before[key] for key in throttle_after}
    save(args.name or "soak", {
        "profile": args.profile, "settings": dict(TUNED if args.profile == "tuned" else SHIPPED),
        "target_rps": rate, "duration_s": args.duration, **digest(summary),
        "containers": containers, "heap": heap.stop(), "gc": gc_totals(),
        "cpu_throttling": throttling})
    os.environ.update(SHIPPED)


class SchedulingProbe(threading.Thread):
    """Runs redis-cli --intrinsic-latency, which spins and reports the longest time it was not
    scheduled. It uses no network and shares nothing with the gateway except the host."""

    def __init__(self, seconds):
        super().__init__(daemon=True)
        self.seconds = seconds
        self.output = ""

    def run(self):
        self.output = run(["docker", "run", "--rm", "redis:7-alpine", "redis-cli",
                           "--intrinsic-latency", str(self.seconds)],
                          check=False, capture=True).stdout

    def max_ms(self):
        values = re.findall(r"Max latency so far: (\d+) microseconds", self.output)
        return round(int(values[-1]) / 1000, 1) if values else None


def read_jvm_pauses():
    """Parses the gateway's safepoint log into (epoch seconds at end, pause seconds) pairs."""
    out = run(["docker", "exec", f"{PROJECT}-gateway-service-1", "cat", "/tmp/safepoint.log"],
              check=False, capture=True).stdout
    pauses = []
    for match in re.finditer(r"\[(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)[^\]]*\].*Total: (\d+) ns", out):
        stamp = calendar.timegm(time.strptime(match[1], "%Y-%m-%dT%H:%M:%S"))  # containers run in UTC
        pauses.append((int(stamp), int(match[2]) / 1e9))
    return pauses


def per_second_latency(csv_path):
    gateway, control = defaultdict(float), defaultdict(float)
    with open(csv_path, newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            if row["metric_name"] != "http_req_duration":
                continue
            target = control if "route=control" in row["extra_tags"] else gateway
            second = int(row["timestamp"])
            target[second] = max(target[second], float(row["metric_value"]))
    return gateway, control


def diagnose(name, sample, rate, duration, opts):
    """One soak with stall evidence recorded, classifying every second that had a slow request."""
    os.environ.update({**SHIPPED, "GW_JAVA_OPTS": opts})
    fresh_stack(1)
    warm_up(GW1)
    samplers = (StatsSampler(), HeapPoller(interval=15)) if sample else ()
    for sampler in samplers:
        sampler.start()
    probe = SchedulingProbe(duration)
    probe.start()
    throttle_before = cpu_throttling()
    summary = k6("capacity.js", name, {
        "BASE_URLS": GW1, "RATE": rate, "DURATION": f"{duration}s",
        "CONTROL_URL": "http://user-service:8081"}, csv_out=True)
    throttle_after = cpu_throttling()
    probe.join()
    for sampler in samplers:
        sampler.stop()
    pauses = defaultdict(float)
    for second, seconds in read_jvm_pauses():
        pauses[second] += seconds
    gateway, control = per_second_latency(RAW / f"{name}.csv")
    (RAW / f"{name}.csv").unlink()

    # A request is recorded when it finishes, so evidence is looked for just before a slow second.
    stalls = {"jvm_pause": 0, "control_also_slow": 0, "both": 0, "neither": 0}
    slow = [second for second, value in gateway.items() if value >= SLOW_MS]
    for second in slow:
        window = range(second - 2, second + 1)
        jvm = sum(pauses[t] for t in window) >= 0.05
        also = max(control[t] for t in window) >= SLOW_MS
        stalls["both" if jvm and also else "jvm_pause" if jvm
               else "control_also_slow" if also else "neither"] += 1
    save(name, {
        "samplers_running": sample, "target_rps": rate, "duration_s": duration, "java_opts": opts,
        **digest(summary), "control_latency_ms": metric(summary, "control_latency"),
        "slow_seconds": len(slow), "slow_threshold_ms": SLOW_MS, "slow_second_evidence": stalls,
        "jvm_pause_total_s": round(sum(pauses.values()), 2),
        "jvm_pause_longest_second_s": round(max(pauses.values(), default=0), 3),
        "scheduling_probe_max_ms": probe.max_ms(),
        "cpu_throttled_s": round((throttle_after["throttled_usec"]
                                  - throttle_before["throttled_usec"]) / 1e6, 1),
    })
    os.environ.update(SHIPPED)


def cmd_stall_diagnosis(args):
    """Soaks with and without the harness's own docker stats and docker exec sampling."""
    rate = args.rate or 500
    logging_opts = "-Xlog:safepoint:file=/tmp/safepoint.log:time"
    for repeat in range(1, args.repeats + 1):
        for label, sample in (("quiet", False), ("sampled", True)):
            diagnose(f"stall-{label}-{repeat}", sample, rate, args.duration, logging_opts)


def cmd_down(_args):
    compose("down", "-v", "--remove-orphans")


def cmd_all(args):
    cmd_capacity(args)
    cmd_cold_start(args)
    cmd_overhead(args)
    cmd_rate_limit(args)
    cmd_rate_limit_warmth(args)
    cmd_outage(args)
    cmd_outage_hang(args)
    cmd_redis_down_at_start(args)
    cmd_redis_recovery(args)
    cmd_overload(args)
    args.duration = 600
    cmd_soak(args)
    cmd_stall_diagnosis(args)
    cmd_down(args)


def keep_host_awake():
    """Stops Windows sleeping on idle during a run. Closing a laptop's lid can still suspend it.

    The display is kept on too, because Modern Standby can begin when the screen times out.
    """
    if sys.platform == "win32":
        es_continuous, es_system_required, es_display_required = 0x80000000, 0x00000001, 0x00000002
        ctypes.windll.kernel32.SetThreadExecutionState(
            es_continuous | es_system_required | es_display_required)


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("command", choices=["all", "capacity", "cold-start", "overhead", "rate-limit",
                                            "outage", "outage-redis", "outage-hang", "overload",
                                            "redis-down-at-start", "redis-recovery",
                                            "rate-limit-warmth", "soak", "stall-diagnosis",
                                            "down"])
    parser.add_argument("--only", default=",".join(CAPACITY_PROFILES),
                        help="capacity profiles to run")
    parser.add_argument("--profile", choices=["shipped", "tuned"], default="shipped",
                        help="soak configuration")
    parser.add_argument("--name", default=None, help="soak result file name")
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--duration", type=int, default=60, help="seconds per run")
    parser.add_argument("--rate", type=int, default=None,
                        help="offered req/s (defaults: rate-limit 200, overhead and soak 500)")
    args = parser.parse_args()
    RAW.mkdir(parents=True, exist_ok=True)
    os.environ.update(SHIPPED)
    if args.command != "down":
        keep_host_awake()
        compose("build", "--quiet")  # always test the current code
    {"all": cmd_all, "capacity": cmd_capacity, "overhead": cmd_overhead,
     "rate-limit": cmd_rate_limit, "outage": cmd_outage, "outage-redis": cmd_outage_redis,
     "outage-hang": cmd_outage_hang, "overload": cmd_overload,
     "redis-down-at-start": cmd_redis_down_at_start, "redis-recovery": cmd_redis_recovery,
     "rate-limit-warmth": cmd_rate_limit_warmth, "soak": cmd_soak,
     "stall-diagnosis": cmd_stall_diagnosis, "cold-start": cmd_cold_start,
     "down": cmd_down}[args.command](args)


if __name__ == "__main__":
    main()
