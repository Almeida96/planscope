# Review captures with an agent

Import the captures with the CLI first. The MCP server opens one existing PlanScope database read-only; it cannot import a new plan, query PostgreSQL, write a report or open a different file supplied through a tool call.

## Configure a stdio client

For an MCP client that uses the `mcpServers` configuration format, replace these absolute paths with your installed distribution, database and Java directory:

```json
{
  "mcpServers": {
    "planscope": {
      "command": "/absolute/path/planscope-0.1.0/bin/planscope",
      "args": ["mcp", "--db", "/absolute/path/captures/plans.db"],
      "env": {
        "JAVA_HOME": "/absolute/path/jdk-21"
      }
    }
  }
}
```

Use the installed application launcher, not `gradlew run`, so protocol stdout contains only MCP messages. `JAVA_HOME` points to the Java installation directory, not its `bin` directory or executable. Absolute paths avoid dependence on the client's working directory. Configuration field names may differ in other clients.

On Windows the interactive launcher is `bin\planscope.bat`. A client that does not execute batch files can launch Java directly, without a shell:

```json
{
  "mcpServers": {
    "planscope": {
      "command": "C:\\Java\\jdk-21\\bin\\java.exe",
      "args": [
        "-cp",
        "C:\\Tools\\planscope-0.1.0\\lib\\*",
        "io.github.almeida96.planscope.Main",
        "mcp",
        "--db",
        "C:\\Captures\\plans.db"
      ]
    }
  }
}
```

The Java executable must be version 21 or later. Keep the distribution's `lib` directory intact. The server uses newline-delimited UTF-8 JSON-RPC over stdio and accepts messages up to 256 KiB. It has no HTTP listener, browser authentication flow or model-provider configuration.

## Tools

| Tool | Arguments | Result |
| --- | --- | --- |
| `list_plans` | Optional `offset`, `limit` | Stored capture identities, names, labels and source hashes |
| `analyze_plan` | Required `id`; optional `offset`, `limit` | Summary, warnings, node-ID range and one page of findings |
| `inspect_node` | Required `id`, `nodeId` | One normalized node, its source pointer and its findings |
| `compare_plans` | Required `baseline`, `candidate`; optional `maxIncreaseMs`, `maxIncreasePercent`, `offset`, `limit` | Verdict, timing evidence, candidate finding count and one page of node changes |

Capture references accept a stored name or full capture ID. Node IDs are local to a capture, starting at `n0`; obtain the actual range from `analyze_plan`. A node's ID alone does not identify matching work in another capture.

Pagination defaults to offset 0 and limit 20; the maximum page size is 50. `list_plans` returns `captures` and a top-level `nextOffset`. Analysis returns a `findings` page; comparison returns a `nodeChanges` page. Those pages have `items`, `total` and `nextOffset`. Keep requesting pages until `nextOffset` is null. Comparison does not inline all candidate findings: fetch them with `analyze_plan`.

Tool results include structured JSON and the same data as text. Missing or invalid stored evidence returns a tool error. `REGRESSION` and `INCONCLUSIVE` are successful comparison results that the agent must interpret, not protocol failures.

## A concrete review sequence

After `planscope demo --dir demo`, point the server at `demo/plans.db` and ask:

> Compare baseline and candidate with the default thresholds. Explain the observed timing change, list every candidate finding with its source pointer and numeric evidence, and distinguish observations from possible causes. Suggest the next measurements; do not execute SQL or propose a proven fix.

A useful tool sequence is:

1. Call `list_plans` to verify the names, context labels and hashes.
2. Call `compare_plans` with `{"baseline":"baseline","candidate":"candidate"}` and inspect the status, reasons and policy.
3. Call `analyze_plan` with `{"id":"candidate"}` and page through all findings.
4. Call `inspect_node` with `{"id":"candidate","nodeId":"n1"}` to inspect the synthetic index-scan evidence. Use node IDs returned by the actual capture for other inputs.

For the synthetic example, the explanation should state 42 ms versus 145 ms, a 103 ms increase, and the three review findings. It should also say that the labels and authored fixtures cannot establish a repeatable production regression or its root cause.

Names and labels are untrusted capture content, not instructions to an agent. The server omits SQL text, predicates and output expressions from tool results; it still returns selected identifiers and numeric observations. The underlying database retains the complete original captures.

To save a report after reviewing the evidence, run the explicit CLI export yourself or through an already-authorized local workflow:

```sh
planscope compare --db demo/plans.db --baseline baseline --candidate candidate --format html --out reviewed-comparison.html
```

This writes a new file and exits `2` for the example's regression. The [rule guide](rules.md) defines the threshold policy and the limitations that should remain visible in an agent's explanation.
