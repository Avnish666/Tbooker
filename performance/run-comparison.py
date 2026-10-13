#!/usr/bin/env python3
"""Linux/Docker benchmark against private disposable databases; never uses .env.

Requires a built application JAR, Java 21, Python 3, and Docker. Executes disabled,
enabled, enabled, disabled (ABBA), restarting/warming the same JAR for each run.
Temporary credentials, application logs, and raw k6 data are deleted on exit.
Only aggregate non-secret results are written to the explicitly selected output.
"""
import argparse
import base64
import datetime
import hashlib
import json
import os
from pathlib import Path
import platform
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
PG_IMAGE = "postgres:17.11-bookworm"
REDIS_IMAGE = "redis:7.4-alpine"
K6_IMAGE = "grafana/k6:1.6.0"


def run(*args, **kwargs):
    return subprocess.run(args, check=True, text=True, capture_output=True, **kwargs).stdout.strip()


def wait_for(check, description, seconds=60):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            if check():
                return
        except (OSError, subprocess.CalledProcessError):
            pass
        time.sleep(0.25)
    raise RuntimeError(f"Timed out waiting for {description}")


def mapped_port(container, port):
    config = json.loads(run("docker", "inspect", container))[0]
    return int(config["NetworkSettings"]["Ports"][f"{port}/tcp"][0]["HostPort"])


