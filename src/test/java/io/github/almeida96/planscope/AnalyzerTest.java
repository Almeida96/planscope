package io.github.almeida96.planscope;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class AnalyzerTest {
    private Model.Plan plan(String fields) throws Exception {
        return PlanParser.parse(("[{\"Plan\":{" + fields + "}}]").getBytes(StandardCharsets.UTF_8));
    }
    private Set<String> rules(Model.Plan p) { return Analyzer.findings(p).stream().map(Model.Finding::rule).collect(Collectors.toSet()); }

    @Test void diskAndHashEvidenceUsesReportedCountersWithoutMultiplyingLoops() throws Exception {
        var p = plan("\"Node Type\":\"Hash\",\"Actual Rows\":100,\"Actual Loops\":12,\"Hash Batches\":8");
        var finding = Analyzer.findings(p).getFirst();
        assertEquals("HASH_BATCHES", finding.rule());
        assertEquals(8, finding.evidence().get("batches"));
        var sort = plan("\"Node Type\":\"Sort\",\"Actual Rows\":100,\"Actual Loops\":12,\"Sort Space Type\":\"Disk\",\"Sort Space Used\":128");
        assertEquals(128, Analyzer.findings(sort).getFirst().evidence().get("sortSpaceKiB"));
    }
    @Test void heapFetchesAreAlreadyAggregatedAndOnlyApplyToIndexOnlyScans() throws Exception {
        var p = plan("\"Node Type\":\"Index Only Scan\",\"Actual Rows\":1,\"Actual Loops\":100,\"Heap Fetches\":1000");
        assertEquals(Set.of("HEAP_FETCHES"), rules(p));
        assertEquals(1000, Analyzer.findings(p).getFirst().evidence().get("heapFetches"));
        assertTrue(rules(plan("\"Node Type\":\"Index Scan\",\"Actual Rows\":1,\"Actual Loops\":100,\"Heap Fetches\":1000")).isEmpty());
    }
    @Test void sequentialScanAndLargeInclusiveTimeAloneAreNotRegressions() throws Exception {
        var p = plan("\"Node Type\":\"Seq Scan\",\"Plan Rows\":100000,\"Actual Rows\":100000,\"Actual Loops\":1,\"Actual Total Time\":10000,\"Shared Read Blocks\":90000");
        assertTrue(rules(p).isEmpty());
    }
    @Test void filterRuleRequiresBothVolumeAndFraction() throws Exception {
        assertTrue(rules(plan("\"Node Type\":\"Seq Scan\",\"Actual Rows\":1,\"Actual Loops\":1,\"Rows Removed by Filter\":999")).isEmpty());
        assertTrue(rules(plan("\"Node Type\":\"Seq Scan\",\"Actual Rows\":200,\"Actual Loops\":1,\"Rows Removed by Filter\":1000")).isEmpty());
        assertEquals(Set.of("FILTER_WASTE"), rules(plan("\"Node Type\":\"Seq Scan\",\"Actual Rows\":100,\"Actual Loops\":1,\"Rows Removed by Filter\":1000")));
    }
    @Test void negativeUnderflowIsNotAcceptedAsZero() {
        assertThrows(java.io.IOException.class, () -> plan("\"Node Type\":\"Result\",\"Plan Rows\":-1e-999"));
    }
    @Test void labelsRejectMalformedUnicodeBeforeFingerprinting() throws Exception {
        assertThrows(java.io.IOException.class, () -> Store.label("capture\uD800"));
        Store.label("capture\uD83D\uDE80");
    }
}
