"""Associate pinned source-signature records with retained executable entries."""
import hashlib
import json
import os
from pathlib import Path
import sys

import read_native_vm_entries as entries
import read_native_vm_signatures as signatures
from native_vm_loaded_module import digest

MAX_INPUT_BYTES = 2 * 1024 * 1024
MAX_ASSOCIATIONS = 4096


def capture(path, expected, destination, producer):
    if path.stat().st_size > MAX_INPUT_BYTES:
        raise ValueError('Captured report exceeds diagnostic bounds')
    raw = path.read_bytes()
    if len(raw) > MAX_INPUT_BYTES or hashlib.sha256(raw).hexdigest() != expected:
        raise ValueError('Captured report hash differs')
    if destination.exists() and destination.read_bytes() != raw:
        raise ValueError('Retained input differs from the captured report')
    destination.write_bytes(raw)
    data = json.loads(raw)
    if data.get('completed') is not True or data.get('diagnosticOnly') is not True or \
            data.get('acceptedSavingsCredit') is not False or \
            data.get('actualTargetCallingConventionProven') is not False or \
            data.get('rootCensusProven', False) is not False:
        raise ValueError('Captured report is incomplete or has a different claim scope')
    if data.get('sourceSha256') != digest(Path(producer.__file__)):
        raise ValueError('Captured report producer differs from the reviewed reader')
    return data


def associate(stream, file_size, query, source_matches, recorded_entries, sections, programs, complete_tables, budget=None):
    result = {'expectedSourceSignature': signatures.SIGNATURES.get(query), 'associations': [],
              'uniquePhysicalEntryProven': False, 'status': 'no exact source definition retained'}
    if result['expectedSourceSignature'] is None:
        result['status'] = 'no pinned source declaration contract'
    physical = {}
    missing = False
    budget = [0] if budget is None else budget
    for source_index, item in enumerate(source_matches):
        symbol = item['originalSymbolRecord']
        if item.get('exactSourceSignatureMatched') is not True or \
                item.get('expectedSourceSignature') != result['expectedSourceSignature'] or \
                item.get('demanglerResult', {}).get('demangled') != result['expectedSourceSignature'] or \
                result['expectedSourceSignature'] is None or symbol.get('function') is not True or \
                symbol.get('undefined') is not False or symbol.get('ordinarySectionDefinition') is not True:
            raise ValueError('Retained source match contradicts the exact declaration contract')
        for key in ['sectionIndex', 'value', 'size']:
            if type(symbol.get(key)) is not int or symbol[key] < 0:
                raise ValueError('Source symbol dimensions are not nonnegative integers')
        if symbol['sectionIndex'] >= len(sections):
            raise ValueError('Source symbol section is outside the captured table')
        # The full record binds the two captures without conflating equal names or local aliases.
        record_key = json.dumps(symbol, sort_keys=True, separators=(',', ':'))
        matches = [(index, entry) for index, entry in enumerate(recorded_entries)
                   if json.dumps(entry.get('originalSymbol'), sort_keys=True, separators=(',', ':')) == record_key]
        association = {'sourceMatchIndex': source_index, 'entryRecordIndices': [index for index, _ in matches],
                       'byteChecks': [], 'status': 'entry record absent'}
        result['associations'].append(association)
        if not matches:
            missing = True
        for entry_index, entry in matches:
            budget[0] += 1
            if budget[0] > MAX_ASSOCIATIONS:
                raise ValueError('Source-entry association pairs exceed diagnostic bounds')
            if entry.get('completed') is not True or 'error' in entry:
                association['status'] = 'entry inspection incomplete'
                missing = True
                continue
            checked = {}
            raw = entries.entry_bytes(stream, file_size, symbol, sections, programs, checked)
            if raw is None:
                association['status'] = 'source definition has no retained executable bytes'
                missing = True
                continue
            if any(entry.get(key) != value for key, value in checked.items()):
                raise ValueError('Captured entry bytes or ownership differ from the pinned library')
            association['byteChecks'].append({'entryRecordIndex': entry_index,
                'fileOffset': checked['fileOffset'], 'entryBytes': checked['entryBytes'],
                'entrySha256': checked['entrySha256'], 'byteCoverage': checked['byteCoverage'],
                'truncated': checked['truncated']})
            association['status'] = 'exact source record and executable bytes associated'
            key = (symbol['value'], symbol['size'], checked['fileOffset'], checked['entrySha256'])
            definition = physical.setdefault(key, {'address': symbol['value'], 'declaredBytes': symbol['size'],
                'fileOffset': checked['fileOffset'], 'entrySha256': checked['entrySha256'],
                'sourceAndEntryRecords': []})
            definition['sourceAndEntryRecords'].append({'sourceMatchIndex': source_index, 'entryRecordIndex': entry_index})
    result['physicalDefinitions'] = list(physical.values())
    if source_matches:
        if not complete_tables:
            result['status'] = 'partial symbol-table coverage'
        elif missing:
            result['status'] = 'source or entry association incomplete'
        elif len(physical) != 1:
            result['status'] = 'physical definition absent or ambiguous'
        else:
            result.update(status='one physical definition in the captured source and entry records',
                          uniquePhysicalEntryProven=True)
    return result