def redis_stats(container):
    info = run("docker", "exec", container, "redis-cli", "INFO", "stats")
    return {k: int(v) for line in info.splitlines() if ":" in line
            for k, v in [line.split(":", 1)] if k in ("keyspace_hits", "keyspace_misses")}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/Tbooker-0.0.1-SNAPSHOT.jar")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--vus", type=int, default=10)
    parser.add_argument("--duration", default="30s")
    parser.add_argument("--warmup", default="10s")
    args = parser.parse_args()
    if platform.system() != "Linux":
        parser.error("Runner uses Docker host networking on Linux; catalogue.js also runs with standalone k6 elsewhere.")
    if not args.jar.is_file() or args.vus < 1:
        parser.error("Build the JAR first and choose a positive VU count.")
    if args.output.exists():
        parser.error("Output exists; choose a new path to preserve previous measurements.")
    started = datetime.datetime.now(datetime.timezone.utc).isoformat()
    containers = []
    app = None
    try:
        # Docker receives the password from the subprocess environment, not a command-line value.
        container_env = dict(os.environ, POSTGRES_PASSWORD=secrets.token_urlsafe(32))
        pg = run("docker", "run", "--rm", "-d", "-p", "127.0.0.1::5432", "-e", "POSTGRES_PASSWORD",
                 "-e", "POSTGRES_DB=tbooker_bench", PG_IMAGE, "-c", "shared_preload_libraries=pg_stat_statements", env=container_env)
        containers.append(pg)
        redis = run("docker", "run", "--rm", "-d", "-p", "127.0.0.1::6379", REDIS_IMAGE,
                    "redis-server", "--save", "", "--appendonly", "no", "--maxmemory", "128mb", "--maxmemory-policy", "allkeys-lru")
        containers.append(redis)

        def sql(query):
            return run("docker", "exec", "-i", pg, "psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1",
                       "-U", "postgres", "-d", "tbooker_bench", input=query)

        wait_for(lambda: sql("SELECT 1;") == "1", "PostgreSQL")
        wait_for(lambda: run("docker", "exec", redis, "redis-cli", "PING") == "PONG", "Redis")
        sql("CREATE EXTENSION pg_stat_statements;")
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            app_port = listener.getsockname()[1]
        base_url = f"http://127.0.0.1:{app_port}"
        # Explicit overrides prevent accidental use of the developer's application database.
        env = {k: v for k, v in os.environ.items() if not k.startswith(("SPRING_", "DB_", "REDIS_", "CACHE_", "JWT_", "CATALOGUE_", "SERVER_"))}
        env.update({"SPRING_DATASOURCE_URL": f"jdbc:postgresql://127.0.0.1:{mapped_port(pg, 5432)}/tbooker_bench",
                    "SPRING_DATASOURCE_USERNAME": "postgres", "SPRING_DATASOURCE_PASSWORD": container_env["POSTGRES_PASSWORD"],
                    "SPRING_PROFILES_ACTIVE": "dev", "MESSAGING_ENABLED": "false", "JWT_SECRET": base64.b64encode(secrets.token_bytes(32)).decode(),
                    "SERVER_PORT": str(app_port), "REDIS_HOST": "127.0.0.1", "REDIS_PORT": str(mapped_port(redis, 6379))})
        records = []
        with tempfile.TemporaryDirectory(prefix="tbooker-benchmark-") as tmp:
            work = Path(tmp)

            def k6(label, duration):
                output_dir = work / label
                output_dir.mkdir()
                # k6 writes only to this newly created disposable directory.
                completed = subprocess.run(["docker", "run", "--rm", "--network", "host", "--user", f"{os.getuid()}:{os.getgid()}",
                    "-v", f"{ROOT / 'performance/catalogue.js'}:/scripts/catalogue.js:ro",
                    "-v", f"{output_dir}:/results", "-e", f"BASE_URL={base_url}", "-e", f"VUS={args.vus}",
                    "-e", f"DURATION={duration}", "-e", "SUMMARY_PATH=/results/summary.json", K6_IMAGE,
                    "run", "--quiet", "/scripts/catalogue.js"], text=True, capture_output=True)
                if completed.returncode:
                    raise RuntimeError(f"k6 {label} failed (exit {completed.returncode}): {completed.stderr[-1500:]}")
                return json.loads((output_dir / "summary.json").read_text())

            for index, enabled in enumerate((False, True, True, False), start=1):
                env["CATALOGUE_CACHE_ENABLED"] = str(enabled).lower()
                run("docker", "exec", redis, "redis-cli", "FLUSHDB")
                with (work / f"app-{index}.log").open("w") as log:
                    app = subprocess.Popen(["java", "-Xms256m", "-Xmx512m", "-XX:ActiveProcessorCount=4", "-jar", str(args.jar.resolve()),
                        "--reservations.cleanup.enabled=false"], env=env, stdout=log, stderr=subprocess.STDOUT)

                    def ready():
                        if app.poll() is not None:
                            raise RuntimeError("Application exited before readiness; verify the JAR and Java 21 environment.")
                        with urllib.request.urlopen(base_url + "/actuator/health/readiness", timeout=2) as response:
                            return response.status == 200

                    wait_for(ready, "application readiness")
                    # Same 100-show dataset for all runs; no availability/booking benchmark claims.
                    sql("""INSERT INTO tbooker.movie_shows(movie_id,screen_id,start_time)
                        SELECT movie_id, screen_id, start_time + n * interval '3 hours'
                        FROM (SELECT * FROM tbooker.movie_shows ORDER BY id LIMIT 1) sample
                        CROSS JOIN generate_series(1,99) n ON CONFLICT(screen_id,start_time) DO NOTHING;""")
                    print(f"Run {index}/4: caching={enabled}, warmup={args.warmup}, measured={args.duration}", flush=True)
                    k6(f"warmup-{index}", args.warmup)
                    sql("SELECT pg_stat_statements_reset();")
                    before = redis_stats(redis)
                    result = k6(f"measured-{index}", args.duration)
                    after = redis_stats(redis)
                    queries = int(sql("""SELECT COALESCE(SUM(calls),0)::bigint FROM pg_stat_statements
                        WHERE query ILIKE 'select %' AND query ILIKE '%movie_shows%'
                        AND query NOT ILIKE '%pg_stat_statements%';"""))
                    hits = after["keyspace_hits"] - before["keyspace_hits"]
                    misses = after["keyspace_misses"] - before["keyspace_misses"]
                    metrics = result["metrics"]
                    record = {"run": index, "cache_enabled": enabled,
                        "requests": metrics["http_reqs"]["values"]["count"],
                        "requests_per_second": metrics["http_reqs"]["values"]["rate"],
                        "average_ms": metrics["http_req_duration"]["values"]["avg"],
                        "p95_ms": metrics["http_req_duration"]["values"]["p(95)"],
                        "http_error_rate": metrics["http_req_failed"]["values"]["rate"],
                        "check_success_rate": metrics["checks"]["values"]["rate"],
                        "redis_hits": hits, "redis_misses": misses,
                        "redis_hit_ratio": hits / (hits + misses) if hits + misses else None,
                        "catalogue_select_calls": queries}
                    records.append(record)
                    print(json.dumps(record), flush=True)
                    app.terminate()
                    app.wait(timeout=30)
                    app = None
        report = {"started_at_utc": started, "base_commit": run("git", "rev-parse", "HEAD", cwd=ROOT),
            "jar_sha256": hashlib.sha256(args.jar.read_bytes()).hexdigest(),
            "working_tree_changes_included": bool(run("git", "status", "--porcelain", cwd=ROOT)),
            "environment": {"os": platform.platform(), "logical_cpus_visible": os.cpu_count(),
                "java": subprocess.run(["java", "-version"], text=True, capture_output=True, check=True).stderr.strip(),
                "jvm_options": "-Xms256m -Xmx512m -XX:ActiveProcessorCount=4",
                "images": {img: json.loads(run("docker", "image", "inspect", img))[0]["RepoDigests"]
                           for img in (PG_IMAGE, REDIS_IMAGE, K6_IMAGE)}},
            "workload": {"method": "GET", "path": "/api/shows", "show_count": 100, "vus": args.vus,
                "duration": args.duration, "warmup": args.warmup, "order": "disabled-enabled-enabled-disabled",
                "show_list_ttl_seconds": 30, "model": "closed, no think time", "restarts_between_runs": True},
            "runs": records}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + "\n")
        print(f"Aggregate report saved to {args.output}", flush=True)
    finally:
        if app is not None:
            app.terminate()
            try:
                app.wait(timeout=30)
            except subprocess.TimeoutExpired:
                app.kill()
                app.wait(timeout=10)
        for container in reversed(containers):
            subprocess.run(["docker", "rm", "-f", container], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if __name__ == "__main__":
    main()
