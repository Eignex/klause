"""Corroborate saved total-size return metadata with independent JFR records."""
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import sys

import review_native_vm_live_arguments as original
import review_native_vm_log_identities as log_review


def entry_key(group):
    return group['pid'], group['tid'], group['sequence']


def call_frames(native):
    calls, issues = [], []
    for thread in native['threads']:
        stack = []
        for group in thread:
            if group['errors'] or len(group['primary']) != 1:
                issues.append({'entryKey': entry_key(group), 'reason': 'Incomplete native group'})
                continue
            primary = group['primary'][0]
            kind, category = primary['kind'], primary['type']
            if category == 'ENTRY':
                broker = stack[-1]['brokerEntryKey'] if stack else None
                post = stack[-1]['postEntryKey'] if stack else None
                if kind == 'broker':
                    broker, post = entry_key(group), None
                elif kind == 'post':
                    post = entry_key(group) if broker is not None else None
                frame = {'kind': kind, 'entry': group, 'brokerEntryKey': broker, 'postEntryKey': post}
                stack.append(frame)
                if kind == 'total-size':
                    calls.append(frame)
            elif category == 'RETURN':
                if not stack or stack[-1]['kind'] != kind:
                    issues.append({'entryKey': entry_key(group), 'reason': 'Unmatched or non-LIFO return'})
                else:
                    stack.pop()['return'] = group
        issues.extend({'entryKey': entry_key(frame['entry']), 'kind': frame['kind'],
                       'reason': 'Unfinished native invocation'} for frame in stack)
    return calls, issues


def return_value(group):
    if group['errors'] or len(group['primary']) != 1:
        raise ValueError('Total-size return group is incomplete')
    primary = group['primary'][0]
    values = primary['values']
    if primary['type'] != 'RETURN' or primary['kind'] != 'total-size' or len(values) != 2 or \
            not re.fullmatch(r'[0-9a-fA-F]+', values[0]) or not re.fullmatch(r'[0-9]+', values[1]):
        raise ValueError('Total-size return payload is malformed')
    raw, unsigned = int(values[0], 16), int(values[1])
    if not 0 <= raw < 2 ** 64 or not 0 <= unsigned < 2 ** 31 or raw & 0xffffffff != unsigned:
        raise ValueError('Return low bits differ or the signed source int is unsupported')
    return {'rawRegisterHex': values[0], 'capturedUnsigned32': unsigned, 'sourceIntValue': unsigned}


