package io.github.almeida96.planscope;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import static io.github.almeida96.planscope.Model.*;

public final class Analyzer {
    public static final List<String> LIMITATIONS = List.of(
        "Rules describe the supplied observation, not statistical significance or a proven root cause.",
        "Node time and buffer counters include child work. They must not be summed across the tree.",
        "Actual rows and node times are per-loop averages; multiplying by loops estimates node work, not exclusive wall time.",
        "Parallel worker detail, JIT, triggers, incremental-sort groups and remote execution detail are not analyzed.",
        "BitmapAnd/BitmapOr actual rows are not reported by PostgreSQL; row-estimate rules exclude these nodes and merge-join descendants.",
        "No finding does not establish query efficiency. Plan costs are arbitrary planner units, not milliseconds.",
        "Names are untrusted input. SQL text, predicates and output expressions are excluded from reports and MCP responses.");

    public static List<Finding> findings(Plan plan) {
        var findings = new ArrayList<Finding>();
        for (Node n : plan.nodes()) {
            if (!n.executed()) continue;
            double rows = n.value("Actual Rows"), loops = n.value("Actual Loops");
            Double estimate = n.metric("Plan Rows");
            if (estimate != null && !n.possiblyPartial()
                    && !Set.of("BitmapAnd", "BitmapOr").contains(n.kind()) && Math.max(rows, estimate) >= 100) {
                double factor = Math.max(rows, estimate) / Math.max(1, Math.min(rows, estimate));
                if (factor >= 10) add(findings, n, "ROW_ESTIMATE", "REVIEW",
                    "Observed rows differ from the planner estimate by at least 10x (zero denominators use 1).",
                    Map.of("estimatedRowsPerLoop", estimate, "actualRowsPerLoop", rows, "factorFloorOne", factor),
                    "Check statistics freshness, correlated predicates and parameter selectivity. Rule skipped under known early-stop ancestors.");
            }
            double removed = n.value("Rows Removed by Filter"), visited = removed + rows;
            if (removed * loops >= 1000 && visited > 0 && removed / visited >= .9)
                add(findings, n, "FILTER_WASTE", "REVIEW", "At least 90% of rows reaching this filter were removed.",
                    Map.of("removedRowsPerLoop", removed, "outputRowsPerLoop", rows, "loops", loops,
                        "approximateRemovedRows", removed * loops, "removedFraction", removed / visited),
                    "Inspect the predicate and candidate access paths. A selective filter alone does not justify an index.");
            if (n.sortSpaceType().equals("Disk"))
                add(findings, n, "SORT_DISK", "REVIEW", "This sort reports disk space usage.",
                    Map.of("sortSpaceKiB", n.value("Sort Space Used")),
                    "Check sort input size and concurrent memory budgets before changing work_mem; inspect ordering/index alternatives.");
            if (n.value("Hash Batches") > 1)
                add(findings, n, "HASH_BATCHES", "REVIEW", "The hash operation used multiple batches.",
                    Map.of("batches", n.value("Hash Batches")),
                    "Check hash input estimates, memory budget and temporary I/O in this capture; do not assume a single cause.");
            if (loops >= 1000 && rows * loops >= 10000)
                add(findings, n, "REPEATED_WORK", "REVIEW", "Repeated execution produces at least 10,000 rows across 1,000 or more loops.",
                    Map.of("rowsPerLoop", rows, "loops", loops, "approximateRowsAcrossLoops", rows * loops),
                    "Inspect the parent join or correlated subquery. Compare alternatives with the same workload; repeated index probes can be appropriate.");
            if (n.kind().equals("Index Only Scan") && n.value("Heap Fetches") >= 1000)
                add(findings, n, "HEAP_FETCHES", "REVIEW", "The index-only scan still fetched at least 1,000 heap tuples.",
                    Map.of("heapFetches", n.value("Heap Fetches")),
                    "Check visibility-map coverage and vacuum activity before evaluating whether the index is effective.");
        }
        return List.copyOf(findings);
    }

    private static void add(List<Finding> list, Node n, String rule, String severity, String message,
            Map<String, Double> evidence, String next) {
        list.add(new Finding(rule + ":" + n.id(), rule, severity, n.id(), n.pointer(), message,
                Collections.unmodifiableMap(new TreeMap<>(evidence)), next));
    }

    public static ObjectNode summary(Capture c) {
        ObjectNode o = Json.MAPPER.createObjectNode();
        o.put("id", c.id()).put("name", c.name()).put("queryKey", c.queryKey()).put("workload", c.workload());
        o.put("sourceSha256", c.plan().sourceSha256()).put("analyzed", c.plan().analyzed());
        o.set("executionMs", Json.MAPPER.valueToTree(c.plan().executionMs()));
        o.set("planningMs", Json.MAPPER.valueToTree(c.plan().planningMs()));
        o.put("nodeCount", c.plan().nodes().size()).put("findingCount", findings(c.plan()).size());
        o.set("warnings", Json.MAPPER.valueToTree(c.plan().warnings()));
        return o;
    }

    public static ObjectNode report(Capture c) {
        ObjectNode o = Json.MAPPER.createObjectNode().put("schemaVersion", 1).put("toolVersion", "0.1.0")
                .put("type", "analysis").put("status", c.plan().analyzed() ? "OBSERVED" : "ESTIMATED_ONLY");
        o.set("capture", summary(c));
        o.set("findings", Json.MAPPER.valueToTree(findings(c.plan())));
        o.set("nodes", Json.MAPPER.valueToTree(c.plan().nodes()));
        o.set("limitations", Json.MAPPER.valueToTree(LIMITATIONS));
        return o;
    }
}
