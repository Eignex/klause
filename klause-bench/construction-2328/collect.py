"""Archive remote lab records and derive per-case setup/search/family summaries."""
import gzip
import hashlib
import json
from pathlib import Path
import sys
from urllib.request import urlopen

server = 'http://192.168.50.104:8420'
root = Path(__file__).resolve().parent
for job_id in sys.argv[1:]:
    destination = root / f'lab-{job_id}'
    destination.mkdir(exist_ok=True)
    documents = {}
    for name, endpoint in {
        'job': f'/jobs/{job_id}?json',
        'arms': f'/experiments/{job_id}/arms',
        'cases': f'/experiments/{job_id}/cases',
        'files': f'/jobs/{job_id}/files?json',
    }.items():
        with urlopen(server + endpoint) as response:
            payload = response.read()
        documents[name] = json.loads(payload)
        (destination / f'{name}.json.gz').write_bytes(gzip.compress(payload, mtime=0))
    rows = []
    for case in documents['cases']:
        record = case.get('record')
        if record is None:
            continue
        families = {}
        arms = {}
        for key, value in record['stats'].items():
            if not key.startswith('arm.'):
                continue
            label = key.removeprefix('arm.')
            values = dict(part.split('=', 1) for part in value.split() if '=' in part)
            fields = {field: int(values.get(field, 0)) for field in
                      ('segments', 'work', 'ms', 'initMs', 'initCancelled', 'failures', 'faults')}
            fields['searchAndOverheadMs'] = fields['ms'] - fields['initMs']
            fields['initWork'] = int(values['initWork']) if 'initWork' in values else None
            fields['searchWork'] = fields['work'] - fields['initWork'] if fields['initWork'] is not None else None
            arms[label] = fields
            family = label.split('/')[0]
            totals = families.setdefault(family, {})
            for field, amount in fields.items():
                if amount is not None:
                    totals[field] = totals.get(field, 0) + amount
        rows.append({
            'case': case['index'], 'arm': case['arm'], 'seed': case['seed'], 'repeat': case.get('repeat'),
            'problem': case['problem'], 'status': case['status'],
            'rawRecord': f'{server}/jobs/{job_id}/files/cases/{case["index"]}/record.json',
            'executedCommit': record['gitSha'], 'buildFingerprint': record.get('buildFingerprint'),
            'validationPolicy': record.get('validationPolicy'), 'command': record['command'],
            'budgetMs': record['budgetMs'], 'elapsedMs': record.get('elapsedMs'),
            'feasible': record['feasible'], 'proven': record['proven'], 'objective': record['objective'],
            'timeToFirstFeasibleMs': record['timeToFirstFeasibleMs'], 'timeToBestMs': record['timeToBestMs'],
            'attribution': record['attribution'], 'families': families, 'arms': arms,
        })
    (destination / 'summary.json').write_text(json.dumps(rows, indent=2) + '\n')
    hashes = {file.name: hashlib.sha256(file.read_bytes()).hexdigest()
              for file in sorted(destination.iterdir()) if file.name != 'sha256.json'}
    (destination / 'sha256.json').write_text(json.dumps(hashes, indent=2) + '\n')
