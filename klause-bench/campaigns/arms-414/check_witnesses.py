#!/usr/bin/env python3
"""Run independent MiniZinc pinned-source checks in GitHub CI, outside campaign timings."""
import argparse
import gzip
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent


def check(bench, jobs, output):
    results = []
    seen = set()
    for job in jobs:
        folder = ROOT / 'evidence' / str(job)
        cases = json.loads(gzip.decompress((folder / 'cases.json.gz').read_bytes()))
        raw = json.loads(gzip.decompress((folder / 'raw-records.json.gz').read_bytes()))
        for case in cases:
            rec = raw.get(str(case['index']), {})
            if case['problem']['format'] != 'MINIZINC' or rec.get('feasible') is not True:
                continue
            witness = rec.get('finalWitness')
            if witness is None:
                results.append(dict(job=job, case=case['index'], status='missing', reason='legacy record has no witness'))
                continue
            identity = (case['problem']['problem'], witness)
            if identity in seen:
                continue
            seen.add(identity)
            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / 'candidate.out'
                path.write_text(witness + '\n----------\n')
                command = [str(bench), 'validate-solution', 'suite=' + case['problem']['suite'],
                           'problem=' + case['problem']['problem'], 'output=' + str(path)]
                run = subprocess.run(command, text=True, capture_output=True, timeout=180)
                try:
                    verdict = json.loads(run.stdout.strip().splitlines()[-1])
                    if verdict.get('sourceHashes') != rec.get('sourceHashes'):
                        verdict = dict(status='error', reason='source bytes differ from measured inputs')
                except (ValueError, IndexError):
                    verdict = dict(status='error', reason=(run.stderr or run.stdout)[-2000:])
            results.append(dict(job=job, case=case['index'], input=case['problem']['problem'],
                                objective=rec.get('objective'), sourceHashes=rec.get('sourceHashes'), **verdict))
    output.write_text(json.dumps(results, indent=2) + '\n')
    print(json.dumps(dict(checked=len(results), statuses={s: sum(x['status'] == s for x in results)
                                                        for s in {x['status'] for x in results}})))
    if any(x['status'] in ['invalid', 'error'] for x in results):
        raise SystemExit(1)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bench', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('jobs', nargs='+', type=int)
    args = parser.parse_args()
    check(args.bench.resolve(), args.jobs, args.output)
