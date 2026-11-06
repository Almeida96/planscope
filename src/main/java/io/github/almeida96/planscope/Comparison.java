package io.github.almeida96.planscope;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;
import static io.github.almeida96.planscope.Model.*;

public final class Comparison {
    public record Policy(double maxIncreaseMs, double maxIncreasePercent) {
        public Policy {
            if (!Double.isFinite(maxIncreaseMs) || !Double.isFinite(maxIncreasePercent)
                    || maxIncreaseMs < 0 || maxIncreasePercent < 0 || maxIncreaseMs > 1e15 || maxIncreasePercent > 1e15)
                throw new IllegalArgumentException("Thresholds must be finite nonnegative numbers no greater than 1e15");
        }
    }

    public static ObjectNode compare(Capture baseline, Capture candidate, Policy policy) {
        var result = Json.MAPPER.createObjectNode().put("schemaVersion", 1).put("toolVersion", "0.1.0")
                .put("type", "comparison");
        result.set("baseline", Analyzer.summary(baseline)); result.set("candidate", Analyzer.summary(candidate));
        result.set("policy", Json.MAPPER.valueToTree(policy));
        result.put("policyRule", "REGRESSION only when execution time increases beyond BOTH absolute and relative thresholds; equality passes.");
        var reasons = result.putArray("reasons");
        if (!baseline.queryKey().equals(candidate.queryKey())) reasons.add("Query keys differ");
        if (!baseline.workload().equals(candidate.workload())) reasons.add("Workload labels differ");
        if (baseline.id().equals(candidate.id())) reasons.add("Baseline and candidate are the same capture");
        if (baseline.plan().executionMs() == null || candidate.plan().executionMs() == null)
            reasons.add("Both captures need top-level Execution Time observations");
        ObjectNode timings = result.putObject("execution");
        timings.set("baselineMs", Json.MAPPER.valueToTree(baseline.plan().executionMs()));
        timings.set("candidateMs", Json.MAPPER.valueToTree(candidate.plan().executionMs()));
        if (reasons.isEmpty()) {
            double before = baseline.plan().executionMs(), after = candidate.plan().executionMs();
            BigDecimal base = BigDecimal.valueOf(before);
            BigDecimal delta = BigDecimal.valueOf(after).subtract(base);
            Double percent = before > 0
                    ? delta.multiply(BigDecimal.valueOf(100)).divide(base, MathContext.DECIMAL128).doubleValue() : null;
            if (percent != null && !Double.isFinite(percent)) percent = null;
            timings.put("deltaMs", delta).set("deltaPercent", Json.MAPPER.valueToTree(percent));
            // A rounded zero baseline has no meaningful relative percentage; it is not an automatic pass.
            if (before == 0) { reasons.add("Zero baseline execution time cannot support a relative threshold"); result.put("status", "INCONCLUSIVE"); }
            else result.put("status", delta.compareTo(BigDecimal.valueOf(policy.maxIncreaseMs())) > 0
                    && delta.multiply(BigDecimal.valueOf(100)).compareTo(base.multiply(BigDecimal.valueOf(policy.maxIncreasePercent()))) > 0
                    ? "REGRESSION" : "PASS");
        } else result.put("status", "INCONCLUSIVE");

        var changes = result.putArray("nodeChanges");
        Map<String, Node> before = byPointer(baseline.plan()), after = byPointer(candidate.plan());
        var pointers = new TreeSet<String>(); pointers.addAll(before.keySet()); pointers.addAll(after.keySet());
        boolean shapeChanged = false;
        for (String pointer : pointers) {
            Node a = before.get(pointer), b = after.get(pointer);
            if (a == null || b == null || !sameLineage(pointer, before, after)) {
                shapeChanged = true;
                var change = changes.addObject().put("pointer", pointer).put("match", "UNMATCHED");
                change.put("baselineKind", a == null ? null : a.kind()).put("candidateKind", b == null ? null : b.kind());
                continue;
            }
            var deltas = Json.MAPPER.createObjectNode();
            for (String metric : List.of("Actual Rows", "Actual Loops", "Actual Total Time", "Shared Read Blocks", "Temp Written Blocks")) {
                Double av = a.metric(metric), bv = b.metric(metric);
                if (av != null && bv != null && !av.equals(bv))
                    deltas.putObject(metric).put("baseline", av).put("candidate", bv).put("delta", bv - av);
            }
            if (!deltas.isEmpty()) changes.addObject().put("pointer", pointer).put("match", "SAME_PATH_AND_IDENTITY")
                .put("baselineNodeId", a.id()).put("candidateNodeId", b.id()).set("metrics", deltas);
        }
        result.put("planShapeChanged", shapeChanged);
        result.set("candidateFindings", Json.MAPPER.valueToTree(Analyzer.findings(candidate.plan())));
        var limitations = result.putArray("limitations");
        Analyzer.LIMITATIONS.forEach(limitations::add);
        limitations.add("Matching query/workload labels are a user assertion, not proof of equivalent SQL, data, hardware, cache or parameters.");
        limitations.add("One observation per side cannot establish a repeatable performance change. Repeat under controlled conditions.");
        limitations.add("Nodes match only at the same JSON pointer with unchanged identity along their ancestor path; reordered or replaced nodes remain unmatched.");
        return result;
    }

    private static Map<String, Node> byPointer(Plan p) {
        var map = new LinkedHashMap<String, Node>(); p.nodes().forEach(n -> map.put(n.pointer(), n)); return map;
    }
    private static boolean sameLineage(String pointer, Map<String, Node> a, Map<String, Node> b) {
        while (true) {
            Node left = a.get(pointer), right = b.get(pointer);
            if (left == null || right == null || !left.identity().equals(right.identity())) return false;
            int parent = pointer.lastIndexOf("/Plans/");
            if (parent < 0) return true;
            pointer = pointer.substring(0, parent);
        }
    }
}
