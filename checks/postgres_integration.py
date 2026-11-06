"""Check the installed CLI against plans produced by a real PostgreSQL server.

Install: python -m pip install 'psycopg[binary]==3.3.7'
Run: python checks/postgres_integration.py --install-dir build/install/planscope

Connection settings come from libpq's PGHOST, PGPORT, PGUSER, PGPASSWORD and
PGDATABASE environment variables. Use an isolated CI database (PostgreSQL 18).
This test executes only fixed statements against its own session-local TEMP
table; the table disappears when the connection closes. It does not benchmark
performance or require one measured capture to be faster than another.

This script needs an actual PostgreSQL instance. Syntax validation alone is not
evidence that the integration passes; the CI PostgreSQL service runs the check.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import subprocess
import tempfile


ROW_COUNT = 50_000
FILTER_QUERY = "SELECT id, payload FROM pg_temp.planscope_integration WHERE id = 4242"
SORT_QUERY = "SELECT id, payload FROM pg_temp.planscope_integration ORDER BY payload DESC, id DESC"
WORKLOAD = "postgres-ci-50000-rows-pad100-v1"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def cli(executable: Path, *arguments: object, allowed_codes: tuple[int, ...] = (0,)) -> tuple[int, dict]:
    process = subprocess.run(
        [str(executable), *map(str, arguments)],
        capture_output=True,
        text=True,
        encoding="utf-8",
        timeout=60,
    )
    require(
        process.returncode in allowed_codes,
        f"CLI {arguments[0]} exited {process.returncode}: {process.stderr.strip()}",
    )
    output = json.loads(process.stdout)
    require(isinstance(output, dict), f"CLI {arguments[0]} did not return a JSON object")
    return process.returncode, output


def explain(connection, query: str, options: str) -> list[dict]:
    # query/options are fixed constants selected by this script, never external input.
    result = connection.execute(f"EXPLAIN ({options}, FORMAT JSON) {query}").fetchone()[0]
    require(isinstance(result, list) and len(result) == 1, "Expected one real PostgreSQL statement plan")
    require(isinstance(result[0].get("Plan"), dict), "PostgreSQL response has no Plan object")
    return result


def raw_nodes(plan: dict):
    yield plan
    for child in plan.get("Plans", []):
        yield from raw_nodes(child)


def import_and_analyze(executable: Path, directory: Path, name: str, query_key: str, document: list[dict]) -> dict:
    source = directory / f"{name}.json"
    raw = (json.dumps(document, indent=2, ensure_ascii=False, allow_nan=False) + "\n").encode("utf-8")
    source.write_bytes(raw)
    database = directory / "captures.db"
    _, imported = cli(
        executable, "import", "--db", database, "--file", source,
        "--name", name, "--query", query_key, "--workload", WORKLOAD,
    )
    require(imported["sourceSha256"] == hashlib.sha256(raw).hexdigest(), "Imported source hash changed")
    _, analyzed = cli(executable, "analyze", "--db", database, "--plan", name, "--format", "json")
    require(analyzed["capture"]["id"] == imported["id"], "Analysis resolved a different capture")
    require(analyzed["capture"]["queryKey"] == query_key, "Query context was not preserved")
    require(analyzed["capture"]["workload"] == WORKLOAD, "Workload context was not preserved")
    return analyzed


def run(executable: Path, psycopg) -> None:
    with tempfile.TemporaryDirectory(prefix="planscope-postgres-") as temporary:
        directory = Path(temporary)
        # libpq reads PG* directly; credentials are never copied into commands or logs.
        with psycopg.connect(connect_timeout=10, application_name="planscope-ci-integration", autocommit=True) as connection:
            connection.execute("SET statement_timeout = '30s'")
            connection.execute("SET max_parallel_workers_per_gather = 0")
            connection.execute("SET jit = off")
            connection.execute("SET enable_seqscan = on")
            connection.execute("SET enable_indexscan = off")
            connection.execute("SET enable_indexonlyscan = off")
            connection.execute("SET enable_bitmapscan = off")
            connection.execute("SET enable_sort = on")
            connection.execute("SET work_mem = '64kB'")
            connection.execute("""
                CREATE TEMP TABLE planscope_integration AS
                SELECT n AS id, lpad(n::text, 100, '0') AS payload
                FROM generate_series(1, 50000) AS source(n)
            """)
            connection.execute("ANALYZE pg_temp.planscope_integration")
            count, minimum_width, maximum_width = connection.execute(
                "SELECT count(*), min(length(payload)), max(length(payload)) FROM pg_temp.planscope_integration"
            ).fetchone()
            require((count, minimum_width, maximum_width) == (ROW_COUNT, 100, 100), "Test data was not created as specified")

            filtered = explain(connection, FILTER_QUERY, "ANALYZE, BUFFERS")
            sorted_plan = explain(connection, SORT_QUERY, "ANALYZE, BUFFERS")
            estimated = explain(connection, FILTER_QUERY, "COSTS")
            untimed = explain(connection, FILTER_QUERY, "ANALYZE, BUFFERS, TIMING OFF")
            repeated = explain(connection, FILTER_QUERY, "ANALYZE, BUFFERS")
            server_version = connection.info.server_version

        # The database session is now closed; all application commands below are offline.
        scan = filtered[0]["Plan"]
        require(scan["Node Type"] == "Seq Scan", "Filter fixture did not use a sequential scan")
        require(scan["Actual Rows"] == 1 and scan["Actual Loops"] == 1, "Filter returned the wrong observed row count")
        require(scan["Rows Removed by Filter"] == ROW_COUNT - 1, "Filter removal counter differs from the fixture")
        filter_report = import_and_analyze(executable, directory, "filter-before", "filter-id-4242", filtered)
        require(filter_report["status"] == "OBSERVED", "Real analyzed plan was not recognized")
        require("FILTER_WASTE" in {finding["rule"] for finding in filter_report["findings"]}, "Selective scan did not trigger FILTER_WASTE")
        require(filter_report["nodes"][0]["metrics"]["Actual Rows"] == 1, "Imported actual row count changed")

        disk_sorts = [node for node in raw_nodes(sorted_plan[0]["Plan"]) if node.get("Sort Space Type") == "Disk"]
        require(bool(disk_sorts), "64 KiB sort fixture did not spill to disk")
        require(sorted_plan[0]["Plan"]["Actual Rows"] == ROW_COUNT, "Sort did not return all fixture rows")
        sort_report = import_and_analyze(executable, directory, "disk-sort", "sort-all-rows", sorted_plan)
        require("SORT_DISK" in {finding["rule"] for finding in sort_report["findings"]}, "Measured disk sort did not trigger SORT_DISK")

        estimate_report = import_and_analyze(executable, directory, "estimate-only", "filter-id-4242", estimated)
        require(estimate_report["status"] == "ESTIMATED_ONLY", "EXPLAIN without ANALYZE was not estimated-only")
        require(estimate_report["capture"]["analyzed"] is False, "Estimated capture invented observations")
        require(estimate_report["capture"]["executionMs"] is None, "Estimated capture invented execution time")
        require(not estimate_report["findings"], "Estimated-only capture produced observed findings")

        require(all("Actual Total Time" not in node and "Actual Startup Time" not in node
                    for node in raw_nodes(untimed[0]["Plan"])), "TIMING OFF unexpectedly has node timing fields")
        untimed_report = import_and_analyze(executable, directory, "timing-off", "filter-id-4242", untimed)
        require(untimed_report["status"] == "OBSERVED", "TIMING OFF lost observed row counters")
        require(untimed_report["capture"]["executionMs"] == untimed[0]["Execution Time"], "TIMING OFF lost top-level execution time")
        require(all("Actual Total Time" not in node["metrics"] and "Actual Startup Time" not in node["metrics"]
                    for node in untimed_report["nodes"]), "Importer invented TIMING OFF node timings")

        repeat_report = import_and_analyze(executable, directory, "filter-after", "filter-id-4242", repeated)
        require(repeat_report["nodes"][0]["metrics"]["Actual Rows"] == 1, "Repeated filter returned the wrong row count")
        exit_code, comparison = cli(
            executable, "compare", "--db", directory / "captures.db",
            "--baseline", "filter-before", "--candidate", "filter-after", "--format", "json",
            allowed_codes=(0, 2, 3),
        )
        # A timing increase is valid test evidence, never a flaky integration failure.
        require(comparison["status"] in {"PASS", "REGRESSION", "INCONCLUSIVE"}, "Invalid comparison status")
        require(exit_code == {"PASS": 0, "REGRESSION": 2, "INCONCLUSIVE": 3}[comparison["status"]], "Comparison exit code disagrees with its report")
        require(comparison["execution"]["baselineMs"] == filtered[0]["Execution Time"], "Baseline measured time was not preserved")
        require(comparison["execution"]["candidateMs"] == repeated[0]["Execution Time"], "Candidate measured time was not preserved")
        require(comparison["baseline"]["queryKey"] == comparison["candidate"]["queryKey"] == "filter-id-4242", "Comparison query labels differ")
        require(comparison["baseline"]["workload"] == comparison["candidate"]["workload"] == WORKLOAD, "Comparison workload labels differ")
        if filtered[0]["Execution Time"] > 0:
            require(comparison["status"] != "INCONCLUSIVE", "Comparable observed captures unexpectedly became inconclusive")

        print(json.dumps({
            "check": "real-postgresql-integration",
            "serverVersion": server_version,
            "fixtureRows": ROW_COUNT,
            "passed": ["FILTER_WASTE", "SORT_DISK", "ESTIMATED_ONLY", "TIMING_OFF", "comparison-evidence"],
            "measuredComparisonStatus": comparison["status"],
            "note": "Observed integration behavior; no performance-speedup assertion.",
        }))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--install-dir", type=Path, required=True)
    args = parser.parse_args()
    executable = args.install_dir.resolve() / "bin" / ("planscope.bat" if os.name == "nt" else "planscope")
    require(executable.is_file(), f"Installed CLI not found: {executable}")
    try:
        import psycopg
    except ImportError as error:
        raise SystemExit("Install the test dependency: python -m pip install 'psycopg[binary]==3.3.7'") from error
    require(importlib.metadata.version("psycopg") == "3.3.7", "This integration check requires psycopg==3.3.7")
    run(executable, psycopg)


if __name__ == "__main__":
    main()
