"""Exercise the installed distribution, exit codes, persistence and report boundaries."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

installation = Path(sys.argv[1]).resolve()
executable = installation / 'bin' / ('planscope.bat' if os.name == 'nt' else 'planscope')


def run(*args, status=0):
    process = subprocess.run([str(executable), *map(str, args)], capture_output=True,
                             text=True, encoding='utf-8', timeout=30)
    assert process.returncode == status, (args, process.returncode, process.stdout, process.stderr)
    return process


with tempfile.TemporaryDirectory(prefix='planscope-cli-') as temp:
    root = Path(temp)
    demo = root / 'demo'
    assert run('--version').stdout.strip() == 'planscope 0.1.0'
    output = json.loads(run('demo', '--dir', demo).stdout)
    assert output['status'] == 'REGRESSION'
    report = json.loads((demo / 'comparison.json').read_text())
    assert report['execution']['deltaMs'] == 103
    assert len(report['candidateFindings']) == 3
    assert {f['rule'] for f in report['candidateFindings']} == {'SORT_DISK', 'ROW_ESTIMATE', 'FILTER_WASTE'}
    db = demo / 'plans.db'
    before = hashlib.sha256(db.read_bytes()).hexdigest()
    captures = json.loads(run('list', '--db', db, '--limit', 1).stdout)
    assert len(captures['captures']) == 1 and captures['nextOffset'] == 1
    analysis = json.loads(run('analyze', '--db', db, '--plan', 'candidate').stdout)
    assert analysis['capture']['executionMs'] == 145
    run('compare', '--db', db, '--baseline', 'baseline', '--candidate', 'candidate', status=2)
    run('compare', '--db', db, '--baseline', 'candidate', '--candidate', 'baseline')
    run('compare', '--db', db, '--baseline', 'baseline', '--candidate', 'baseline', status=3)
    run('compare', '--db', db, '--baseline', 'baseline', '--candidate', 'candidate', '--max-increase-ms', 'NaN', status=1)
    run('analyze', '--db', db, '--plan', 'candidate', '--format', 'html', '--out', demo / 'comparison.html', status=1)
    run('analyze', '--db', db, '--plan', 'candidate', '--format', 'md', '--out', root / 'analysis.md')
    assert 'FILTER\\_WASTE' in (root / 'analysis.md').read_text()
    run('list', '--db', db, '--limit', '1.5', status=1)
    run('list', '--db', db, '--db', db, status=1)
    run('list', '--db', root / 'missing.db', status=1)
    assert not (root / 'missing.db').exists()
    run('analyze', '--db', db, '--plan', 'absent', status=1)
    assert hashlib.sha256(db.read_bytes()).hexdigest() == before
    # Exact repeats are idempotent; changing context under a used name is an error.
    run('import', '--db', db, '--file', demo / 'candidate.json', '--name', 'candidate',
        '--query', 'orders-by-account', '--workload', 'synthetic-demo-v1')
    run('import', '--db', db, '--file', demo / 'candidate.json', '--name', 'candidate',
        '--query', 'different', '--workload', 'synthetic-demo-v1', status=1)
    original = (demo / 'comparison.html').read_bytes()
    run('demo', '--dir', demo, status=1)
    assert (demo / 'comparison.html').read_bytes() == original
    malformed = root / 'bad.json'
    malformed.write_text('[{"Plan":{"Node Type":"Result","Actual Loops":1,"Actual Rows":null}}]')
    run('import', '--db', root / 'bad.db', '--file', malformed, '--name', 'bad', '--query', 'q', '--workload', 'w', status=1)
    assert not (root / 'bad.db').exists()
print('Installed CLI: demo, 3 findings, comparisons, immutable imports, read-only queries and failure paths passed.')
