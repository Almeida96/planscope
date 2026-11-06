package io.github.almeida96.planscope;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

class MainTest {
    @TempDir Path temp;
    record Result(int code, String output, String error) {}
    private Result run(String... args) {
        var out = new ByteArrayOutputStream(); var err = new ByteArrayOutputStream();
        int code = Main.run(args, new ByteArrayInputStream(new byte[0]), new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }
    @Test void helpAndUnknownArguments() {
        assertEquals(0, run("--help").code());
        assertEquals("planscope 0.1.0\n", run("--version").output().replace("\r\n", "\n"));
        assertEquals(1, run("banana").code());
        assertEquals(1, run("list", "--db").code());
        assertEquals(1, run("list", "--db", "a", "--db", "b").code());
        assertEquals(1, run("list", "--db", "a", "--sql", "DROP").code());
    }
    @Test void demoComparisonAndNoOverwrite() throws Exception {
        Path dir = temp.resolve("demo");
        assertEquals(0, run("demo", "--dir", dir.toString()).code());
        String db = dir.resolve("plans.db").toString();
        assertEquals(2, run("compare", "--db", db, "--baseline", "baseline", "--candidate", "candidate").code());
        assertEquals(0, run("compare", "--db", db, "--baseline", "candidate", "--candidate", "baseline").code());
        assertEquals(3, run("compare", "--db", db, "--baseline", "baseline", "--candidate", "baseline").code());
        byte[] before = Files.readAllBytes(dir.resolve("comparison.html"));
        assertEquals(1, run("analyze", "--db", db, "--plan", "candidate", "--out", dir.resolve("comparison.html").toString()).code());
        assertArrayEquals(before, Files.readAllBytes(dir.resolve("comparison.html")));
        assertEquals(1, run("demo", "--dir", dir.toString()).code());
    }
    @Test void badInputDoesNotCreateDatabase() throws Exception {
        Path invalid = temp.resolve("bad.json"), db = temp.resolve("bad.db");
        Files.writeString(invalid, "[]");
        assertEquals(1, run("import", "--db", db.toString(), "--file", invalid.toString(), "--name", "x", "--query", "q", "--workload", "w").code());
        assertFalse(Files.exists(db));
    }
    @Test void missingDatabaseIsNotCreated() {
        Path absent = temp.resolve("absent.db");
        assertEquals(1, run("list", "--db", absent.toString()).code());
        assertFalse(Files.exists(absent));
    }
}
