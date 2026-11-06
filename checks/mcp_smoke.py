"""Exercise the installed read-only server through the official MCP Python client.

Install the client with: python -m pip install mcp==1.26.0
Run: python checks/mcp_smoke.py --install-dir build/install/planscope
Uses synthetic local fixtures; no PostgreSQL connection or model is invoked.
"""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

try:
    from jsonschema import Draft202012Validator
    from mcp import ClientSession, StdioServerParameters
    from mcp.client.stdio import stdio_client
    from mcp.shared.exceptions import McpError
except ImportError as error:
    raise SystemExit("Install the official client first: python -m pip install mcp==1.26.0") from error


CANARY = "PLAN_SCOPE_PRIVATE_SQL_CANARY"


def fixture(execution_ms: float) -> list[dict]:
    return [{
        "Execution Time": execution_ms,
        "Planning Time": 0.5,
        "Plan": {
            "Node Type": "Append",
            "Actual Rows": 33_000,
            "Actual Loops": 1,
            "Actual Total Time": execution_ms,
            "Plans": [{
                "Node Type": "Seq Scan",
                "Relation Name": f"orders_{index}",
                "Plan Rows": 1,
                "Actual Rows": 1000,
                "Actual Loops": 1,
                "Actual Total Time": execution_ms / 20,
                "Filter": CANARY,
                "Output": [CANARY],
            } for index in range(33)],
        },
    }]


def pointer(document: object, location: str) -> object:
    value = document
    for part in location.split("/")[1:]:
        key = part.replace("~1", "/").replace("~0", "~")
        value = value[int(key)] if isinstance(value, list) else value[key]
    return value


