"""Inspect saved rejected counter-map BTF without attaching probes or running the target."""
import hashlib
import json
from pathlib import Path
import sys

import native_vm_event_loss as loss
import review_native_vm_live_arguments as saved

INPUTS = ('live/observer/event-loss.stdout', 'live/observer/event-loss.stderr',
          'live/observer/observer-launch.json', 'live/observer/appimage-extraction.json', 'native_vm_event_loss.py')


def inspect(directory, report):
    files = report.setdefault('inputFiles', {})
    load = lambda name: saved.read_file(directory / name, files)
    lines = load(INPUTS[0]).decode('utf-8').splitlines()
    if len(lines) != 1 or not lines[0].startswith('BIND_FAILURE\t'):
        raise ValueError('Saved counter-map binding failure is absent or ambiguous')
    failure = json.loads(lines[0].split('\t', 1)[1])
    report['originalFailureCopiedVerbatim'] = failure
    report['originalHelperStderr'] = load(INPUTS[1]).decode('utf-8')
    report['originalLaunch'] = json.loads(load(INPUTS[2]))
    report['originalExtraction'] = json.loads(load(INPUTS[3]))
    report['capturedHelperSha256'] = hashlib.sha256(load(INPUTS[4])).hexdigest()
    raw_by_digest = {}
    inspections = failure['inspections']
    if not isinstance(inspections, list) or len(inspections) > 8192:
        raise ValueError('Saved map inspection inventory exceeds bounds')
    for row in inspections:
        if 'btfRawHex' in row:
            raw = bytes.fromhex(row['btfRawHex'])
            if not 0 < len(raw) <= loss.MAX_BTF_BYTES or hashlib.sha256(raw).hexdigest() != row['btfSha256']:
                raise ValueError('Saved raw map BTF digest differs')
            raw_by_digest[row['btfSha256']] = raw
    diagnostics = []
    for row in inspections:
        if row.get('eligibleArray') is not True:
            continue
        result = {key: value for key, value in row.items() if key != 'btfRawHex'}
        raw = raw_by_digest.get(row.get('btfSha256'))
        if raw is None:
            result['diagnosticError'] = 'Original raw BTF is unavailable'
        else:
            try:
                result['typeGraph'] = loss.btf_type_graph(raw, row['map']['btf_value_type_id'])
            except (ValueError, KeyError, TypeError) as error:
                result['diagnosticError'] = str(error)
        diagnostics.append(result)
    report.update(mapDiagnostics=diagnostics, completed=True)


def main():
    manifest, expected, archive, output = sys.argv[1:]
    output = Path(output)
    report = {'completed': False, 'diagnosticOnly': True, 'acceptedSavingsCredit': False,
              'actualFullCallingConventionProven': False, 'rootCensusProven': False,
              'codeSizeProven': False, 'clockAlignmentProven': False, 'lossFreeCaptureProven': False,
              'finalLossCounterProven': False, 'finalLossCounterZeroProven': False,
              'sourceSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'btfReaderSourceSha256': hashlib.sha256(Path(loss.__file__).read_bytes()).hexdigest(),
              'scope': 'Remote saved rejected-map type metadata only. Original guards and failure remain unchanged; no map values, target/probe/workload replay, private-offset inference or compiler acceptance.'}
    try:
        directory = output.parent / 'saved-loss-inputs'
        report['archiveBinding'] = saved.extract_inputs(Path(manifest), expected, Path(archive), directory,
                                                        report.setdefault('inputFiles', {}), required_inputs=INPUTS)
        inspect(directory, report)
    except Exception as error:
        report['error'] = {'type': type(error).__name__, 'message': str(error)}
        raise
    finally:
        output.write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
