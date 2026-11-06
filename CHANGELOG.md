# Changelog

## 0.1.0

- Import one PostgreSQL `FORMAT JSON` statement as an immutable named SQLite capture, with a source SHA-256 hash and explicit query/workload labels.
- Review row estimates, filtered rows, disk sorts, hash batches, repeated work and index-only heap fetches using source pointers and numeric evidence.
- Compare observed execution times with absolute and relative thresholds, and report incompatible captures as inconclusive.
- Export JSON, Markdown and standalone HTML reports.
- Expose four read-only stdio MCP tools for listing, analysis, node inspection and comparison.
- Include a synthetic 42 ms to 145 ms comparison, Java tests, installed-CLI checks and an official MCP-client smoke test.
