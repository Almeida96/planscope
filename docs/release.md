# PlanScope 0.1.0

PlanScope reviews exported PostgreSQL execution plans without connecting to a database server. This first release includes immutable named SQLite captures, six observation rules, pairwise execution-time comparison, JSON/Markdown/HTML reports and four read-only stdio MCP tools.

The included example records a synthetic 42 ms baseline and 145 ms candidate. It produces three review findings and a regression verdict under the default 10 ms and 20% thresholds. The fixture is a reproducible illustration, not a benchmark.

## Install

Download `planscope-0.1.0.zip` and `SHA256SUMS` from the same release. On Linux:

```sh
sha256sum --check SHA256SUMS
unzip planscope-0.1.0.zip
cd planscope-0.1.0
bin/planscope --version
bin/planscope demo --dir demo
```

On macOS use `shasum -a 256 -c SHA256SUMS`. On PowerShell compare the ZIP hash from `Get-FileHash .\planscope-0.1.0.zip -Algorithm SHA256` with the manifest, extract with `Expand-Archive`, and run `.\bin\planscope.bat` inside the extracted directory.

Java 21 or later is required and is not bundled. Set `JAVA_HOME` to the Java installation directory or make `java` available on `PATH`. No PostgreSQL server or model key is needed to run the demo or inspect existing captures.

## Scope

The tool accepts one exported PostgreSQL JSON statement per capture. It does not execute SQL, prove a root cause, automatically choose an index or establish statistical significance. Raw captures stay in SQLite; report filtering does not remove sensitive text from the database. See [capture guidance](capture-guide.md) and [rule semantics](rules.md).

## Maintainer procedure

Run the checks in [Contributing](../CONTRIBUTING.md) and inspect the generated demo. Keep the version consistent across the build, CLI, report metadata and MCP server. Commit the release as `Release PlanScope 0.1.0` and push it to `main` when ready to publish.

The CI workflow tests Ubuntu, Windows and a real PostgreSQL 18 service before its release job runs. A push whose head commit starts with that release message publishes `v0.1.0` with the verified ZIP and `SHA256SUMS`, using these notes. Ordinary pushes and pull requests run verification without publishing a release. The workflow does not replace an existing tag or release.
