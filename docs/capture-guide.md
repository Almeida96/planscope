# Capture and import a plan

PlanScope reads exported files. Capture the plan separately with the PostgreSQL tools and access controls you already use.

## Export clean JSON

For a query you are authorized to execute, an example `psql` invocation is:

```sh
psql --no-psqlrc --no-align --tuples-only --set=ON_ERROR_STOP=1 --dbname=app_staging --command="EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) SELECT id, created_at FROM orders WHERE account_id = 42 ORDER BY created_at" --output=before.json
```

Replace the database and query with your own. The output options omit table formatting and row-count decorations. `--output` writes the file directly, avoiding shell-dependent text encoding. Use PostgreSQL's existing connection settings rather than putting a password in the command. These options are documented in the [psql reference](https://www.postgresql.org/docs/18/app-psql.html).

Use UTF-8 client encoding for the export; for example, set `PGCLIENTENCODING` to `UTF8` in the capture process's environment. PlanScope rejects input in other encodings.

`EXPLAIN ANALYZE` actually runs its statement, including side effects. Capture in an appropriate environment; an arbitrary statement is not made safe by asking for a plan. PostgreSQL documents transactions and rollback for suitable data-changing captures, and also describes the profiling overhead of `ANALYZE`. See the [EXPLAIN reference](https://www.postgresql.org/docs/18/sql-explain.html).

For an estimate without running the query, use `EXPLAIN (FORMAT JSON)`. PlanScope accepts it and labels the analysis `ESTIMATED_ONLY`; it cannot produce an execution-time verdict from that estimate.

## Keep a useful comparison record

Record the SQL revision, parameter values, database version, dataset, relevant settings and cache conditions outside PlanScope. Repeat captures under controlled conditions. Give each capture a new name; use the same query key and workload label for genuinely comparable runs.

```sh
planscope import --db plans.db --file before.json --name before-01 --query orders-by-account --workload staging-v3-warm
planscope import --db plans.db --file after.json --name after-01 --query orders-by-account --workload staging-v3-warm
planscope compare --db plans.db --baseline before-01 --candidate after-01 --format json
```

The query key is an explicit label, not an SQL fingerprint. The workload label is also supplied by you. Matching labels do not prove that the two executions had equivalent conditions.

`TIMING OFF` can reduce node-timing instrumentation while PostgreSQL still measures total statement execution time. PlanScope can compare that top-level execution time and analyze any supported row/buffer fields that are present. It does not invent missing node timings. This behavior of `TIMING` is described in the [EXPLAIN reference](https://www.postgresql.org/docs/18/sql-explain.html#SQL-EXPLAIN-PARAMETERS).

## Accepted input

The file must contain one JSON array with exactly one statement object and its `Plan` tree. Do not paste the formatted `psql` table, combine several statements, or wrap the document in a service-specific envelope. UTF-8 with an initial BOM is accepted.

| Bound | Accepted value |
| --- | --- |
| Raw input | At most 8 MiB |
| Plan nodes | At most 5,000 |
| Plan depth | Root depth 0; deepest accepted node depth 48 |
| Query, workload and capture labels | 1–128 characters; no control characters |
| Retained node names | Nonempty strings of at most 256 characters when present |
| Supported numeric fields | Finite values from 0 through `1e15` |

Duplicate JSON keys, malformed Unicode, trailing JSON documents and invalid numeric fields are rejected. `Actual Rows` and `Actual Loops` must appear together. Loop counts, worker counts and hash-batch counts must be integers. Unknown PostgreSQL fields may remain in the stored raw capture but do not become supported analysis features automatically.

The parser follows `Plans` children. It does not analyze worker detail, JIT, triggers, incremental-sort groups or remote execution details. A root without observed counters makes the capture estimated-only; a top-level `Execution Time` without observed root counters is invalid.

## Storage and troubleshooting

The first valid import initializes `plans.db`. Its parent directory must exist. A nonempty database owned by another application is rejected. Reusing a capture name with changed bytes or labels fails; choose a new name rather than attempting to replace history. Capture names cannot be 64-character lowercase hexadecimal hashes because those identify captures by ID.

Original bytes are stored in the database and checked against their SHA-256 hash when read. A change in whitespace or line endings changes the source hash. Reports exclude SQL text and predicate expressions; the database still contains the complete original file.

If a comparison is inconclusive, inspect its `reasons` array. If no rules trigger, inspect the available counters and warnings before treating it as an efficient plan. The [interpretation guide](rules.md) explains what each rule can establish.
