"""Summarize AWS JFR samples before and after the nominal budget in CLI uptime."""

import collections
import datetime
import hashlib
import json
import pathlib
import subprocess
import sys


def instant(value):
    if isinstance(value, (int, float)):
        return datetime.datetime.fromtimestamp(value / 1000, datetime.timezone.utc)
    return datetime.datetime.fromisoformat(value.replace('Z', '+00:00'))


def summarize(root):
    for path in sorted(root.glob('cases/*/profile-*/cli.jfr')):
        record_bytes = (path.parent / 'solve-record.json').read_bytes()
        record = json.loads(record_bytes)
        manifest = json.loads((path.parent / 'measurement.json').read_text())
        if hashlib.sha256(record_bytes).hexdigest() != manifest['recordSha256']:
            raise ValueError('record differs from measurement manifest')
        events = json.loads(subprocess.check_output([
            'jfr', 'print', '--json', '--events',
            'jdk.JVMInformation,jdk.ExecutionSample', str(path),
        ]))['recording']['events']
        jvm = next(event['values'] for event in events if event['type'] == 'jdk.JVMInformation')
        start = instant(jvm['jvmStartTime'])
        windows = {}
        for label in ['beforeBudget', 'atOrAfterBudget']:
            selected = [event['values'] for event in events if event['type'] == 'jdk.ExecutionSample'
                        and ((instant(event['values']['startTime']) - start).total_seconds() * 1000
                             >= record['budgetMs']) == (label == 'atOrAfterBudget')]
            leaf, inclusive, threads, depths = (collections.Counter() for _ in range(4))
            truncated = 0
            uptimes = []
            for values in selected:
                trace = values.get('stackTrace') or {}
                frames = trace.get('frames', [])
                names = [frame['method']['type']['name'].replace('/', '.') + '.' +
                         frame['method']['name'] for frame in frames]
                if names:
                    leaf[names[0]] += 1
                inclusive.update(set(names))
                thread = values.get('sampledThread') or {}
                threads[thread.get('javaName', thread.get('osName', 'unknown'))] += 1
                depths[len(names)] += 1
                truncated += bool(trace.get('truncated'))
                uptimes.append((instant(values['startTime']) - start).total_seconds() * 1000)
            windows[label] = {
                'samples': len(selected), 'threads': dict(threads), 'stackDepths': dict(depths),
                'truncatedStacks': truncated,
                'firstUptimeMs': min(uptimes, default=None), 'lastUptimeMs': max(uptimes, default=None),
                'leaf': leaf.most_common(20),
                'inclusiveKlause': [(name, count) for name, count in inclusive.most_common()
                                    if name.startswith('com.eignex.klause')][:30],
            }
        yield {'problem': record['problem'], 'measurementId': manifest['measurementId'],
               'budgetMs': record['budgetMs'], 'elapsedMs': record['elapsedMs'],
               'presolvePreparationMs': record['stats'].get('presolvePreparationMs'),
               'clock': 'CLI JVM uptime; its nominal-budget boundary is not an exact presolve deadline timestamp.',
               'windows': windows}


if __name__ == '__main__':
    print(json.dumps(list(summarize(pathlib.Path(sys.argv[1]))), indent=2, allow_nan=False))