async def verify(java: str, classpath: str, database: Path, candidate: list[dict], source_hash: str) -> dict:
    params = StdioServerParameters(
        command=java,
        args=["-cp", classpath, "io.github.almeida96.planscope.Main", "mcp", "--db", str(database)],
    )
    async with stdio_client(params) as (read, write):
        async with ClientSession(read, write) as session:
            initialized = await session.initialize()
            assert initialized.serverInfo.name == "planscope"
            tools = (await session.list_tools()).tools
            expected = {"list_plans", "analyze_plan", "inspect_node", "compare_plans"}
            assert {tool.name for tool in tools} == expected
            schemas = {tool.name: Draft202012Validator(tool.inputSchema) for tool in tools}
            for tool in tools:
                Draft202012Validator.check_schema(tool.inputSchema)
                assert tool.inputSchema["additionalProperties"] is False
                assert tool.annotations.readOnlyHint is True
                assert tool.annotations.destructiveHint is False
                assert tool.annotations.openWorldHint is False

            async def call(name: str, arguments: dict, *, failed: bool = False) -> dict:
                schemas[name].validate(arguments)
                result = await session.call_tool(name, arguments)
                assert bool(result.isError) == failed, result
                content = json.loads(result.content[0].text)
                assert content == result.structuredContent
                assert CANARY not in json.dumps(content)
                return content

            first = await call("list_plans", {"limit": 1})
            second = await call("list_plans", {"offset": first["nextOffset"], "limit": 1})
            assert first["captures"][0]["name"] == "baseline"
            assert second["captures"][0]["name"] == "candidate"
            assert second["nextOffset"] is None
            candidate_id = second["captures"][0]["id"]
            assert second["captures"][0]["sourceSha256"] == source_hash

            findings: list[dict] = []
            offset = 0
            while True:
                analysis = await call("analyze_plan", {"id": candidate_id, "offset": offset, "limit": 7})
                assert "nodes" not in analysis
                assert analysis["capture"]["nodeCount"] == 34
                assert analysis["nodeIds"] == {"first": "n0", "last": "n33"}
                page = analysis["findings"]
                assert page["total"] == 33 and len(page["items"]) <= 7
                findings.extend(page["items"])
                if page["nextOffset"] is None:
                    break
                assert page["nextOffset"] > offset
                offset = page["nextOffset"]
            assert len(findings) == len({row["id"] for row in findings}) == 33
            evidence = await call("inspect_node", {"id": candidate_id, "nodeId": findings[0]["nodeId"]})
            assert evidence["capture"]["sourceSha256"] == source_hash
            raw_node = pointer(candidate, evidence["node"]["pointer"])
            assert raw_node["Node Type"] == evidence["node"]["kind"]
            assert raw_node["Actual Rows"] == evidence["node"]["metrics"]["Actual Rows"]
            assert evidence["node"]["pointer"] == findings[0]["pointer"]
            assert "Filter" not in evidence["node"] and "Output" not in evidence["node"]

            changes: list[dict] = []
            offset = 0
            while True:
                comparison = await call("compare_plans", {"baseline": "baseline", "candidate": candidate_id, "offset": offset, "limit": 9})
                assert comparison["status"] == "REGRESSION"
                assert comparison["candidateFindingCount"] == 33
                assert "candidateFindings" not in comparison
                page = comparison["nodeChanges"]
                assert page["total"] == 34 and len(page["items"]) <= 9
                changes.extend(page["items"])
                if page["nextOffset"] is None:
                    break
                assert page["nextOffset"] > offset
                offset = page["nextOffset"]
            assert len(changes) == len({row["pointer"] for row in changes}) == 34
            for thresholds in ({"maxIncreaseMs": 40}, {"maxIncreasePercent": 200}):
                result = await call("compare_plans", {"baseline": "baseline", "candidate": candidate_id, **thresholds})
                assert result["status"] == "PASS"
            same = await call("compare_plans", {"baseline": "baseline", "candidate": "baseline"})
            assert same["status"] == "INCONCLUSIVE"

            missing = await call("inspect_node", {"id": candidate_id, "nodeId": "n4999"}, failed=True)
            assert missing["error"]["code"] == "NODE_NOT_FOUND"
            missing = await call("analyze_plan", {"id": "unknown"}, failed=True)
            assert missing["error"]["code"] == "CAPTURE_UNAVAILABLE"

            invalid = [
                ("list_plans", {"path": str(database)}),
                ("list_plans", {"limit": 51}),
                ("list_plans", {"offset": -1}),
                ("list_plans", {"limit": "1"}),
                ("analyze_plan", {}),
                ("analyze_plan", {"id": "candidate", "sql": "SELECT 1"}),
                ("inspect_node", {"id": "candidate", "nodeId": "n5000"}),
                ("compare_plans", {"baseline": "baseline", "candidate": "candidate", "maxIncreaseMs": -1}),
                ("compare_plans", {"baseline": "baseline", "candidate": "candidate", "maxIncreasePercent": None}),
            ]
            for name, arguments in invalid:
                assert not schemas[name].is_valid(arguments), (name, arguments)
                try:
                    await session.call_tool(name, arguments)
                except McpError as error:
                    assert error.error.code == -32602, error
                else:
                    raise AssertionError(f"Accepted invalid tool arguments: {name}")

            return {
                "status": "passed",
                "client": "official MCP Python SDK",
                "clientVersion": importlib.metadata.version("mcp"),
                "protocolVersion": initialized.protocolVersion,
                "tools": sorted(expected),
                "findingsPaged": len(findings),
                "nodeChangesPaged": len(changes),
                "invalidArgumentsRejected": len(invalid),
                "sourcePointersVerified": True,
                "rawSqlCanaryOmitted": True,
                "modelInvoked": False,
                "postgresqlConnected": False,
            }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--install-dir", type=Path, required=True)
    args = parser.parse_args()
    install = args.install_dir.resolve()
    if not (install / "lib").is_dir():
        raise SystemExit("Installed lib directory is missing; run gradlew installDist first")
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")) if java_home else shutil.which("java")
    if not java:
        raise SystemExit("Java 21 is required; set JAVA_HOME or add java to PATH")
    classpath = str(install / "lib" / "*")
    with tempfile.TemporaryDirectory(prefix="planscope-mcp-") as temp:
        directory = Path(temp)
        database = directory / "plans.db"
        candidate = fixture(60)
        source_hash = ""
        for name, document in (("baseline", fixture(20)), ("candidate", candidate)):
            path = directory / f"{name}.json"
            raw = json.dumps(document, ensure_ascii=False).encode("utf-8")
            path.write_bytes(raw)
            if name == "candidate":
                source_hash = hashlib.sha256(raw).hexdigest()
            completed = subprocess.run(
                [java, "-cp", classpath, "io.github.almeida96.planscope.Main", "import", "--db", str(database),
                 "--file", str(path), "--name", name, "--query", "orders", "--workload", "synthetic-mcp-v1"],
                capture_output=True, text=True, encoding="utf-8", timeout=30,
            )
            assert completed.returncode == 0, completed.stderr
            assert json.loads(completed.stdout)["name"] == name
        before = database.read_bytes()
        result = asyncio.run(verify(java, classpath, database, candidate, source_hash))
        assert database.read_bytes() == before, "MCP modified the capture database"
        result["captureStoreByteIdentical"] = True
        print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