def correlate(baseline, layout):
    calls, issues = call_frames(baseline['nativeRecords'])
    indexed = {}
    for index, call in enumerate(calls):
        key = call['brokerEntryKey'], call['postEntryKey']
        indexed.setdefault(key, []).append(index)
        call['returnSizeMatchesJfr'] = False
    rows = []
    for broker_index, broker in enumerate(baseline['correlation']['brokerInvocations']):
        posts = broker['posts'] if broker['posts'] else [None]
        for post_index, post in enumerate(posts):
            indices = indexed.get((entry_key(broker['entry']), entry_key(post['entry'])), []) if post else []
            row = {'brokerIndex': broker_index, 'postIndex': post_index if post else None,
                   'callCandidates': indices, 'returnSizeMatchesJfr': False,
                   'originalIndependentIdentityMatched': broker['independentIdentityMatched'],
                   'originalRecordedArgumentBindingCorroborated': broker['recordedArgumentBindingCorroborated']}
            rows.append(row)
            try:
                if not broker['completed'] or not broker['independentIdentityMatched']:
                    raise ValueError('Original complete independent JFR identity is unproved')
                if len(broker.get('jfrMatches', [])) != 1 or broker['jfrMatches'][0]['succeeded'] is not True:
                    raise ValueError('Unique successful original JFR compilation is absent')
                event = broker['jfrMatches'][0]['raw']['values']
                size = event.get('codeSize')
                row['jfrCodeSize'] = size
                if type(size) is not int or not 0 <= size < 2 ** 31:
                    raise ValueError('Independent JFR code size is missing or unsupported')
                if not all(baseline['readDiagnostics'].get(name) is True for name in
                           ['readWarningGuardPassed', 'sequencedDeliveryGuardPassed', 'finalLossCounterZeroProven']) or \
                        baseline['nativeRecords']['errors']:
                    raise ValueError('Original read, sequenced delivery or owned counter guard failed')
                if post is None or 'return' not in post or len(indices) != 1:
                    raise ValueError('Completed post contains no unique total-size invocation')
                call = calls[indices[0]]
                if 'return' not in call or call['entry']['errors']:
                    raise ValueError('Original total-size invocation is unfinished or incomplete')
                begin, end = broker['usdtBegin'], broker['usdtEnd']
                if len(begin) != 1 or len(end) != 1 or not (
                        begin[0]['sequence'] < post['entry']['sequence'] < call['entry']['sequence'] <
                        call['return']['sequence'] < post['return']['sequence'] < end[0]['sequence']):
                    raise ValueError('Size invocation is outside its complete public compilation and post')
                task = original.decode_candidate(broker['entry'], 'task', 'CompileTask', layout)
                post_task = original.decode_candidate(post['entry'], 'task', 'CompileTask', layout)
                post_nmethod = original.decode_candidate(post['entry'], 'nmethod', 'nmethod', layout)
                receiver = original.decode_candidate(call['entry'], 'nmethod', 'nmethod', layout)
                key = original.task_key(task)
                if task['pointer'] != post_task['pointer'] or post_nmethod['pointer'] != receiver['pointer'] or \
                        original.task_key(post_task) != key or original.task_key(post_nmethod, 'nmethod') != key or \
                        original.task_key(receiver, 'nmethod') != key:
                    raise ValueError('Broker task, post publication and size receiver metadata differ')
                value = return_value(call['return'])
                row.update(returnMetadata=value, nativeTaskKey=key)
                if value['sourceIntValue'] != size:
                    raise ValueError('Captured return differs from independent JFR task code size')
                row['returnSizeMatchesJfr'] = True
                call.update(returnSizeMatchesJfr=True, matchedBrokerIndex=broker_index, matchedPostIndex=post_index)
            except (ValueError, KeyError, IndexError, TypeError) as error:
                row['unprovenReason'] = str(error)
    return {'callRecords': calls, 'postAssessments': rows, 'nativeLifetimeIssues': issues,
            'summary': {'totalSizeInvocations': len(calls),
                        'completedTotalSizeInvocations': sum('return' in call for call in calls),
                        'postAssessments': len(rows),
                        'returnSizeJfrMetadataMatches': sum(row['returnSizeMatchesJfr'] for row in rows),
                        'unprovenReasons': dict(Counter(row['unprovenReason'] for row in rows if 'unprovenReason' in row)),
                        'originalCorrelationSummaryCopiedVerbatim': baseline['correlation']['summary'],
                        'scope': 'Saved return/JFR metadata only. Original binding and whole-capture guards remain '
                                 'unchanged. Unobserved, ambiguous, unfinished and outside-post calls remain; no '
                                 'calling convention, complete code-size/root/clock or compiler-saving proof.'}}


def inspect(directory, output, report):
    log_review.inspect(directory, output, report)
    report['completed'] = False
    baseline_path = output.parent / report['originalReview']['path']
    raw = original.read_file(baseline_path, report.setdefault('reproducedReviewFiles', {}))
    if hashlib.sha256(raw).hexdigest() != report['originalReview']['sha256']:
        raise ValueError('Reproduced original review changed before size metadata review')
    layout = json.loads(original.read_file(directory / 'live/live-layout.json', report.setdefault('inputFiles', {})))
    report['returnSizeCorrelation'] = correlate(json.loads(raw), layout)
    report['completed'] = True


def main():
    manifest, expected, archive, output = sys.argv[1:]
    output = Path(output)
    report = {'completed': False, 'diagnosticOnly': True, 'acceptedSavingsCredit': False,
              'actualFullCallingConventionProven': False, 'rootCensusProven': False,
              'codeSizeProven': False, 'clockAlignmentProven': False, 'lossFreeCaptureProven': False,
              'sourceSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'scope': 'Remote saved total-size return/JFR metadata corroboration only. Full original argument '
                       'review and complementary log metadata retained; no private field reconstruction, new '
                       'probe, target/JVM/solver replay, ABI/full-root/clock/code-size or compiler-saving proof.'}
    try:
        directory = output.parent / 'saved-live-inputs'
        report['archiveBinding'] = original.extract_inputs(Path(manifest), expected, Path(archive), directory,
                                                          report.setdefault('inputFiles', {}))
        inspect(directory, output, report)
        report['completed'] = True
    except Exception as error:
        report['completed'] = False
        report['error'] = {'type': type(error).__name__, 'message': str(error)}
        raise
    finally:
        output.write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
