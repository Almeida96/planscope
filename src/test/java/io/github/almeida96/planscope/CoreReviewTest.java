package io.github.almeida96.planscope;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import static io.github.almeida96.planscope.Model.*;
import static org.junit.jupiter.api.Assertions.*;

class CoreReviewTest {
    @TempDir Path temp;

    private ObjectNode node(String kind, double estimated, double actual, long loops) {
        return Json.MAPPER.createObjectNode().put("Node Type", kind).put("Plan Rows", estimated)
                .put("Actual Rows", actual).put("Actual Loops", loops);
    }
    private byte[] document(ObjectNode root, Double execution) {
        var statement = Json.MAPPER.createObjectNode();
        statement.set("Plan", root);
        if (execution != null) statement.put("Execution Time", execution);
        return Json.compact(List.of(statement)).getBytes(StandardCharsets.UTF_8);
    }
    private Plan plan(ObjectNode root) throws IOException { return PlanParser.parse(document(root, 1.0)); }
    private Capture capture(String id, ObjectNode root, double execution) throws IOException {
        return new Capture(id, id, "same-query", "same-workload", PlanParser.parse(document(root, execution)));
    }
    private boolean hasRule(Plan plan, String rule) {
        return Analyzer.findings(plan).stream().anyMatch(f -> f.rule().equals(rule));
    }

    @Test void supportsAnalyzeWithCostsAndTimingDisabled() throws Exception {
        var root = node("Seq Scan", 100, 150, 1);
        root.remove("Plan Rows");
        Plan parsed = plan(root);
        assertTrue(parsed.analyzed());
        assertEquals(1.0, parsed.executionMs());
        assertNull(parsed.nodes().getFirst().metric("Actual Total Time"));
        assertTrue(Analyzer.findings(parsed).isEmpty());
    }

    @Test void estimatedPlansDoNotInventExecutionMetrics() throws Exception {
        var root = Json.MAPPER.createObjectNode().put("Node Type", "Seq Scan").put("Total Cost", 500);
        Plan parsed = PlanParser.parse(document(root, null));
        assertFalse(parsed.analyzed());
        assertNull(parsed.executionMs());
        assertTrue(Analyzer.findings(parsed).isEmpty());
        assertThrows(IOException.class, () -> PlanParser.parse(document(root, 12.0)));
    }

    @Test void comparesRowsPerLoopWithoutMultiplyingTheEstimate() throws Exception {
        Plan parsed = plan(node("Index Scan", 100, 100, 1000));
        assertFalse(hasRule(parsed, "ROW_ESTIMATE"));
        assertTrue(hasRule(parsed, "REPEATED_WORK"));
    }

    @Test void filterEvidenceMultipliesPerLoopRemovedRowsOnlyOnce() throws Exception {
        Plan parsed = plan(node("Seq Scan", 5, 5, 10).put("Rows Removed by Filter", 100));
        Finding finding = Analyzer.findings(parsed).stream().filter(f -> f.rule().equals("FILTER_WASTE")).findFirst().orElseThrow();
        assertEquals(1000.0, finding.evidence().get("approximateRemovedRows"));
    }

    @Test void neverExecutedNodesDoNotProduceObservedFindings() throws Exception {
        assertTrue(Analyzer.findings(plan(node("Sort", 10000, 0, 0).put("Sort Space Type", "Disk"))).isEmpty());
    }

    @Test void limitChildrenAndDescendantsSkipEstimateRules() throws Exception {
        var root = node("Limit", 1, 1, 1);
        var child = node("Result", 10000, 1, 1);
        child.putArray("Plans").add(node("Seq Scan", 10000, 1, 1));
        root.putArray("Plans").add(child);
        assertFalse(hasRule(plan(root), "ROW_ESTIMATE"));
    }

    @Test void bitmapCombinersDoNotMistakeUnreportedRowsForEstimateErrors() throws Exception {
        for (String kind : List.of("BitmapAnd", "BitmapOr")) {
            assertFalse(hasRule(plan(node(kind, 10000, 0, 1)), "ROW_ESTIMATE"), kind);
        }
    }

    @Test void mergeJoinInputsAreNotTrustedAsFullyConsumedCardinalities() throws Exception {
        var root = node("Merge Join", 1, 1, 1).put("Join Type", "Inner");
        root.putArray("Plans").add(node("Index Scan", 10000, 1, 1))
                .add(node("Index Scan", 100, 10000, 1));
        assertFalse(hasRule(plan(root), "ROW_ESTIMATE"));
    }

