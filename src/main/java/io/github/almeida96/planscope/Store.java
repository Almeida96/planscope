package io.github.almeida96.planscope;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.sqlite.SQLiteConfig;
import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static io.github.almeida96.planscope.Model.*;

/** Immutable captures. Read commands use a real read-only SQLite connection. */
public final class Store implements AutoCloseable {
    private static final int APPLICATION_ID = 0x50534350;
    private final Connection connection;
    private final boolean writable;

    public Store(Path path, boolean writable) throws SQLException, IOException {
        this.writable = writable;
        if (!writable && !Files.isRegularFile(path)) throw new IOException("Capture database does not exist");
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(!writable);
        config.setBusyTimeout(5000);
        connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath(), config.toProperties());
        try {
            execute("PRAGMA trusted_schema=OFF");
            int app = pragma("application_id"), version = pragma("user_version");
            if (app == 0 && version == 0 && writable) {
                try (var s = connection.createStatement(); var rs = s.executeQuery("SELECT count(*) FROM sqlite_master")) {
                    if (rs.next() && rs.getInt(1) != 0) throw new SQLException("Refusing to initialize a nonempty foreign database");
                }
                connection.setAutoCommit(false);
                execute("CREATE TABLE captures (id TEXT PRIMARY KEY, name TEXT UNIQUE NOT NULL, query_key TEXT NOT NULL, workload TEXT NOT NULL, sha TEXT NOT NULL, raw BLOB NOT NULL CHECK(length(raw)<=8388608))");
                execute("PRAGMA application_id=" + APPLICATION_ID);
                execute("PRAGMA user_version=1");
                connection.commit();
                connection.setAutoCommit(true);
            } else if (app != APPLICATION_ID || version != 1) throw new SQLException("Unsupported PlanScope database format");
        } catch (SQLException e) { connection.close(); throw e; }
    }

    public Capture add(String name, String queryKey, String workload, byte[] raw) throws IOException, SQLException {
        if (!writable) throw new SQLException("Read-only capture database");
        label(name); label(queryKey); label(workload);
        if (name.matches("[a-f0-9]{64}")) throw new IOException("Capture names cannot look like SHA-256 IDs");
        Plan plan = PlanParser.parse(raw);
        String id = captureId(name, queryKey, workload, plan.sourceSha256());
        try (var check = connection.prepareStatement("SELECT id FROM captures WHERE name=?")) {
            check.setString(1, name);
            try (var rows = check.executeQuery()) {
                if (rows.next()) {
                    if (id.equals(rows.getString(1))) return get(id);
                    throw new IOException("Capture name already exists with different content or context; choose a new name");
                }
            }
        }
        try (var s = connection.prepareStatement("INSERT INTO captures VALUES (?,?,?,?,?,?)")) {
            s.setString(1, id); s.setString(2, name); s.setString(3, queryKey); s.setString(4, workload);
            s.setString(5, plan.sourceSha256()); s.setBytes(6, raw); s.executeUpdate();
        }
        return new Capture(id, name, queryKey, workload, plan);
    }

    public Capture get(String idOrName) throws SQLException, IOException {
        label(idOrName);
        // Names cannot be 64-character hashes, preventing ambiguous ID/name resolution.
        String key = idOrName.matches("[a-f0-9]{64}") ? "id" : "name";
        try (var s = connection.prepareStatement("SELECT id,name,query_key,workload,sha,length(raw) AS bytes,raw FROM captures WHERE " + key + "=?")) {
            s.setString(1, idOrName);
            try (var row = s.executeQuery()) {
                if (!row.next()) throw new IOException("Capture not found");
                validateStoredMetadata(row.getString("id"), row.getString("name"),
                        row.getString("query_key"), row.getString("workload"), row.getString("sha"));
                if (row.getLong("bytes") > PlanParser.MAX_BYTES) throw new IOException("Stored capture exceeds size limit");
                byte[] raw = row.getBytes("raw");
                if (raw == null || !Json.hash(raw).equals(row.getString("sha"))) throw new IOException("Stored capture checksum mismatch");
                Plan plan = PlanParser.parse(raw);
                return new Capture(row.getString("id"), row.getString("name"), row.getString("query_key"), row.getString("workload"), plan);
            }
        }
    }

    public ObjectNode list(int offset, int limit) throws SQLException {
        if (offset < 0 || offset > 1_000_000 || limit < 1 || limit > 50) throw new IllegalArgumentException("Invalid page bounds");
        var result = Json.MAPPER.createObjectNode().put("offset", offset).put("limit", limit);
        var items = result.putArray("captures");
        try (var s = connection.prepareStatement("SELECT id,name,query_key,workload,sha FROM captures ORDER BY name LIMIT ? OFFSET ?")) {
            s.setInt(1, limit + 1); s.setInt(2, offset);
            try (var rows = s.executeQuery()) {
                int count = 0;
                while (rows.next()) {
                    if (count++ == limit) { result.put("nextOffset", offset + limit); break; }
                    try {
                        validateStoredMetadata(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5));
                    } catch (IOException invalid) { throw new SQLException("Stored capture metadata is invalid", invalid); }
                    items.addObject().put("id", rows.getString(1)).put("name", rows.getString(2))
                        .put("queryKey", rows.getString(3)).put("workload", rows.getString(4)).put("sourceSha256", rows.getString(5));
                }
            }
        }
        if (!result.has("nextOffset")) result.putNull("nextOffset");
        return result;
    }

    public static void label(String value) throws IOException {
        if (value == null || value.isBlank() || value.length() > 128 || value.chars().anyMatch(Character::isISOControl))
            throw new IOException("Labels must contain 1..128 characters without control characters");
        Json.validString(value);
    }
    private static String captureId(String name, String queryKey, String workload, String sha) {
        return Json.hash(Json.compact(List.of(name, queryKey, workload, sha)));
    }
    private static void validateStoredMetadata(String id, String name, String queryKey, String workload, String sha) throws IOException {
        label(name); label(queryKey); label(workload);
        if (name.matches("[a-f0-9]{64}") || sha == null || !sha.matches("[a-f0-9]{64}")
                || !captureId(name, queryKey, workload, sha).equals(id))
            throw new IOException("Stored capture identity checksum mismatch");
    }
    private int pragma(String key) throws SQLException {
        try (var s = connection.createStatement(); var rs = s.executeQuery("PRAGMA " + key)) { return rs.next() ? rs.getInt(1) : -1; }
    }
    private void execute(String sql) throws SQLException { try (var s = connection.createStatement()) { s.execute(sql); } }
    @Override public void close() throws SQLException { connection.close(); }
}
