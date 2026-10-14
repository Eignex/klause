"""Demangle retained native symbol records without loading the target JVM."""
import hashlib
import json
from pathlib import Path
import sys

from native_vm_demangler import DemanglingError, NativeDemangler
from native_vm_loaded_module import digest

MAX_INPUT_BYTES = 2 * 1024 * 1024
MAX_RECORDS = 4096
SIGNATURES = {
    'invoke_compiler_on_method': 'CompileBroker::invoke_compiler_on_method(CompileTask*)',
    'post_compiled_method': 'nmethod::post_compiled_method(CompileTask*)',
    'total_size': 'nmethod::total_size() const',
}


def inspect(library, expected_library, captured, expected_capture, out, report):
    if digest(library) != expected_library:
        raise ValueError('Target library hash differs from the pinned capture')
    if captured.stat().st_size > MAX_INPUT_BYTES:
        raise ValueError('Captured symbol report exceeds diagnostic bounds')
    raw = captured.read_bytes()
    if len(raw) > MAX_INPUT_BYTES or hashlib.sha256(raw).hexdigest() != expected_capture:
        raise ValueError('Captured symbol report hash differs')
    source = json.loads(raw)
    if source.get('completed') is not True or source.get('librarySha256') != expected_library or \
            source.get('librarySha256After') != expected_library:
        raise ValueError('Captured symbol inspection is incomplete or targets another library')
    queries = source.get('queries')
    if not isinstance(queries, list) or not 0 < len(queries) <= 16:
        raise ValueError('Captured symbol query groups differ')
    if not all(isinstance(query, str) and 1 <= len(query) <= 128 and query.isascii() and
               all(character.isalnum() or character == '_' for character in query) for query in queries):
        raise ValueError('Captured queries are not bounded source identifiers')
    if len(set(queries)) != len(queries) or not isinstance(source.get('candidates'), dict) or \
            set(source['candidates']) != set(queries):
        raise ValueError('Captured symbol query groups differ')
    (out / 'captured-vm-symbols.json').write_bytes(raw)
    report.update(capturedSymbolsSha256=expected_capture, librarySha256=expected_library,
                  candidates={name: [] for name in queries}, matchedSourceSignatures={name: [] for name in queries if name in SIGNATURES})
    provider = NativeDemangler(out)
    report['demanglerProvider'] = provider.provenance
    for query in queries:
        expected = SIGNATURES.get(query)
        rows = source['candidates'][query]
        if not isinstance(rows, list) or len(rows) > MAX_RECORDS:
            raise ValueError('Captured candidate records exceed diagnostic bounds')
        for row in rows:
            name = bytes.fromhex(row['nameHex'])
            if row['name'].encode('ascii', errors='strict') != name:
                raise ValueError('Captured name bytes differ from the displayed name')
            item = {'originalSymbolRecord': row, 'demanglerResult': None,
                    'expectedSourceSignature': expected, 'exactSourceSignatureMatched': False}
            report['candidates'][query].append(item)
            try:
                result = provider.read(name)
            except DemanglingError as error:
                item['demanglerResult'] = error.context
                raise
            item.update(demanglerResult=result, exactSourceSignatureMatched=expected is not None and result['demangled'] == expected)
            if item['exactSourceSignatureMatched'] and row['function'] is True and \
                    row['undefined'] is False and row['ordinarySectionDefinition'] is True:
                report['matchedSourceSignatures'][query].append(item)
    provider.verify_unchanged()
    if digest(library) != expected_library:
        raise ValueError('Target library changed during signature verification')
    report['completed'] = True


def main():
    library, expected_library, captured, expected_capture, output = sys.argv[1:]
    output = Path(output)
    report = {'completed': False, 'library': str(Path(library).resolve()),
              'expectedLibrarySha256': expected_library, 'expectedCapturedSymbolsSha256': expected_capture,
              'sourceSha256': digest(Path(__file__)), 'diagnosticOnly': True,
              'acceptedSavingsCredit': False, 'actualTargetCallingConventionProven': False,
              'scope': 'Exact demangled source signatures from retained raw records only; local/duplicate/unmatched/nonfunction entries retained. No target library load/invocation, argument-register or live task/root/clock/loss/size/performance proof.'}
    try:
        inspect(Path(library).resolve(), expected_library, Path(captured), expected_capture, output.parent, report)
    except Exception as error:
        report['error'] = {'type': type(error).__name__, 'message': str(error)}
        raise
    finally:
        output.write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