    @Test void statementAndNumericValidationRejectsMalformedInputs() {
        for (String value : List.of("[]", "{}", "[{},{}]", "[{\"Plan\":null}]",
                "[{\"Plan\":{\"Node Type\":\"Seq Scan\",\"Node Type\":\"Sort\"}}]",
                "[{\"Plan\":{\"Node Type\":\"Seq Scan\"}}] {}")) {
            assertThrows(IOException.class, () -> PlanParser.parse(value.getBytes(StandardCharsets.UTF_8)), value);
        }
        assertThrows(IOException.class, () -> plan(node("Seq Scan", -1, 1, 1)));
        assertThrows(IOException.class, () -> plan(node("Seq Scan", 1, 1, 1).put("Actual Loops", .5)));
        assertThrows(IOException.class, () -> plan(node("Seq Scan", 1, 1, 1).putNull("Actual Rows")));
        assertThrows(IOException.class, () -> plan(node("Seq Scan", 1, 1, 1).put("Parallel Aware", "false")));
    }

    @Test void rejectsInvalidUtf8AndUnpairedUnicode() {
        assertThrows(IOException.class, () -> PlanParser.parse(new byte[] {(byte) 0xc0, (byte) 0xaf}));
        assertThrows(IOException.class, () -> PlanParser.parse("[{\"Plan\":{\"Node Type\":\"\\ud800\"}}]".getBytes(StandardCharsets.UTF_8)));
    }

    @Test void enforcesFileNodeAndDepthBounds() throws Exception {
        byte[] oversized = new byte[PlanParser.MAX_BYTES + 1];
        Path file = temp.resolve("large.json");
        Files.write(file, oversized);
        assertThrows(IOException.class, () -> PlanParser.readFile(file));
        assertThrows(IOException.class, () -> PlanParser.parse(oversized));
        var root = node("Append", 1, 1, 1);
        var children = root.putArray("Plans");
        for (int i = 0; i < PlanParser.MAX_NODES - 1; i++) children.add(node("Result", 1, 1, 1));
        assertEquals(PlanParser.MAX_NODES, plan(root).nodes().size());
        children.add(node("Result", 1, 1, 1));
        assertThrows(IOException.class, () -> plan(root));
        var deep = node("Result", 1, 1, 1);
        var cursor = deep;
        for (int i = 0; i < 49; i++) {
            var next = node("Result", 1, 1, 1);
            cursor.putArray("Plans").add(next);
            cursor = next;
        }
        assertThrows(IOException.class, () -> plan(deep));
    }

    @Test void thresholdsRequireBothStrictIncreasesAndComparableContext() throws Exception {
        var before = capture("before", node("Result", 1, 1, 1), 100);
        var policy = new Comparison.Policy(10, 10);
        assertEquals("PASS", Comparison.compare(before, capture("equal", node("Result", 1, 1, 1), 110), policy).path("status").asText());
        assertEquals("REGRESSION", Comparison.compare(before, capture("higher", node("Result", 1, 1, 1), 111), policy).path("status").asText());
        assertEquals("INCONCLUSIVE", Comparison.compare(before, before, policy).path("status").asText());
        assertEquals("INCONCLUSIVE", Comparison.compare(capture("zero", node("Result", 1, 1, 1), 0), before, policy).path("status").asText());
        var different = new Capture("different", "different", "another-query", "same-workload", before.plan());
        assertEquals("INCONCLUSIVE", Comparison.compare(before, different, policy).path("status").asText());
        assertThrows(IllegalArgumentException.class, () -> new Comparison.Policy(Double.NaN, 1));
    }

    @Test void operationChangesBreakNodeAndDescendantMatching() throws Exception {
        for (String attribute : List.of("Join Type", "Strategy", "Operation", "Partial Mode", "Scan Direction")) {
            var root = node("Hash Join", 1, 1, 1).put(attribute, "before");
            root.putArray("Plans").add(node("Seq Scan", 1, 1, 1));
            var changed = root.deepCopy().put(attribute, "after");
            ((ObjectNode) changed.path("Plans").get(0)).put("Actual Rows", 2);
            var comparison = Comparison.compare(capture("before", root, 1), capture("after", changed, 2), new Comparison.Policy(0, 0));
            assertTrue(comparison.path("planShapeChanged").asBoolean(), attribute);
            assertTrue(comparison.path("nodeChanges").findValuesAsText("match").stream().allMatch("UNMATCHED"::equals), attribute);
        }
    }

