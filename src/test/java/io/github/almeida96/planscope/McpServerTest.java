package io.github.almeida96.planscope;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpServerTest {
  @TempDir Path directory;
  private Store store;
  private Path database;
  private static final String READY = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";

  @BeforeEach void setup() throws Exception {
    database = directory.resolve("plans.db");
    try (Store writable = new Store(database, true)) {
      writable.add("baseline", "query", "workload", plan(25, 20));
      writable.add("candidate", "query", "workload", plan(25, 60));
    }
    store = new Store(database, false);
  }

  @AfterEach void close() throws Exception { if (store != null) store.close(); }

  @Test void negotiatesVersionsAndRequiresReadyNotification() throws Exception {
    for (String version : List.of("2025-11-25", "2025-06-18", "future")) {
      JsonNode result = run(initialize(version)).getFirst().path("result");
      assertEquals(version.equals("2025-06-18") ? version : "2025-11-25", result.path("protocolVersion").asText());
      assertEquals("planscope", result.path("serverInfo").path("name").asText());
    }
    List<JsonNode> replies = run(request(1, "tools/list", Map.of()), initialize("2025-11-25"),
        request(2, "tools/list", Map.of()), READY, request(3, "tools/list", Map.of()));
    assertCode(replies.get(0), -32002); assertCode(replies.get(2), -32002);
    assertEquals(4, replies.get(3).path("result").path("tools").size());
    assertCode(run(initialize("2025-11-25"), initialize("2025-11-25")).getLast(), -32600);
  }

  @Test void listsClosedSchemasForOnlyReadOnlyTools() throws Exception {
    JsonNode tools = ready(request(1, "tools/list", Map.of())).getFirst().path("result").path("tools");
    assertEquals(List.of("list_plans", "analyze_plan", "inspect_node", "compare_plans"),
        list(tools).stream().map(node -> node.path("name").asText()).toList());
    for (JsonNode tool : tools) {
      assertFalse(tool.path("inputSchema").path("additionalProperties").asBoolean());
      assertTrue(tool.path("annotations").path("readOnlyHint").asBoolean());
      assertTrue(tool.path("annotations").path("idempotentHint").asBoolean());
      assertFalse(tool.path("annotations").path("destructiveHint").asBoolean());
      assertFalse(tool.path("annotations").path("openWorldHint").asBoolean());
      assertFalse(tool.path("inputSchema").path("properties").has("path"));
      assertCode(ready(call(tool.path("name").asText(), Map.of("path", "C:/secret"))).getFirst(), -32602);
    }
    assertEquals(50, tools.get(1).path("inputSchema").path("properties").path("limit").path("maximum").asInt());
    assertEquals(10, tools.get(3).path("inputSchema").path("properties").path("maxIncreaseMs").path("default").asInt());
  }

  @Test void pagesCapturesAndFindingsWithoutReturningRawPlans() throws Exception {
    JsonNode first = structured(ready(call("list_plans", Map.of("limit", 1))).getFirst());
    assertEquals("baseline", first.path("captures").get(0).path("name").asText());
    assertEquals(1, first.path("nextOffset").asInt());
    JsonNode last = structured(ready(call("list_plans", Map.of("offset", 1, "limit", 1))).getFirst());
    assertEquals("candidate", last.path("captures").get(0).path("name").asText());
    assertTrue(last.path("nextOffset").isNull());

    JsonNode analysis = structured(ready(call("analyze_plan", Map.of("id", "candidate"))).getFirst());
    assertEquals(26, analysis.path("capture").path("nodeCount").asInt());
    assertEquals(25, analysis.path("findings").path("total").asInt());
    assertEquals(20, analysis.path("findings").path("items").size());
    assertEquals(20, analysis.path("findings").path("nextOffset").asInt());
    assertFalse(analysis.has("nodes")); assertFalse(analysis.toString().contains("SECRET_SQL"));
    JsonNode next = structured(ready(call("analyze_plan", Map.of("id", "candidate", "offset", 20))).getFirst());
    assertEquals(5, next.path("findings").path("items").size());
    assertTrue(next.path("findings").path("nextOffset").isNull());
    JsonNode empty = structured(ready(call("analyze_plan", Map.of("id", "candidate", "offset", 30000))).getFirst());
    assertTrue(empty.path("findings").path("items").isEmpty());
    assertEquals(analysis, structured(ready(call("analyze_plan", Map.of("id", "candidate"))).getFirst()));
  }

  @Test void inspectionResolvesOneExactSourcePointerAndReportsMissingNodes() throws Exception {
    JsonNode result = structured(ready(call("inspect_node", Map.of("id", "candidate", "nodeId", "n1"))).getFirst());
    assertEquals("n1", result.path("node").path("id").asText());
    assertEquals("/0/Plan/Plans/0", result.path("node").path("pointer").asText());
    assertEquals("n0", result.path("node").path("parentId").asText());
    assertEquals("ROW_ESTIMATE", result.path("findings").get(0).path("rule").asText());
    assertEquals(64, result.path("capture").path("sourceSha256").asText().length());
    assertFalse(result.toString().contains("SECRET_SQL"));
    JsonNode missing = ready(call("inspect_node", Map.of("id", "candidate", "nodeId", "n4999"))).getFirst();
    assertTrue(missing.path("result").path("isError").asBoolean());
    assertEquals("NODE_NOT_FOUND", structured(missing).path("error").path("code").asText());
  }

  @Test void comparisonsPageChangesAndHonorInclusiveThresholdBoundaries() throws Exception {
    JsonNode comparison = structured(ready(call("compare_plans", Map.of("baseline", "baseline", "candidate", "candidate", "limit", 1))).getFirst());
    assertEquals("REGRESSION", comparison.path("status").asText());
    assertEquals(26, comparison.path("nodeChanges").path("total").asInt());
    assertEquals(1, comparison.path("nodeChanges").path("items").size());
    assertEquals(1, comparison.path("nodeChanges").path("nextOffset").asInt());
    assertEquals(25, comparison.path("candidateFindingCount").asInt());
    assertFalse(comparison.has("candidateFindings"));
    for (Map<String, Object> thresholds : List.of(
        Map.<String, Object>of("baseline", "baseline", "candidate", "candidate", "maxIncreaseMs", 40),
        Map.<String, Object>of("baseline", "baseline", "candidate", "candidate", "maxIncreasePercent", 200))) {
      assertEquals("PASS", structured(ready(call("compare_plans", thresholds)).getFirst()).path("status").asText());
    }
    assertEquals("INCONCLUSIVE", structured(ready(call("compare_plans", Map.of("baseline", "baseline", "candidate", "baseline"))).getFirst()).path("status").asText());
  }

  @Test void rejectsInvalidArgumentsBeforeAttemptingLookup() throws Exception {
    for (Object value : List.of(0, 51, 1.5, "20", true))
      assertCode(ready(call("list_plans", Map.of("limit", value))).getFirst(), -32602);
    for (Object value : List.of(-1, 1_000_001, 1L << 40, "0"))
      assertCode(ready(call("list_plans", Map.of("offset", value))).getFirst(), -32602);
    for (String id : List.of("", " ", "a".repeat(129), "bad\nlabel"))
      assertCode(ready(call("analyze_plan", Map.of("id", id))).getFirst(), -32602);
    for (String id : List.of("n5000", "n-1", "n00", "1", "n1/../../secret"))
      assertCode(ready(call("inspect_node", Map.of("id", "missing", "nodeId", id))).getFirst(), -32602);
    for (Object value : List.of(-1, "10", true, new java.math.BigDecimal("1e15").add(java.math.BigDecimal.ONE), new java.math.BigDecimal("-1e-1000")))
      assertCode(ready(call("compare_plans", Map.of("baseline", "missing", "candidate", "missing", "maxIncreaseMs", value))).getFirst(), -32602);
    assertCode(ready(call("analyze_plan", Map.of())).getFirst(), -32602);
    assertCode(ready(request(1, "tools/call", Map.of("name", "list_plans", "arguments", List.of()))).getFirst(), -32602);
    assertCode(ready(request(1, "tools/list", Map.of("cursor", "invalid"))).getFirst(), -32602);
  }

  @Test void missingCapturesAreToolErrorsWithoutLeakingInternalPaths() throws Exception {
    JsonNode reply = ready(call("analyze_plan", Map.of("id", "unknown"))).getFirst();
    assertFalse(reply.has("error")); assertTrue(reply.path("result").path("isError").asBoolean());
    assertEquals("CAPTURE_UNAVAILABLE", structured(reply).path("error").path("code").asText());
    assertFalse(reply.toString().contains(database.toString()));
  }

  @Test void notificationsCannotInvokeToolsAndMalformedMessagesDoNotBreakLaterRequests() throws Exception {
    List<JsonNode> responses = run("not-json", "[]", "{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"ping\"}",
        initialize("2025-11-25"), READY,
        "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\"list_plans\"}}",
        request(2, "ping", Map.of()), request(3, "resources/list", Map.of()));
    assertEquals(6, responses.size());
    assertCode(responses.get(0), -32700); assertCode(responses.get(1), -32600); assertCode(responses.get(2), -32600);
    assertTrue(responses.get(4).path("result").isEmpty()); assertCode(responses.get(5), -32601);
  }

  @Test void boundsFramesAndRecoversAfterMalformedUtf8AndAnOversizedLine() throws Exception {
    ByteArrayOutputStream input = new ByteArrayOutputStream();
    input.write(new byte[] {(byte) 0xc3, (byte) 0x28, '\n'});
    input.write('x'); input.write(new byte[McpServer.MAX_LINE_BYTES]); input.write('\n');
    input.write(request(9, "ping", Map.of()).getBytes(UTF_8));
    List<JsonNode> output = runBytes(input.toByteArray());
    assertEquals(3, output.size()); assertCode(output.get(0), -32700); assertCode(output.get(1), -32700);
    assertEquals(9, output.get(2).path("id").asInt());
  }

  @Test void allReadToolsLeaveTheDatabaseByteIdentical() throws Exception {
    byte[] before = Files.readAllBytes(database);
    ready(call("list_plans", Map.of()), call("analyze_plan", Map.of("id", "candidate")),
        call("inspect_node", Map.of("id", "candidate", "nodeId", "n0")),
        call("compare_plans", Map.of("baseline", "baseline", "candidate", "candidate")));
    assertArrayEquals(before, Files.readAllBytes(database));
    assertEquals(2, store.list(0, 50).path("captures").size());
  }

  @Test void maximumSizePlanStillReturnsOnlyRequestedFindingPage() throws Exception {
    store.close(); store = null;
    try (Store writable = new Store(database, true)) { writable.add("large", "query", "workload", plan(4999, 60)); }
    store = new Store(database, false);
    JsonNode result = structured(ready(call("analyze_plan", Map.of("id", "large", "limit", 50))).getFirst());
    assertEquals(5000, result.path("capture").path("nodeCount").asInt());
    assertEquals(4999, result.path("findings").path("total").asInt());
    assertEquals(50, result.path("findings").path("items").size());
    assertTrue(Json.compact(result).getBytes(UTF_8).length < 100_000);
    assertFalse(result.has("nodes"));
  }

  private static byte[] plan(int children, double execution) {
    var document = Json.MAPPER.createArrayNode();
    var statement = document.addObject().put("Execution Time", execution);
    var root = statement.putObject("Plan").put("Node Type", "Append").put("Actual Rows", 0).put("Actual Loops", 1).put("Actual Total Time", execution);
    var plans = root.putArray("Plans");
    for (int i = 0; i < children; i++) {
      plans.addObject().put("Node Type", "Seq Scan").put("Relation Name", "items").put("Plan Rows", 1)
          .put("Actual Rows", 1000).put("Actual Loops", 1).put("Actual Total Time", execution / 20)
          .put("Filter", "SECRET_SQL_PREDICATE").putArray("Output").add("SECRET_SQL_EXPRESSION");
    }
    return Json.compact(document).getBytes(UTF_8);
  }

  private static String initialize(String version) { return request(0, "initialize", Map.of("protocolVersion", version, "capabilities", Map.of(), "clientInfo", Map.of("name", "test", "version", "1"))); }
  private static String request(Object id, String method, Object params) { return Json.compact(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params)); }
  private static String call(String name, Object args) { return request(1, "tools/call", Map.of("name", name, "arguments", args)); }
  private List<JsonNode> ready(String... requests) throws Exception {
    var rows = new ArrayList<String>(); rows.add(initialize("2025-11-25")); rows.add(READY); rows.addAll(List.of(requests));
    return run(rows.toArray(String[]::new)).subList(1, requests.length + 1);
  }
  private List<JsonNode> run(String... requests) throws Exception { return runBytes((String.join("\n", requests) + "\n").getBytes(UTF_8)); }
  private List<JsonNode> runBytes(byte[] input) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    new McpServer(store).run(new ByteArrayInputStream(input), output);
    var rows = new ArrayList<JsonNode>();
    for (String line : output.toString(UTF_8).lines().toList()) rows.add(Json.read(line));
    return rows;
  }
  private static JsonNode structured(JsonNode response) throws Exception {
    JsonNode result = response.path("result");
    assertEquals(result.get("structuredContent"), Json.read(result.path("content").get(0).path("text").asText()));
    return result.get("structuredContent");
  }
  private static List<JsonNode> list(JsonNode node) { var rows = new ArrayList<JsonNode>(); node.forEach(rows::add); return rows; }
  private static void assertCode(JsonNode node, int code) { assertEquals(code, node.path("error").path("code").asInt(), node.toPrettyString()); }
}
