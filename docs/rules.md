# Rules and interpretation

All six findings have severity `REVIEW`. They describe observed counters and propose a next check. They do not assign a proven cause, generate an index definition or make a query rewrite.

Every finding includes its node ID, JSON pointer, numeric evidence and `nextCheck`. Rules run only on nodes whose `Actual Loops` is greater than zero. Missing fields do not supply evidence that a threshold was reached.

| Rule | Trigger | Useful next check |
| --- | --- | --- |
| `ROW_ESTIMATE` | Both row values exist; the larger is at least 100; `max(actual, estimate) / max(1, min(actual, estimate)) >= 10` | Statistics freshness, correlated conditions and parameter selectivity |
| `FILTER_WASTE` | Removed rows × loops is at least 1,000, and removed / (removed + returned) is at least 0.9 | Predicate and available access paths |
| `SORT_DISK` | `Sort Space Type` is `Disk` | Sort input size, ordering alternatives and concurrent memory use |
| `HASH_BATCHES` | `Hash Batches` is greater than 1 | Input estimates, memory budget and temporary I/O |
| `REPEATED_WORK` | At least 1,000 loops and actual rows × loops is at least 10,000 | Parent join or correlated-subquery behavior |
| `HEAP_FETCHES` | `Index Only Scan` with at least 1,000 `Heap Fetches` | Visibility-map coverage and vacuum activity |

The row-estimate rule skips nodes beneath a `Limit`, semi join or anti join because their execution may end early. This is a conservative check for known early-stop cases, not a complete model of every executor behavior. Parallel nodes retain a warning; individual worker details are not analyzed.

## Read the metrics correctly

PostgreSQL's actual rows and node timings are per-loop averages. Multiplication by loops describes accumulated work; it is not an exclusive contribution to wall-clock time. Parent counters can include child activity, and parallel work overlaps. Do not sum node times or buffers across the tree. Planner costs use planner units, not milliseconds. See [Using EXPLAIN](https://www.postgresql.org/docs/18/using-explain.html) and the [buffer-counter documentation](https://www.postgresql.org/docs/18/sql-explain.html#SQL-EXPLAIN-PARAMETERS).

PlanScope uses top-level `Execution Time` for its threshold verdict. It reports planning time separately in capture summaries. The verdict does not include client network transfer or application latency.

## Threshold policy

For positive baseline time `b` and candidate time `c`:

```text
increase_ms = c - b
increase_percent = (c - b) / b * 100
REGRESSION = increase_ms > maxIncreaseMs AND increase_percent > maxIncreasePercent
```

Defaults are 10 ms and 20%. Both boundaries are strict; equality passes. Thresholds must be finite, nonnegative and no greater than `1e15`. Improvements and changes below either threshold produce `PASS` when the inputs are comparable.

`INCONCLUSIVE` means the comparison cannot apply that policy: query keys differ, workload labels differ, both references resolve to the same capture, either execution time is missing, or baseline time is zero. No relative percentage is assigned to a zero baseline.

Two different named captures of the same bytes are distinct records and can be compared. This does not supply two independent measurements. PlanScope does not perform statistical tests or aggregate a series of runs.

## Node matching

Comparison pairs nodes at the same JSON pointer only when their identity and the identity of every ancestor match. Identity includes node type, relation, schema, alias, index and parent relationship. An added, removed, replaced or reordered node can remain `UNMATCHED`; PlanScope does not guess a correspondence by similar timing.

Matched metric deltas cover actual rows, loops, total node time, shared read blocks and temporary written blocks when both sides provide the field. A shape change does not itself trigger `REGRESSION`; the execution-time policy is separate.

## Synthetic example

The included candidate has a 2,048 KiB disk sort at `/0/Plan`. Its index scan at `/0/Plan/Plans/0` estimates 10 rows and returns 1,000, and removes 49,000 rows while returning 1,000. Those values trigger `SORT_DISK`, `ROW_ESTIMATE` and `FILTER_WASTE`.

The recorded execution times are 42 and 145 ms, so both default thresholds are exceeded. Those numbers illustrate the report and tests. They do not demonstrate a performance result on a running PostgreSQL server.
