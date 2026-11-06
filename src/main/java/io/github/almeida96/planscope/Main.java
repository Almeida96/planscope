package io.github.almeida96.planscope;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class Main {
    public static void main(String[] args) {
        int status = run(args, System.in, System.out, System.err);
        if (status != 0) System.exit(status);
    }
    static int run(String[] args, InputStream in, PrintStream out, PrintStream err) {
        try {
            if (args.length == 0 || args[0].equals("--help") || args[0].equals("help")) { out.print(HELP); return 0; }
            if (args.length == 1 && args[0].equals("--version")) { out.println("planscope 0.1.0"); return 0; }
            String command = args[0];
            Set<String> allowed = switch (command) {
                case "import" -> Set.of("db", "file", "name", "query", "workload");
                case "list" -> Set.of("db", "offset", "limit");
                case "analyze" -> Set.of("db", "plan", "format", "out");
                case "compare" -> Set.of("db", "baseline", "candidate", "max-increase-ms", "max-increase-percent", "format", "out");
                case "mcp" -> Set.of("db");
                case "demo" -> Set.of("dir");
                default -> throw new IllegalArgumentException("Unknown command: " + command);
            };
            if (args.length == 2 && args[1].equals("--help")) { out.print(HELP); return 0; }
            Map<String, String> flags = parse(args, allowed);
            if (command.equals("demo")) { demo(Path.of(required(flags, "dir")), out); return 0; }
            Path db = Path.of(required(flags, "db"));
            if (command.equals("import")) {
                // Validate before creating a database file, so malformed imports leave no empty database.
                byte[] raw = PlanParser.readFile(Path.of(required(flags, "file")));
                PlanParser.parse(raw);
                String name = required(flags, "name"), query = required(flags, "query"), workload = required(flags, "workload");
                Store.label(name); Store.label(query); Store.label(workload);
                try (Store store = new Store(db, true)) { out.print(Json.pretty(Analyzer.summary(store.add(name, query, workload, raw)))); }
                return 0;
            }
            String format = flags.getOrDefault("format", "json");
            if (!Set.of("json", "md", "html").contains(format)) throw new IllegalArgumentException("Format must be json, md or html");
            try (Store store = new Store(db, false)) {
                JsonNode report;
                switch (command) {
                    case "mcp" -> { new McpServer(store).run(in, out); return 0; }
                    case "list" -> report = store.list(integer(flags, "offset", 0, 0, 1_000_000), integer(flags, "limit", 20, 1, 50));
                    case "analyze" -> report = Analyzer.report(store.get(required(flags, "plan")));
                    case "compare" -> report = Comparison.compare(store.get(required(flags, "baseline")), store.get(required(flags, "candidate")),
                        new Comparison.Policy(decimal(flags, "max-increase-ms", 10), decimal(flags, "max-increase-percent", 20)));
                    default -> throw new IllegalArgumentException("Unknown command");
                }
                String rendered = Reports.render(report, format);
                if (flags.containsKey("out")) writeNew(Path.of(flags.get("out")), rendered);
                else out.print(rendered);
                return switch (report.path("status").asText()) { case "REGRESSION" -> 2; case "INCONCLUSIVE" -> 3; default -> 0; };
            }
        } catch (IOException | java.sql.SQLException | IllegalArgumentException e) {
            err.println("planscope: " + e.getMessage()); return 1;
        }
    }
    private static Map<String, String> parse(String[] args, Set<String> allowed) {
        var flags = new LinkedHashMap<String, String>();
        for (int i = 1; i < args.length; i += 2) {
            String arg = args[i];
            if (!arg.startsWith("--") || !allowed.contains(arg.substring(2))) throw new IllegalArgumentException("Unknown option: " + arg);
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) throw new IllegalArgumentException("Missing value for " + arg);
            if (flags.putIfAbsent(arg.substring(2), args[i + 1]) != null) throw new IllegalArgumentException("Repeated option: " + arg);
        }
        return flags;
    }
    private static String required(Map<String, String> flags, String key) {
        String value = flags.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing --" + key);
        return value;
    }
    private static int integer(Map<String, String> flags, String key, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(flags.getOrDefault(key, Integer.toString(fallback)));
            if (value < min || value > max) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) { throw new IllegalArgumentException("--" + key + " must be an integer from " + min + " to " + max); }
    }
    private static double decimal(Map<String, String> flags, String key, double fallback) {
        try { return Double.parseDouble(flags.getOrDefault(key, Double.toString(fallback))); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("--" + key + " must be a number"); }
    }
    private static void writeNew(Path target, String text) throws IOException {
        Files.writeString(target, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
    private static void demo(Path dir, PrintStream out) throws IOException, java.sql.SQLException {
        // Refuse an existing directory rather than overwrite a user's reports or database.
        Files.createDirectory(dir);
        try (Store store = new Store(dir.resolve("plans.db"), true)) {
            var captures = new ArrayList<Model.Capture>();
            for (String name : List.of("baseline", "candidate")) {
                try (var resource = Main.class.getResourceAsStream("/" + name + ".json")) {
                    if (resource == null) throw new IOException("Bundled example is missing");
                    byte[] raw = resource.readAllBytes();
                    Files.write(dir.resolve(name + ".json"), raw, StandardOpenOption.CREATE_NEW);
                    captures.add(store.add(name, "orders-by-account", "synthetic-demo-v1", raw));
                }
            }
            var comparison = Comparison.compare(captures.get(0), captures.get(1), new Comparison.Policy(10, 20));
            for (String format : List.of("json", "md", "html")) writeNew(dir.resolve("comparison." + format), Reports.render(comparison, format));
            writeNew(dir.resolve("analysis.json"), Json.pretty(Analyzer.report(captures.get(1))));
            out.print(Json.pretty(Json.MAPPER.createObjectNode().put("dataset", "synthetic-demo-v1")
                .put("status", comparison.path("status").asText()).put("report", dir.resolve("comparison.html").toString())
                .put("note", "Synthetic observations, not measured benchmark results. Demo exits 0 after creating artifacts.")));
        }
    }
    private static final String HELP = """
        PlanScope 0.1.0 — offline PostgreSQL plan evidence

        planscope import --db plans.db --file plan.json --name capture --query query-key --workload context
        planscope list --db plans.db [--offset 0] [--limit 20]
        planscope analyze --db plans.db --plan name-or-id [--format json|md|html] [--out NEW-FILE]
        planscope compare --db plans.db --baseline name-or-id --candidate name-or-id
            [--max-increase-ms 10] [--max-increase-percent 20] [--format json|md|html] [--out NEW-FILE]
        planscope mcp --db plans.db
        planscope demo --dir NEW-DIRECTORY

        Runtime: Java 21+. No PostgreSQL connection or model credentials required.
        Imports are immutable. Output files must not exist. MCP opens the database read-only.
        Exit codes: 0 success/PASS; 1 invalid input/I/O; 2 REGRESSION; 3 INCONCLUSIVE.
        Comparison requires identical query/workload labels and observed execution times.
        A threshold verdict describes two captures, not statistical significance or release safety.
        """;
}
