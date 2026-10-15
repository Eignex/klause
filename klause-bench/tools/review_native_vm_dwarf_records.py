"""Review saved bound-file DWARF candidates without loading a native file."""
import hashlib
import json
from pathlib import Path
import sys

READER_SHA256 = '248980a0d50104ddb8293f88ef2e00fdce91a8cb8d9b976c9ab8e36485e16ede'
MAX_INPUT_BYTES = 8 * 1024 * 1024
MAX_GRAPH = 16384
MAX_SELECTED = 2048
MAX_CHAIN = 32
MAX_REVIEW_BYTES = 32 * 1024 * 1024
QUALIFIERS = {'DW_TAG_typedef', 'DW_TAG_const_type', 'DW_TAG_volatile_type', 'DW_TAG_restrict_type'}
STORAGE = {'DW_TAG_base_type', 'DW_TAG_enumeration_type', 'DW_TAG_pointer_type',
           'DW_TAG_reference_type', 'DW_TAG_rvalue_reference_type'}
OWNERS = {'CompileTask', 'nmethod', 'CodeBlob', 'CompilerThread'}
TASK_FIELDS = ('_compile_id', '_osr_bci', '_comp_level', '_is_complete', '_is_success',
               '_nm_content_size', '_nm_total_size', '_nm_insts_size')
CONSTANT_FORMS = {'DW_FORM_data1', 'DW_FORM_data2', 'DW_FORM_data4', 'DW_FORM_data8',
                  'DW_FORM_udata', 'DW_FORM_sdata', 'DW_FORM_implicit_const'}


def scalar(record, name):
    attribute = record['attributes'].get(name)
    if attribute is None or attribute['form'] not in CONSTANT_FORMS or type(attribute['value']) is not int:
        return None
    return attribute['value']


def text_name(record):
    attribute = record['attributes'].get('DW_AT_name')
    if attribute is None:
        return None
    value = attribute['value']
    if not isinstance(value, dict) or set(value) != {'bytesHex'}:
        return None
    return bytes.fromhex(value['bytesHex']).decode('utf-8', errors='backslashreplace')


def storage(identity, graph):
    chain = []
    while identity is not None:
        if identity in chain or len(chain) >= MAX_CHAIN:
            return {'status': 'cyclic-or-oversized-type-chain', 'chain': chain, 'next': identity}
        chain.append(identity)
        node = graph.get(identity)
        if node is None:
            return {'status': 'missing-type-record', 'chain': chain}
        if node['referenceErrors']:
            return {'status': 'unresolved-type-reference', 'chain': chain,
                    'referenceErrors': node['referenceErrors']}
        if node['tag'] in QUALIFIERS:
            identity = node['references'].get('DW_AT_type')
            if identity is None:
                return {'status': 'missing-qualified-type', 'chain': chain}
            continue
        width = scalar(node, 'DW_AT_byte_size')
        if node['tag'] not in STORAGE or width is None or not 0 < width <= 1024:
            return {'status': 'unsupported-or-missing-storage', 'chain': chain, 'tag': node['tag']}
        return {'status': 'constant-storage-candidate', 'chain': chain, 'tag': node['tag'],
                'byteSize': width, 'encoding': scalar(node, 'DW_AT_encoding'),
                'name': text_name(node)}
    return {'status': 'missing-member-type', 'chain': chain}


def member(identity, graph, owner_size):
    node = graph[identity]
    result = {'die': identity, 'name': text_name(node), 'tag': node['tag'],
              'attributes': node['attributes'], 'references': node['references'],
              'referenceErrors': node['referenceErrors'], 'status': 'unsupported-member'}
    if node['tag'] != 'DW_TAG_member':
        return result
    result['storage'] = storage(node['references'].get('DW_AT_type'), graph)
    if {'DW_AT_bit_size', 'DW_AT_bit_offset', 'DW_AT_data_bit_offset'} & node['attributes'].keys():
        result['status'] = 'bit-field-unresolved'
        return result
    offset = scalar(node, 'DW_AT_data_member_location')
    if offset is None:
        result['status'] = 'location-unresolved'
        return result
    result['constantOffset'] = offset
    if node['referenceErrors'] or result['storage']['status'] != 'constant-storage-candidate':
        result['status'] = 'type-unresolved'
    elif owner_size is None or offset < 0 or offset + result['storage']['byteSize'] > owner_size:
        result['status'] = 'owner-bounds-unresolved'
    else:
        result['status'] = 'constant-member-candidate'
    return result


