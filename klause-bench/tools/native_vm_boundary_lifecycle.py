"""Observe source-bound compiler lifecycle entries without private argument reads."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal

import bind_native_vm_boundary_entries as binding
import native_compiler_observer as observer
import native_vm_live_arguments as live

LIBRARY_SHA256 = '5e0fb6e83a5676090f28ff0b453e9199df06af1ff90d473c1b9837e41096114c'
ASSOCIATIONS_SHA256 = 'a08ed43588c9c72218791b9876d285c7bd0ca336fa3f480c4e0b038ba8be3dfd'
ENTRIES_SHA256 = '6540af790ed77cf2eedffd471818aeabe3b38d77d09d4bebd2f5074f097cb8eb'
IMAGE_SHA256 = '16194f713ba1fbff2dd76b7abffad4cd61f91e5663b116a5b03c907ae3e20cd8'
BOUNDARIES = ('compiler_thread_loop', 'CompilerThread', 'create_native_wrapper', 'init_compiler_runtime',
              'compile_method_base', 'on_empty_queue', 'shutdown_compiler_runtime', 'ciObjectFactory',
              'possibly_add_compiler_threads')


def select_entries(stream, file_size, associations, entries):
    if tuple(associations['queries']) != BOUNDARIES or tuple(entries['queries']) != BOUNDARIES or \
            associations.get('allQueriesUniquelyBound') is not True or \
            associations.get('completeSymbolTableCoverage') is not True or \
            set(associations['bindings']) != set(BOUNDARIES) or set(entries['entries']) != set(BOUNDARIES):
        raise ValueError('Complete canonical boundary associations are unavailable')
    selected, locations = {}, set()
    for query in BOUNDARIES:
        group = associations['bindings'][query]
        definitions = group['physicalDefinitions']
        if group.get('uniquePhysicalEntryProven') is not True or len(definitions) != 1 or \
                group.get('expectedSourceSignature') != binding.signatures.SIGNATURES[query]:
            raise ValueError('Boundary has no unique pinned source definition')
        definition = definitions[0]
        if any(type(definition.get(key)) is not int or definition[key] < 0
               for key in ['address', 'declaredBytes', 'fileOffset']):
            raise ValueError('Canonical physical definition dimensions are invalid')
        references = definition['sourceAndEntryRecords']
        if not references:
            raise ValueError('Canonical definition has no retained entry references')
        names = set()
        for reference in references:
            index, source_index = reference['entryRecordIndex'], reference['sourceMatchIndex']
            rows = entries['entries'][query]
            if type(index) is not int or not 0 <= index < len(rows) or \
                    type(source_index) is not int or source_index < 0:
                raise ValueError('Canonical entry reference is outside its captured inventory')
            links = [row for row in group['associations'] if row['sourceMatchIndex'] == source_index and
                     index in row['entryRecordIndices']]
            if len(links) != 1 or rows[index].get('completed') is not True:
                raise ValueError('Canonical source and entry references differ')
            row = rows[index]
            symbol = row['originalSymbol']
            if any(type(symbol.get(key)) is not int or symbol[key] < 0
                   for key in ['sectionIndex', 'value', 'size']) or \
                    symbol['sectionIndex'] >= len(entries['symbols']['sections']):
                raise ValueError('Canonical symbol dimensions are invalid')
            if symbol.get('function') is not True or symbol.get('undefined') is not False or \
                    symbol.get('ordinarySectionDefinition') is not True or \
                    not 1 <= len(symbol['name']) <= 1024 or \
                    not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', symbol['name']) or \
                    symbol['name'].encode('ascii').hex() != symbol['nameHex']:
                raise ValueError('Canonical boundary is not an exact ordinary probe name')
            checked = {}
            raw = binding.entries.entry_bytes(stream, file_size, symbol, entries['symbols']['sections'],
                                              entries['programHeaders'], checked)
            if raw is None or any(row.get(key) != value for key, value in checked.items()) or \
                    (definition['address'], definition['declaredBytes'], definition['fileOffset'],
                     definition['entrySha256']) != (symbol['value'], symbol['size'], checked['fileOffset'],
                                                   checked['entrySha256']):
                raise ValueError('Canonical boundary bytes differ from the pinned library')
            names.add(symbol['name'])
        if len(names) != 1:
            raise ValueError('Canonical physical entry has ambiguous probe names')
        name = next(iter(names))
        named = [row for rows in entries['entries'].values() for row in rows
                 if row['originalSymbol']['name'] == name]
        for row in named:
            symbol = row['originalSymbol']
            if row.get('completed') is not True or symbol.get('function') is not True or \
                    symbol.get('undefined') is not False or symbol.get('ordinarySectionDefinition') is not True or \
                    (symbol['value'], symbol['size'], row.get('fileOffset'), row.get('entrySha256')) != \
                    (definition['address'], definition['declaredBytes'], definition['fileOffset'], definition['entrySha256']):
                raise ValueError('Probe name has a conflicting retained definition')
        location = definition['address'], definition['fileOffset']
        if location in locations:
            raise ValueError('Different source boundaries share a physical probe location')
        locations.add(location)
        selected[query] = {'name': name, 'physicalDefinition': definition, 'sourceEntryReferences': references}
    return selected


def bound_entries(library, association_path, entry_path, out):
    associations = binding.capture(association_path, ASSOCIATIONS_SHA256,
                                   out / 'captured-boundary-associations.json', binding)
    entries = binding.capture(entry_path, ENTRIES_SHA256, out / 'captured-boundary-entries.json', binding.entries)
    if associations.get('entriesReportSha256') != ENTRIES_SHA256 or \
            any(report.get(key) != LIBRARY_SHA256 for report in [associations, entries]
                for key in ['librarySha256', 'librarySha256After', 'expectedLibrarySha256']):
        raise ValueError('Boundary inputs belong to a different entry report or library')
    with library.open('rb') as stream:
        before = os.fstat(stream.fileno())
        if hashlib.file_digest(stream, 'sha256').hexdigest() != LIBRARY_SHA256 or \
                binding.entries.symbols.identity(before) != binding.entries.symbols.identity(library.stat()):
            raise ValueError('Pinned boundary library identity differs')
        selected = select_entries(stream, before.st_size, associations, entries)
        stream.seek(0)
        if hashlib.file_digest(stream, 'sha256').hexdigest() != LIBRARY_SHA256 or \
                binding.entries.symbols.identity(before) != binding.entries.symbols.identity(os.fstat(stream.fileno())) or \
                binding.entries.symbols.identity(before) != binding.entries.symbols.identity(library.stat()):
            raise ValueError('Pinned boundary library changed during validation')
    return selected


def program(library, root_pid, selected):
    if not library.is_absolute() or not re.fullmatch(r'/[A-Za-z0-9_./+\-]+', str(library)) or \
            type(root_pid) is not int or not 0 < root_pid < 2 ** 32 or set(selected) != set(BOUNDARIES):
        raise ValueError('Invalid boundary probe process, library or inventory')
    predicate = f'/pid == {root_pid}/'
    blocks = [f'BEGIN {{ @sequence[{root_pid}] = 0; '
              'printf("VALIDATION_FAULT_EXPECTED\\t%llu\\t%rh\\n", nsecs, buf(uptr(0), 1)); }']
    for kind, probe, tail, arguments in [
            ('EXEC', 'sched_process_exec', '\\t%d', ', uid'),
            ('FORK', 'sched_process_fork', '\\t%d\\t%d', ', args->parent_pid, args->child_pid'),
            ('EXIT', 'sched_process_exit', '', '')]:
        blocks.append(f'tracepoint:sched:{probe} {predicate} {{ ' + live.sequence() +
                      f'printf("LIFECYCLE\\t{kind}\\t%llu\\t%d\\t%d\\t%llu{tail}\\n", '
                      f'nsecs, pid, tid, $sequence{arguments}); }}')
    for query in BOUNDARIES:
        name = selected[query]['name']
        if not 1 <= len(name) <= 1024 or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', name):
            raise ValueError('Unbounded or wildcard boundary probe name')
        for kind, probe in [('ENTER', 'uprobe'), ('LEAVE', 'uretprobe')]:
            blocks.append(f'{probe}:{library}:{name} {predicate} {{ ' + live.sequence() +
                          f'printf("BOUNDARY\\t{kind}\\t{query}\\t%llu\\t%d\\t%d\\t%llu\\n", '
                          'nsecs, pid, tid, $sequence); }')
    for kind in ('begin', 'end'):
        success = 'arg8' if kind == 'end' else '-1'
        statement = (f'usdt:{library}:hotspot:method__compile__{kind} {predicate} {{ ' + live.sequence() +
                     f'printf("USDT\\t{kind}\\t%llu\\t%d\\t%d\\t%llu\\t%lld\\n", '
                     f'nsecs, pid, tid, $sequence, {success}); ')
        for label, pointer, length in [('compiler', 0, 1), ('class', 2, 3), ('method', 4, 5), ('signature', 6, 7)]:
            statement += (f'printf("IDENTITY\\t{kind}\\t%llu\\t%d\\t%d\\t%llu\\t{label}\\t%llu\\t%rh\\n", '
                          f'nsecs, pid, tid, $sequence, arg{length}, '
                          f'buf(uptr(arg{pointer}), arg{length} < 128 ? arg{length} : 128)); ')
        blocks.append(statement + '}')
    blocks.append('END { let $threads = 0; let $events = 0; for ($row : @sequence) { '
                  'printf("FINAL_SEQUENCE\\t%llu\\t%llu\\t%llu\\n", nsecs, $row.0, $row.1); '
                  '$threads++; $events += $row.1; } '
                  'printf("FINAL_SEQUENCE_TOTAL\\t%llu\\t%llu\\t%llu\\n", nsecs, $threads, $events); '
                  'printf("DRAIN_MARKER\\t%llu\\n", nsecs); clear(@sequence); }')
    return '\n'.join(blocks) + '\n'


def terminate(signum, frame):
    raise TimeoutError('Boundary capture was stopped')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('destination', type=Path)
    parser.add_argument('library', type=Path)
    parser.add_argument('associations', type=Path)
    parser.add_argument('entries', type=Path)
    parser.add_argument('image', type=Path)
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    out = args.destination
    out.mkdir(parents=True, exist_ok=True)
    report = {'completed': False, 'diagnosticOnly': True, 'acceptedSavingsCredit': False,
              'sourceSha256': observer.file_digest(Path(__file__)), 'actualCallingConventionProven': False,
              'rootCensusProven': False, 'privateArgumentOrFieldReads': False,
              'scope': 'Source-bound lifecycle callbacks and public USDT identity fragments only. All raw events '
                       'and unfinished contexts retained; callback spans include waiting and are not compiler elapsed '
                       'time. No argument ABI, task pointers, descendants, inlined/absent hooks, complete roots, clock '
                       'alignment, code-size or compiler savings proof.'}
    previous_handler = signal.signal(signal.SIGTERM, terminate)
    try:
        library = args.library.resolve()
        report['selectedEntries'] = bound_entries(library, args.associations, args.entries, out)
        report.update(library=str(library), expectedLibrarySha256=LIBRARY_SHA256)
        report['observerImageSha256'] = observer.file_digest(args.image)
        if report['observerImageSha256'] != IMAGE_SHA256:
            raise ValueError('Observer image differs from the pinned package')
        command = args.command[1:] if args.command[:1] == ['--'] else args.command
        observer.observe_appimage(out / 'observer', library, args.image, command, 120,
                                  lambda path, pid: program(path, pid, report['selectedEntries']),
                                  capture_event_loss=True)
        if observer.file_digest(library) != LIBRARY_SHA256:
            raise ValueError('Pinned library changed during boundary observation')
        report['completed'] = True
    except Exception as error:
        report['error'] = {'type': type(error).__name__, 'message': str(error)}
        raise
    finally:
        signal.signal(signal.SIGTERM, previous_handler)
        (out / 'boundary-lifecycle.json').write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