    @Test void decimalThresholdEqualityDoesNotBecomeARegressionFromRounding() throws Exception {
        var before = capture("before", node("Result", 1, 1, 1), .70);
        var after = capture("after", node("Result", 1, 1, 1), .77);
        assertEquals("PASS", Comparison.compare(before, after, new Comparison.Policy(.07, 10)).path("status").asText());
        assertEquals("REGRESSION", Comparison.compare(before, after, new Comparison.Policy(.069, 9.99)).path("status").asText());
    }

    @Test void comparisonDoesNotEmitNonfiniteRelativePercentages() throws Exception {
        var comparison = Comparison.compare(capture("before", node("Result", 1, 1, 1), 1e-300),
                capture("after", node("Result", 1, 1, 1), 1e15), new Comparison.Policy(1, 10));
        assertEquals("REGRESSION", comparison.path("status").asText());
        assertTrue(comparison.path("execution").path("deltaPercent").isNull());
        assertFalse(Json.compact(comparison).contains("Infinity"));
    }

    @Test void captureStoreIsImmutableIdempotentAndReadOnly() throws Exception {
        Path db = temp.resolve("captures.db");
        byte[] raw = document(node("Result", 1, 1, 1), 1.0);
        Capture original;
        try (var store = new Store(db, true)) {
            original = store.add("before", "query", "workload", raw);
            assertEquals(original.id(), store.add("before", "query", "workload", raw).id());
            assertThrows(IOException.class, () -> store.add("before", "other-query", "workload", raw));
            store.add("after", "query", "workload", raw);
            assertEquals("after", store.list(0, 1).path("captures").get(0).path("name").asText());
            assertEquals(1, store.list(0, 1).path("nextOffset").asInt());
        }
        byte[] beforeRead = Files.readAllBytes(db);
        try (var store = new Store(db, false)) {
            assertEquals(original.id(), store.get("before").id());
            assertEquals(original.id(), store.get(original.id()).id());
            assertThrows(SQLException.class, () -> store.add("forbidden", "query", "workload", raw));
        }
        assertArrayEquals(beforeRead, Files.readAllBytes(db));
    }

    @Test void storeRejectsTamperedContextEvenWhenRawChecksumStillMatches() throws Exception {
        Path db = temp.resolve("context.db");
        try (var store = new Store(db, true)) { store.add("capture", "query", "workload", document(node("Result", 1, 1, 1), 1.0)); }
        try (var sql = DriverManager.getConnection("jdbc:sqlite:" + db); var update = sql.createStatement()) {
            update.executeUpdate("UPDATE captures SET workload='different-data'");
        }
        try (var store = new Store(db, false)) {
            assertThrows(IOException.class, () -> store.get("capture"));
            assertThrows(SQLException.class, () -> store.list(0, 20));
        }
    }

    @Test void storeRejectsTamperedRawAndForeignDatabases() throws Exception {
        Path db = temp.resolve("raw.db");
        try (var store = new Store(db, true)) { store.add("capture", "query", "workload", document(node("Result", 1, 1, 1), 1.0)); }
        try (var sql = DriverManager.getConnection("jdbc:sqlite:" + db); var update = sql.createStatement()) {
            update.executeUpdate("UPDATE captures SET raw=x'5b5d'");
        }
        try (var store = new Store(db, false)) { assertThrows(IOException.class, () -> store.get("capture")); }
        Path foreign = temp.resolve("foreign.db");
        try (var sql = DriverManager.getConnection("jdbc:sqlite:" + foreign); var command = sql.createStatement()) {
            command.executeUpdate("CREATE TABLE important(value TEXT)");
        }
        byte[] before = Files.readAllBytes(foreign);
        assertThrows(SQLException.class, () -> new Store(foreign, true));
        assertArrayEquals(before, Files.readAllBytes(foreign));
        Path missing = temp.resolve("missing.db");
        assertThrows(IOException.class, () -> new Store(missing, false));
        assertFalse(Files.exists(missing));
    }

    @Test void reportsEscapeNamesAndExcludeRawExpressions() throws Exception {
        String payload = "<script>alert('x')</script>&\"";
        var root = node("Seq Scan", 10000, 1, 1).put("Relation Name", payload).put("Filter", "secret_password = 'private'");
        var capture = new Capture("id", payload, payload, payload, plan(root));
        var report = Analyzer.report(capture);
        for (String format : List.of("json", "md", "html")) {
            String output = Reports.render(report, format);
            assertFalse(output.contains("secret_password"), format);
            if (!format.equals("json")) assertFalse(output.contains("<script>"), format);
        }
        assertTrue(Reports.render(report, "html").contains("&lt;script&gt;"));
        assertTrue(Reports.render(report, "html").contains("default-src 'none'"));
    }
}
