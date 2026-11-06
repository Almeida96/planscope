package io.github.almeida96.planscope;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;

/** Standalone reports: no script, external assets, raw predicates or SQL. */
public final class Reports {
    public static String render(JsonNode report, String format) {
        return switch (format) {
            case "json" -> Json.pretty(report);
            case "md" -> markdown(report);
            case "html" -> html(report);
            default -> throw new IllegalArgumentException("Format must be json, md or html");
        };
    }
    private static String markdown(JsonNode r) {
        var s = new StringBuilder("# PlanScope\n\n**Status:** " + r.path("status").asText() + "\n\n");
        boolean compare = r.path("type").asText().equals("comparison");
        if (compare) {
            s.append("Baseline: ").append(md(r.path("baseline").path("name").asText())).append("\n\nCandidate: ")
                .append(md(r.path("candidate").path("name").asText())).append("\n\n");
            s.append("| Execution | Baseline | Candidate | Delta |\n|---|---:|---:|---:|\n| ms | ")
                .append(number(r.path("execution").path("baselineMs"))).append(" | ")
                .append(number(r.path("execution").path("candidateMs"))).append(" | ")
                .append(number(r.path("execution").path("deltaMs"))).append(" |\n\n")
                .append(r.path("policyRule").asText()).append("\n\n");
            for (JsonNode reason : r.path("reasons")) s.append("- ").append(md(reason.asText())).append('\n');
        } else s.append("Capture: ").append(md(r.path("capture").path("name").asText())).append("\n\n");
        s.append("\n## Findings\n\n");
        JsonNode findings = r.path(compare ? "candidateFindings" : "findings");
        if (findings.isEmpty()) s.append("No rules triggered for the observed fields.\n\n");
        for (JsonNode f : findings) s.append("### ").append(md(f.path("id").asText())).append("\n\n")
            .append(md(f.path("message").asText())).append("\n\nSource: `").append(f.path("pointer").asText())
            .append("`\n\n```json\n").append(Json.pretty(f.path("evidence"))).append("```\n\n")
            .append(md(f.path("nextCheck").asText())).append("\n\n");
        s.append("## Interpretation\n\n");
        for (JsonNode limitation : r.path("limitations")) s.append("- ").append(md(limitation.asText())).append('\n');
        return s.toString();
    }
    private static String html(JsonNode r) {
        boolean compare = r.path("type").asText().equals("comparison");
        String status = r.path("status").asText();
        var s = new StringBuilder("""
            <!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'">
            <title>PlanScope — execution evidence</title><style>
            :root{color-scheme:dark}*{box-sizing:border-box}body{margin:0;background:#10181e;color:#e2e9ed;font:16px/1.6 system-ui,sans-serif}
            main{max-width:1150px;margin:auto;padding:48px 28px}header{border-bottom:1px solid #33454d;padding-bottom:28px;margin-bottom:28px}
            .eyebrow{font-size:12px;letter-spacing:.2em;color:#86b8bd;text-transform:uppercase}h1{font-size:46px;letter-spacing:-.04em;margin:10px 0}h2{font-size:24px;margin-top:38px}
            .badge{display:inline-block;padding:5px 12px;border:1px solid #708e91;border-radius:6px;font:600 13px monospace;color:#c7e8e9}
            .REGRESSION{color:#ffb4a1;border-color:#a66b5d}.PASS{color:#b4e4b9}.INCONCLUSIVE{color:#efda99}
            .muted{color:#a5b5bd}.cards{display:grid;grid-template-columns:repeat(3,1fr);gap:16px}.card,article{background:#18252d;border:1px solid #31434c;border-radius:10px;padding:22px;margin:12px 0}
            .value{font-size:32px;font-weight:650;display:block}.label{font-size:13px;color:#a5b5bd}article h3{margin:0 0 10px;color:#b2d8db;font:600 16px monospace}
            table{width:100%;border-collapse:collapse;font-size:14px}td,th{padding:10px 12px;text-align:left;border-bottom:1px solid #33454d;overflow-wrap:anywhere}th{color:#a5b5bd}
            code,pre{font:13px/1.7 ui-monospace,monospace}pre{background:#10181e;padding:14px;overflow:auto;border-radius:6px}li{margin:8px 0}details{margin:18px 0}summary{cursor:pointer;color:#b2d8db}
            @media(max-width:650px){main{padding:24px 16px}.cards{grid-template-columns:1fr}h1{font-size:34px}table{font-size:12px}}
            </style><main><header><div class="eyebrow">PostgreSQL / plan evidence</div><h1>PlanScope</h1>
            """);
        s.append("<span class=\"badge ").append(esc(status)).append("\">").append(esc(status)).append("</span>");
        s.append("<p class=muted>Offline analysis of exported plans. Findings point to observations and checks, not an automatic fix.</p></header>");
        if (compare) {
            s.append("<h2>").append(esc(r.path("baseline").path("name").asText())).append(" → ")
                .append(esc(r.path("candidate").path("name").asText())).append("</h2><div class=cards>");
            for (String field : new String[]{"baselineMs", "candidateMs", "deltaMs"})
                s.append("<div class=card><span class=label>").append(switch (field) { case "baselineMs" -> "Baseline"; case "candidateMs" -> "Candidate"; default -> "Change"; }).append("</span><span class=value>")
                    .append(number(r.path("execution").path(field))).append(" <small>ms</small></span></div>");
            s.append("</div><p>").append(esc(r.path("policyRule").asText())).append("</p><p class=muted>Thresholds: ")
                .append(number(r.path("policy").path("maxIncreaseMs"))).append(" ms and ")
                .append(number(r.path("policy").path("maxIncreasePercent"))).append("%.</p>");
            for (JsonNode reason : r.path("reasons")) s.append("<p>").append(esc(reason.asText())).append("</p>");
        } else s.append("<h2>").append(esc(r.path("capture").path("name").asText())).append("</h2>");
        JsonNode context = r.path(compare ? "candidate" : "capture");
        s.append("<p class=muted>Query: ").append(esc(context.path("queryKey").asText())).append(" · Workload: ")
            .append(esc(context.path("workload").asText())).append(" · ").append(context.path("nodeCount").asInt()).append(" nodes</p>");
        for (JsonNode warning : context.path("warnings")) s.append("<p>").append(esc(warning.asText())).append("</p>");
        s.append("<h2>Investigation findings</h2>");
        JsonNode findings = r.path(compare ? "candidateFindings" : "findings");
        if (findings.isEmpty()) s.append("<p>No rules triggered for the observed fields.</p>");
        for (JsonNode f : findings) {
            s.append("<article><h3>").append(esc(f.path("id").asText())).append("</h3><p>").append(esc(f.path("message").asText()))
                .append("</p><table><thead><tr><th>Observed metric</th><th>Value</th></tr></thead><tbody>");
            f.path("evidence").fields().forEachRemaining(e -> s.append("<tr><td>").append(esc(e.getKey())).append("</td><td>").append(number(e.getValue())).append("</td></tr>"));
            s.append("</tbody></table><p>").append(esc(f.path("nextCheck").asText())).append("</p><code>")
                .append(esc(f.path("pointer").asText())).append("</code></article>");
        }
        s.append("<details><summary>Source hashes and structured evidence</summary><pre>").append(esc(Json.pretty(r))).append("</pre></details><h2>Interpretation</h2><ul>");
        for (JsonNode limitation : r.path("limitations")) s.append("<li>").append(esc(limitation.asText())).append("</li>");
        return s.append("</ul><p class=muted>PlanScope 0.1.0 · Local report · No external requests</p></main></html>\n").toString();
    }
    private static String number(JsonNode n) { return n.isNumber() ? String.format(Locale.ROOT, "%.3f", n.asDouble()) : "n/a"; }
    static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }
    private static String md(String s) { return esc(s).replace("\\", "\\\\").replace("*", "\\*").replace("_", "\\_").replace("`", "\\`").replace("[", "\\[").replace("]", "\\]").replace("|", "\\|").replace("\n", " ").replace("\r", " "); }
}
