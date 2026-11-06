package io.github.almeida96.planscope;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static io.github.almeida96.planscope.Model.*;

/** Reads one PostgreSQL FORMAT JSON statement, retaining pointers into the original document. */
public final class PlanParser {
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    public static final int MAX_NODES = 5000;
    private static final List<String> NUMBERS = List.of("Startup Cost", "Total Cost", "Plan Rows",
            "Plan Width", "Actual Startup Time", "Actual Total Time", "Actual Rows", "Actual Loops",
            "Rows Removed by Filter", "Rows Removed by Join Filter", "Rows Removed by Index Recheck",
            "Shared Hit Blocks", "Shared Read Blocks", "Shared Dirtied Blocks", "Shared Written Blocks",
            "Local Hit Blocks", "Local Read Blocks", "Temp Read Blocks", "Temp Written Blocks",
            "Sort Space Used", "Hash Batches", "Original Hash Batches", "Peak Memory Usage",
            "Heap Fetches", "Workers Planned", "Workers Launched");

    public static byte[] readFile(Path path) throws IOException {
        try (var in = Files.newInputStream(path)) {
            byte[] data = in.readNBytes(MAX_BYTES + 1);
            if (data.length > MAX_BYTES) throw new IOException("Plan exceeds 8 MiB");
            return data;
        }
    }

    public static Plan parse(byte[] data) throws IOException {
        if (data.length > MAX_BYTES) throw new IOException("Plan exceeds 8 MiB");
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        JsonNode document = Json.read(text);
        if (!document.isArray() || document.size() != 1 || !document.get(0).isObject())
            throw new IOException("Expected a PostgreSQL JSON array containing exactly one statement");
        JsonNode statement = document.get(0);
        var nodes = new ArrayList<Node>();
        walk(statement.get("Plan"), "/0/Plan", null, 0, false, false, nodes);
        var warnings = new ArrayList<String>();
        boolean analyzed = nodes.getFirst().metric("Actual Loops") != null;
        if (!analyzed) warnings.add("Estimated plan: no observed execution metrics. Costs are not milliseconds.");
        if (nodes.stream().anyMatch(Node::possiblyPartial))
            warnings.add("LIMIT, short-circuit joins or merge joins can stop children early or repeat rows; row-estimate rules skip those nodes.");
        if (nodes.stream().anyMatch(Node::parallel))
            warnings.add("Parallel work can overlap. Per-worker details are not analyzed; node times are not wall-clock contributions.");
        if (analyzed && nodes.stream().anyMatch(n -> n.metric("Actual Loops") == null))
            warnings.add("Some nodes have no observed counters; findings cover only nodes with observations.");
        Double execution = number(statement, "Execution Time", "/0");
        Double planning = number(statement, "Planning Time", "/0");
        if (execution != null && !analyzed) throw new IOException("Execution Time requires observed root counters");
        return new Plan(Json.hash(data), analyzed, execution, planning, List.copyOf(nodes), List.copyOf(warnings));
    }

    private static void walk(JsonNode raw, String pointer, String parent, int depth,
            boolean partial, boolean parallel, List<Node> nodes) throws IOException {
        if (raw == null || !raw.isObject()) throw new IOException(pointer + " must be a plan object");
        if (depth > 48 || nodes.size() >= MAX_NODES) throw new IOException("Plan exceeds depth 48 or 5,000 nodes");
        String kind = text(raw, "Node Type", pointer, true);
        var metrics = new LinkedHashMap<String, Double>();
        for (String key : NUMBERS) {
            Double n = number(raw, key, pointer);
            if (n != null) metrics.put(key, n);
        }
        if (raw.has("Actual Rows") != raw.has("Actual Loops"))
            throw new IOException(pointer + " must provide Actual Rows and Actual Loops together");
        for (String key : List.of("Actual Loops", "Workers Planned", "Workers Launched", "Hash Batches")) {
            Double n = metrics.get(key);
            if (n != null && n != Math.rint(n)) throw new IOException(pointer + "/" + key + " must be an integer");
        }
        if (raw.has("Parallel Aware") && !raw.get("Parallel Aware").isBoolean())
            throw new IOException(pointer + "/Parallel Aware must be boolean");
        boolean isParallel = parallel || raw.path("Parallel Aware").asBoolean() || kind.startsWith("Gather");
        String id = "n" + nodes.size();
        nodes.add(new Node(id, pointer, parent, depth, kind, text(raw, "Relation Name", pointer, false),
                text(raw, "Schema", pointer, false), text(raw, "Alias", pointer, false),
                text(raw, "Index Name", pointer, false), text(raw, "Parent Relationship", pointer, false),
                text(raw, "Join Type", pointer, false), text(raw, "Strategy", pointer, false),
                text(raw, "Operation", pointer, false), text(raw, "Partial Mode", pointer, false),
                text(raw, "Scan Direction", pointer, false),
                text(raw, "Sort Space Type", pointer, false), partial, isParallel,
                Collections.unmodifiableMap(metrics)));
        boolean childPartial = partial || kind.equals("Limit") || kind.equals("Merge Join")
                || Set.of("Semi", "Anti").contains(raw.path("Join Type").asText());
        if (raw.has("Plans")) {
            JsonNode children = raw.get("Plans");
            if (!children.isArray()) throw new IOException(pointer + "/Plans must be an array");
            for (int i = 0; i < children.size(); i++)
                walk(children.get(i), pointer + "/Plans/" + i, id, depth + 1, childPartial, isParallel, nodes);
        }
    }

    private static String text(JsonNode value, String key, String pointer, boolean required) throws IOException {
        JsonNode n = value.get(key);
        if (n == null && !required) return "";
        if (n == null || !n.isTextual() || n.asText().isBlank() || n.asText().length() > 256)
            throw new IOException(pointer + "/" + key + " must be a nonempty string of at most 256 characters");
        return n.asText();
    }

    private static Double number(JsonNode value, String key, String pointer) throws IOException {
        JsonNode n = value.get(key);
        if (n == null) return null;
        if (!n.isNumber() || !Double.isFinite(n.doubleValue()) || n.decimalValue().signum() < 0
                || n.decimalValue().compareTo(new java.math.BigDecimal("1e15")) > 0)
            throw new IOException(pointer + "/" + key + " must be a finite number between 0 and 1e15");
        return n.doubleValue();
    }
}
