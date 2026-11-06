package io.github.almeida96.planscope;

import java.util.List;
import java.util.Map;

public final class Model {
    private Model() {}
    public record Node(String id, String pointer, String parentId, int depth, String kind,
            String relation, String schema, String alias, String index, String relationship,
            String joinType, String strategy, String operation, String partialMode, String scanDirection,
            String sortSpaceType, boolean possiblyPartial, boolean parallel,
            Map<String, Double> metrics) {
        public Double metric(String key) { return metrics.get(key); }
        public double value(String key) { return metrics.getOrDefault(key, 0.0); }
        public boolean executed() { return value("Actual Loops") > 0; }
        public String identity() {
            return Json.compact(List.of(kind, relation, schema, alias, index, relationship,
                    joinType, strategy, operation, partialMode, scanDirection));
        }
    }
    public record Plan(String sourceSha256, boolean analyzed, Double executionMs, Double planningMs,
            List<Node> nodes, List<String> warnings) {}
    public record Capture(String id, String name, String queryKey, String workload, Plan plan) {}
    public record Finding(String id, String rule, String severity, String nodeId, String pointer,
            String message, Map<String, Double> evidence, String nextCheck) {}
}
