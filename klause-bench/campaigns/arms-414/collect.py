#!/usr/bin/env python3
"""Archive lab evidence without launching or validating a solve."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent


def fetch(url):
    with urllib.request.urlopen(url, timeout=60) as response:
        return response.read()


def collect(job, server):
    target = ROOT / 'evidence' / str(job)
    target.mkdir(parents=True, exist_ok=True)
    artifacts = []
    for suffix, name in [
        (f'/jobs/{job}', 'job'),
        (f'/experiments/{job}/arms', 'arms'),
        (f'/experiments/{job}/cases', 'cases'),
        (f'/experiments/{job}/stats', 'lab-stats'),
        (f'/experiments/{job}/reference', 'reference-comparison'),
        (f'/jobs/{job}/files', 'files'),
    ]:
        url = server + suffix
        try:
            data = fetch(url)
        except urllib.error.HTTPError as error:
            artifacts.append(dict(url=url, error=str(error)))
            continue
        name += '.json.gz'
        (target / name).write_bytes(gzip.compress(data, mtime=0))
        artifacts.append(dict(file=name, url=url, sha256=hashlib.sha256(data).hexdigest()))
    listing = json.loads(gzip.decompress((target / 'files.json.gz').read_bytes()))
    for item in listing:
        if item['path'] not in ['job.log', 'setup.log', 'aws-instances']:
            continue
        data = fetch(f"{server}/jobs/{job}/files/{item['path']}")
        (target / item['path']).write_bytes(data)
        artifacts.append(dict(file=item['path'], sha256=hashlib.sha256(data).hexdigest()))
    # Raw solver streams and installed/runtime manifests remain retrievable at these exact paths.
    manifest = dict(job=job, page=f'{server}/jobs/{job}', artifacts=artifacts,
                    raw_files=[dict(path=x['path'], bytes=x['bytes'],
                                    url=f"{server}/jobs/{job}/files/{x['path']}") for x in listing])
    (target / 'retrieval.json').write_text(json.dumps(manifest, indent=2) + '\n')
    cases = json.loads(gzip.decompress((target / 'cases.json.gz').read_bytes()))
    print(job, len(cases), 'planned;', sum(x.get('record') is not None for x in cases), 'records')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('jobs', type=int, nargs='+')
    parser.add_argument('--server', default='http://192.168.50.104:8420')
    args = parser.parse_args()
    for job in args.jobs:
        collect(job, args.server.rstrip('/'))
