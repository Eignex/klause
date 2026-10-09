#!/usr/bin/env python3
"""Archive remote lab observations; never build or execute a solver."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
from urllib.request import urlopen

parser = argparse.ArgumentParser()
parser.add_argument('job', type=int)
parser.add_argument('--url', default='http://192.168.50.104:8420')
parser.add_argument('--out', type=Path, default=Path(__file__).parent / 'evidence')
args = parser.parse_args()
out = args.out / str(args.job)
out.mkdir(parents=True, exist_ok=True)
manifest = []
for name, route in {
    'job.json': f'/jobs/{args.job}',
    'cases.json': f'/experiments/{args.job}/cases',
    'arms.json': f'/experiments/{args.job}/arms',
    'stats.json': f'/experiments/{args.job}/stats',
    'reference.json': f'/experiments/{args.job}/reference',
    'files.json': f'/jobs/{args.job}/files',
    'job.log': f'/jobs/{args.job}/files/job.log',
}.items():
    url = args.url.rstrip('/') + route
    try:
        with urlopen(url, timeout=60) as response:
            raw = response.read()
    except Exception as error:
        manifest.append({'url': url, 'error': str(error)})
        continue
    (out / (name + '.gz')).write_bytes(gzip.compress(raw, mtime=0))
    manifest.append({'url': url, 'file': name + '.gz', 'bytes': len(raw),
                     'sha256': hashlib.sha256(raw).hexdigest()})
(out / 'retrieval.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(out)