def review(source, report):
    dwarf = source['dwarf']
    graph, selected = dwarf['graph'], dwarf['selectedRecords']
    if not isinstance(graph, dict) or len(graph) > MAX_GRAPH or not isinstance(selected, list) or \
            len(selected) > MAX_SELECTED or any(not isinstance(node, dict) for node in graph.values()):
        raise ValueError('Saved DWARF graph is incomplete or exceeds bounds')
    report.update(typeCandidates=[], functionCandidates=[], taskFieldConcordance={},
                  originalRequestedUnitCoverage=dwarf['requestedUnitCoverage'],
                  originalNameErrors=dwarf['nameErrors'])
    retained = 0

    def retain(row):
        nonlocal retained
        retained += 256
        for chunk in json.JSONEncoder().iterencode(row):
            retained += len(chunk.encode('utf-8'))
            if retained > MAX_REVIEW_BYTES:
                raise ValueError('Saved DWARF candidate review exceeds output bounds')
        report['retainedReviewBytes'] = retained
        return row

    retain({'coverage': report['originalRequestedUnitCoverage'], 'errors': report['originalNameErrors']})
    for candidate in selected:
        identity = candidate['die']
        node = graph[identity]
        if len(candidate['children']) > 512 or len(candidate['nameCandidates']) > 64:
            raise ValueError('Saved selected record exceeds bounds')
        names = [bytes.fromhex(row['nameHex']).decode('utf-8', errors='backslashreplace')
                 for row in candidate['nameCandidates']]
        row = {'originalSelectedRecord': candidate, 'record': node, 'names': names}
        if node['tag'] in {'DW_TAG_class_type', 'DW_TAG_structure_type'}:
            if not any(name in OWNERS for name in names):
                raise ValueError('Saved type selection differs from supported owners')
            declaration = node['attributes'].get('DW_AT_declaration', {}).get('value')
            size = scalar(node, 'DW_AT_byte_size')
            if size is not None and not 0 < size <= 1024 * 1024:
                size = None
            declaration_status = 'definition' if declaration is None or declaration in (False, 0) else \
                'declaration' if declaration in (True, 1) else 'unknown'
            row.update(declaration=declaration, declarationStatus=declaration_status, byteSizeCandidate=size,
                       ownerNameStatus='single-owner' if len(set(names)) == 1 else 'ambiguous-owner',
                       members=[member(child, graph, size) for child in candidate['children']])
            report['typeCandidates'].append(retain(row))
        elif node['tag'] in {'DW_TAG_subprogram', 'DW_TAG_inlined_subroutine'}:
            parameters = []
            for child in candidate['children']:
                parameter = graph[child]
                if parameter['tag'] == 'DW_TAG_formal_parameter':
                    parameters.append({'die': child, 'record': parameter,
                                       'storage': storage(parameter['references'].get('DW_AT_type'), graph),
                                       'locationStatus': 'not-evaluated'})
            row.update(parameters=parameters, callingConventionStatus='unproved',
                       rangesStatus='not-evaluated', locationStatus='not-evaluated')
            report['functionCandidates'].append(retain(row))
        else:
            raise ValueError('Saved selected tag is unsupported')
    definitions = [row for row in report['typeCandidates'] if 'CompileTask' in row['names'] and
                   row['declarationStatus'] != 'declaration']
    for name in TASK_FIELDS:
        observations = []
        signatures = []
        for row in definitions:
            matches = [item for item in row['members'] if item['name'] == name]
            observations.append({'ownerDie': row['originalSelectedRecord']['die'], 'members': matches})
            if row['declarationStatus'] != 'definition' or row['ownerNameStatus'] != 'single-owner' or \
                    row['record']['referenceErrors'] or \
                    len(matches) != 1 or matches[0]['status'] != 'constant-member-candidate':
                signatures.append(None)
                continue
            item = matches[0]
            signatures.append((row['byteSizeCandidate'], item['constantOffset'],
                               item['storage']['byteSize'], item['storage']['tag'],
                               item['storage']['encoding'], item['storage']['name']))
        status = 'no-concrete-definitions' if not definitions else 'incomplete-or-conflicting'
        if signatures and signatures[0] is not None and all(item == signatures[0] for item in signatures):
            status = 'consistent-dwarf-candidates'
        report['taskFieldConcordance'][name] = retain({'status': status, 'definitions': observations,
                                                      'actualFieldLayoutProven': False})


def inspect(captured, expected_capture, expected_producer, output, report):
    if captured.stat().st_size > MAX_INPUT_BYTES:
        raise ValueError('Saved DWARF report exceeds bounds')
    raw = captured.read_bytes()
    if len(raw) > MAX_INPUT_BYTES or hashlib.sha256(raw).hexdigest() != expected_capture:
        raise ValueError('Saved DWARF report hash differs')
    if expected_producer != READER_SHA256:
        raise ValueError('Saved DWARF producer is unsupported')
    source = json.loads(raw)
    if source.get('sourceSha256') != expected_producer or source.get('completed') is not True or \
            source.get('diagnosticOnly') is not True or source.get('acceptedSavingsCredit') is not False or \
            source['fileBinding'].get('externalDebugFileBound') is not True or \
            source['dwarf'].get('selectedUnitTraversalCompleted') is not True or \
            source['dwarf'].get('locationExpressionsEvaluated') is not False:
        raise ValueError('Saved bound-file inventory is incomplete or changes proof scope')
    for flag in ['actualFieldLayoutProven', 'actualTargetCallingConventionProven',
                 'actualControlFlowCoverageProven', 'rootCensusProven', 'codeSizeProven']:
        if source.get(flag) is not False:
            raise ValueError('Saved inventory changes original proof scope')
    (output / 'captured-vm-dwarf-records.json').write_bytes(raw)
    report.update(capturedSha256=expected_capture, originalProducerSha256=expected_producer)
    review(source, report)
    report['completed'] = True


def main():
    captured, expected_capture, expected_producer, output = sys.argv[1:]
    output = Path(output)
    report = {'completed': False, 'diagnosticOnly': True, 'acceptedSavingsCredit': False,
              'sourceSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'actualFieldLayoutProven': False, 'actualTargetCallingConventionProven': False,
              'actualControlFlowCoverageProven': False, 'rootCensusProven': False, 'codeSizeProven': False,
              'scope': 'Remote saved DWARF candidate review only. All selected type/function/inline records '
                       'and original bytes retained. Constant member/type candidates do not prove source '
                       'layout or entry ABI. No inheritance expansion, range/location evaluation, target '
                       'loading/invocation, private field read, root/clock/code-size/runtime/compiler proof.'}
    try:
        inspect(Path(captured), expected_capture, expected_producer, output.parent, report)
    except Exception as error:
        report['error'] = {'type': type(error).__name__, 'message': str(error)}
        raise
    finally:
        output.write_text(json.dumps(report, separators=(',', ':')) + '\n')


if __name__ == '__main__':
    main()
