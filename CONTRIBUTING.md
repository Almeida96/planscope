# Contributing

PlanScope turns exported plans into inspectable observations. Changes should preserve the distinction between a measured field, a heuristic finding and a conclusion that needs more evidence.

## Run the checks

Use JDK 21 and Python 3.12. From the repository root:

```sh
./gradlew test installDist distZip --no-daemon --console=plain
python checks/cli_smoke.py build/install/planscope
python -m pip install 'mcp==1.26.0'
python checks/mcp_smoke.py --install-dir build/install/planscope
```

On Windows use `.\gradlew.bat`; the Python smoke tests select the Windows launcher. Set `JAVA_HOME` to the JDK directory. Use an isolated Python environment for the MCP dependency. The wrapper and dependency locks are checked in; dependency changes are reviewed manually.

To inspect the generated output:

```sh
build/install/planscope/bin/planscope demo --dir build/review-demo
```

Open `build/review-demo/comparison.html`. Use a new output directory for each run. Generated databases, captures from real systems and build outputs do not belong in a source change.

## Submit a change

For a behavioral fix, include an input that reproduces the problem and a regression test that checks the public result. Explain which observation was wrong and why the new interpretation is valid. Use small synthetic plans; remove real SQL, identifiers and literals from shared examples.

For a new rule, document its exact thresholds, minimum observations, skipped cases and likely false positives. Preserve JSON pointers to the input and report the numeric evidence. Do not infer exclusive node time by subtracting or summing arbitrary tree metrics. Do not turn a plan shape or a sequential scan into an automatic failure.

Parser changes must keep explicit size/depth bounds, strict numeric validation and duplicate-key rejection. Store changes must retain immutable capture names, source-byte checksum verification and read-only connections for inspection. MCP changes need both protocol tests and the official client smoke test; tools must not accept SQL, arbitrary paths or write operations.

Update the README, relevant guide and changelog when commands or interpretation change. Report the checks you ran and any platform you could not test. See [release notes](docs/release.md) for the release workflow.
