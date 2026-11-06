# Architecture

PlanScope has a Java 21 command-line entry point and a stdio MCP adapter. Both use the same parser, capture store, analysis rules and comparison policy. SQLite stores exported observations; no PostgreSQL connection exists in the application.

## Import and storage

`PlanParser` reads at most 8 MiB, decodes UTF-8 and parses one PostgreSQL statement. Shared JSON handling rejects duplicate keys, trailing documents and invalid Unicode. A bounded walk through `Plans` creates node records with stable, capture-local IDs such as `n0` and pointers into the original JSON.

The normalized model retains node identity and selected numeric fields. SQL text, predicate expressions and output expressions do not enter that model. The original bytes remain in SQLite so the source hash refers to exactly what was imported.

`Store` identifies its schema with SQLite `application_id` and `user_version` values. Imports use parameterized statements and immutable names. An ID hashes the capture name, query key, workload label and source hash; it is not a statement fingerprint. Repeating an identical import is idempotent. The application provides no capture update or deletion command.

Reading a capture verifies its byte hash and reparses it before analysis. Listing returns stored identifiers and labels in name order. Inspection commands and MCP open a read-only SQLite connection. Immutability is an application contract, not filesystem encryption or protection against another program editing the database.

## Analysis and comparison

`Analyzer` produces deterministic findings from the supported counters, with source pointers and evidence maps. Findings are recomputed when requested rather than stored as historical derived results.

`Comparison` first checks explicit context labels and observation availability. Its execution-time verdict uses the configured absolute and relative thresholds. Node-change evidence is a separate pass that requires matching pointers and ancestor identity. See [rules](rules.md) for the formulas and skipped cases.

No query planner, statistical significance test, SQL generator or model provider runs here. The analysis works on one observation or a pair of observations at a time.

## Reports and process boundary

`Reports` renders JSON, Markdown and self-contained HTML. HTML contains escaped values, inline styles and structured evidence, with no scripts or external asset requests. File output uses create-new semantics. The demo creates a new directory and writes its database, synthetic inputs and reports there.

`McpServer` implements newline-delimited JSON-RPC over standard input and output. It supports initialization, ping and four tools. Protocol output stays on stdout; the CLI writes startup errors to stderr. The selected database is fixed at process startup, and tool arguments do not expose file paths or SQL.

The server bounds each incoming message to 256 KiB and exposes paginated inspection results. A read-only tool annotation describes its intent; the read-only database connection and restricted tool implementations enforce the storage behavior.

## Tests and distribution

The Gradle build produces installed launchers and a ZIP with application dependencies, examples and documentation. Java tests exercise the core and protocol behavior. Python smoke tests run the installed CLI and the official MCP client rather than importing Java implementation classes.

CI runs on Ubuntu and Windows with JDK 21. A separate PostgreSQL 18 service job exports real plans and checks their analysis through the installed CLI. The release job consumes the distribution artifact only after both platform jobs and PostgreSQL integration pass. It publishes the ZIP and SHA-256 manifest for the explicit release commit described in [release notes](release.md).