def inspect(library, expected_library, entry_path, expected_entries, signature_path, expected_signatures, out, report):
    with library.open('rb') as stream:
        before = os.fstat(stream.fileno())
        actual = hashlib.file_digest(stream, 'sha256').hexdigest()
        report.update(librarySha256=actual, fileBytes=before.st_size)
        if actual != expected_library or entries.symbols.identity(before) != entries.symbols.identity(library.stat()):
            raise ValueError('Pinned library identity differs before association')
        entry_data = capture(entry_path, expected_entries, out / 'captured-vm-entries.json', entries)
        signature_data = capture(signature_path, expected_signatures, out / 'captured-vm-signatures.json', signatures)
        if entry_data.get('librarySha256') != expected_library or \
                entry_data.get('librarySha256After') != expected_library or \
                entry_data.get('expectedLibrarySha256') != expected_library or \
                signature_data.get('librarySha256') != expected_library or \
                signature_data.get('expectedLibrarySha256') != expected_library:
            raise ValueError('Captured reports belong to a different pinned library')
        queries = entries.symbols.query_names(tuple(entry_data['queries']))
        captured_symbols = entry_data['symbols']
        if not all(set(rows) == set(queries) for rows in [entry_data['entries'], captured_symbols['candidates'],
                   signature_data['candidates']]) or \
                set(signature_data['matchedSourceSignatures']) != {query for query in queries
                                                                  if query in signatures.SIGNATURES}:
            raise ValueError('Captured source and executable query groups differ')
        if any(not isinstance(rows, list) for rows in entry_data['entries'].values()):
            raise ValueError('Captured entry groups are not lists')
        if sum(len(rows) for rows in entry_data['entries'].values()) > entries.MAX_RECORDS:
            raise ValueError('Captured entry records exceed diagnostic bounds')
        complete_tables = captured_symbols.get('hasFullSymbolTable') is True and \
            bool(captured_symbols['symbolTables']) and all(table.get('completed') is True
                                                         for table in captured_symbols['symbolTables'])
        report.update(queries=queries, completeSymbolTableCoverage=complete_tables, bindings={})
        budget = [0]
        for query in queries:
            candidates = signature_data['candidates'][query]
            if not isinstance(candidates, list) or len(candidates) > signatures.MAX_RECORDS:
                raise ValueError('Captured signature candidates exceed diagnostic bounds')
            original = captured_symbols['candidates'][query]
            encoded = lambda value: json.dumps(value, sort_keys=True, separators=(',', ':'))
            if encoded([item['originalSymbolRecord'] for item in candidates]) != encoded(original) or \
                    encoded([item['originalSymbol'] for item in entry_data['entries'][query]]) != encoded(original):
                raise ValueError('Captured candidate inventories differ')
            source_matches = signature_data['matchedSourceSignatures'].get(query, [])
            if not isinstance(source_matches, list) or len(source_matches) > signatures.MAX_RECORDS:
                raise ValueError('Captured source matches exceed diagnostic bounds')
            expected_matches = [item for item in candidates if item.get('exactSourceSignatureMatched') is True and
                                item['originalSymbolRecord'].get('function') is True and
                                item['originalSymbolRecord'].get('undefined') is False and
                                item['originalSymbolRecord'].get('ordinarySectionDefinition') is True]
            if encoded(source_matches) != encoded(expected_matches):
                raise ValueError('Captured exact source match inventory differs')
            report['bindings'][query] = associate(stream, before.st_size, query, source_matches,
                entry_data['entries'][query], captured_symbols['sections'], entry_data['programHeaders'],
                complete_tables, budget=budget)
        report['associationPairsInspected'] = budget[0]
        stream.seek(0)
        report['librarySha256After'] = hashlib.file_digest(stream, 'sha256').hexdigest()
        if report['librarySha256After'] != expected_library or \
                entries.symbols.identity(before) != entries.symbols.identity(os.fstat(stream.fileno())) or \
                entries.symbols.identity(before) != entries.symbols.identity(library.stat()):
            raise ValueError('Pinned library changed during source-entry association')
    report['allQueriesUniquelyBound'] = all(value['uniquePhysicalEntryProven'] for value in report['bindings'].values())
    report['completed'] = True


def main():
    library, expected_library, entry_path, expected_entries, signature_path, expected_signatures, output = sys.argv[1:]
    output = Path(output)
    report = {'completed': False, 'sourceSha256': digest(Path(__file__)), 'diagnosticOnly': True,
              'acceptedSavingsCredit': False, 'actualTargetCallingConventionProven': False, 'rootCensusProven': False,
              'expectedLibrarySha256': expected_library, 'entriesReportSha256': expected_entries,
              'signaturesReportSha256': expected_signatures,
              'scope': 'Remote association of exact records from hash-bound reviewed producers and rechecked executable '
                       'bytes only. All input records, aliases, missing/partial/ambiguous cases remain. Producer ownership '
                       'checks and their authenticated capture provenance remain required; no target load, ELF rescan, '
                       'decoder invocation, argument/register/private-field, live task/thread/clock/size or compiler proof.'}
    try:
        inspect(Path(library).resolve(), expected_library, Path(entry_path), expected_entries,
                Path(signature_path), expected_signatures, output.parent, report)
    except Exception as error:
        report['error'] = {'type': type(error).__name__, 'message': str(error)}
        raise
    finally:
        output.write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
