"""Capture unaccepted native argument candidates and exported-field bytes."""
import argparse
import hashlib
import json
from pathlib import Path

import native_compiler_observer as observer
import read_native_vm_metadata as metadata

LIBRARY_SHA256 = '5e0fb6e83a5676090f28ff0b453e9199df06af1ff90d473c1b9837e41096114c'
ENTRIES_SHA256 = 'b084bd021491dc839e66e01b0b3ce8543e43b205dfa783dfd68f5c44b0297812'
MAX_REPORT_BYTES = 32 * 1024 * 1024
HOOKS = (
    ('broker', '_ZN13CompileBroker25invoke_compiler_on_methodEP11CompileTask', (('task', 'CompileTask', 'arg0'),)),
    ('post', '_ZN7nmethod20post_compiled_methodEP11CompileTask',
     (('nmethod', 'nmethod', 'arg0'), ('task', 'CompileTask', 'arg1'))),
    ('total-size', '_ZNK7nmethod10total_sizeEv', (('nmethod', 'nmethod', 'arg0'),)),
    ('queue-add', '_ZN12CompileQueue3addEP11CompileTask', (('task', 'CompileTask', 'arg1'),)),
    ('queue-remove', '_ZN12CompileQueue6removeEP11CompileTask', (('task', 'CompileTask', 'arg1'),)),
    ('queue-stale', '_ZN12CompileQueue21remove_and_mark_staleEP11CompileTask',
     (('task', 'CompileTask', 'arg1'),)),
    ('queue-get', '_ZN12CompileQueue3getEP14CompilerThread', ()),
    ('wrapper-construct', '_ZN18CompileTaskWrapperC1EP11CompileTask', (('task', 'CompileTask', 'arg1'),)),
    ('wrapper-destroy', '_ZN18CompileTaskWrapperD1Ev', ()),
)


def bound_entries(path):
    raw = path.read_bytes()
    if len(raw) > MAX_REPORT_BYTES or hashlib.sha256(raw).hexdigest() != ENTRIES_SHA256:
        raise ValueError('Executable entry capture differs from pinned original report')
    report = json.loads(raw)
    if not report['completed'] or report['librarySha256'] != LIBRARY_SHA256 or \
            report['librarySha256After'] != LIBRARY_SHA256:
        raise ValueError('Executable entry capture is incomplete or belongs to a different library')
    selected = {}
    for label, name, candidates in HOOKS:
        rows = [row for values in report['entries'].values() for row in values
                if row['originalSymbol']['name'] == name]
        if not rows or any(not row['completed'] or 'entryHex' not in row or
                           not row['originalSymbol']['function'] or row['originalSymbol']['undefined'] or
                           not row['originalSymbol']['ordinarySectionDefinition'] for row in rows):
            raise ValueError('Required canonical executable entry is unavailable')
        identities = {(row['originalSymbol']['value'], row['originalSymbol']['size'],
                       row['fileOffset'], row['entrySha256']) for row in rows}
        if len(identities) != 1:
            raise ValueError('Canonical executable entry has ambiguous definitions')
        selected[label] = rows
    return selected


def layout_fields(report):
    if report['librarySha256'] != LIBRARY_SHA256 or report['pointerWidth'] != 8:
        raise ValueError('Live field layout differs from pinned library or pointer width')
    selected = report['requiredFields']
    fields = []
    for owner in ('CompileTask', 'nmethod'):
        owners = [row for row in report['typeTable']['rows'] if row['typeName'] == owner]
        if len(owners) != 1 or type(owners[0]['size']) is not int or not 0 < owners[0]['size'] <= 1024 * 1024:
            raise ValueError('Exported live field owner is missing or unsupported')
        for name in metadata.REQUIRED_FIELDS[owner]:
            matches = [row for row in selected if row['typeName'] == owner and row['fieldName'] == name]
            if len(matches) != 1:
                raise ValueError('Exported live field is missing or ambiguous')
            row = matches[0]
            if row['isStatic'] != 0 or type(row['offset']) is not int or row['offset'] < 0 or \
                    type(row['targetFieldWidth']) is not int or row['targetFieldWidth'] not in (1, 2, 4, 8):
                raise ValueError('Unsupported exported live field width or offset')
            if row['offset'] + row['targetFieldWidth'] > owners[0]['size']:
                raise ValueError('Exported live field exceeds its declared owner')
            fields.append(row)
    return fields


def sequence():
    # Map-value lowering keeps map storage distinct from user-memory pointers.
    return ('let $sequence = 0; if (has_key(@sequence, tid)) { $sequence = @sequence[tid]; } '
            '$sequence++; @sequence[tid] = $sequence; ')


def field_reads(kind, candidates, fields):
    result = []
    for role, owner, argument in candidates:
        result.append(f'let ${role} = (uint64){argument}; '
                      f'printf("CANDIDATE\\t{kind}\\t%llu\\t%d\\t%d\\t%llu\\t{role}\\t%llx\\n", '
                      f'nsecs, pid, tid, $sequence, ${role}); ')
        result.append(f'if (${role} != 0) {{ ')
        for row in fields:
            if row['typeName'] != owner:
                continue
            name, offset, width = row['fieldName'], row['offset'], row['targetFieldWidth']
            result.append(f'printf("FIELD\\t{kind}\\t%llu\\t%d\\t%d\\t%llu\\t{role}\\t{owner}.{name}'
                          f'\\t%llx\\t{width}\\t%rh\\n", nsecs, pid, tid, $sequence, ${role}, '
                          f'buf(uptr(${role} + {offset}), {width})); ')
        result.append('} ')
    return ''.join(result)


def program(library, root_pid, fields):
    if not library.is_absolute() or any(character in str(library) for character in '\n"*') or \
            type(root_pid) is not int or root_pid <= 0:
        raise ValueError('Invalid probe library path or original process identity')
    blocks = [f'BEGIN {{ @sequence[{root_pid}] = 0; '
              'printf("VALIDATION_FAULT_EXPECTED\\t%llu\\t%rh\\n", nsecs, buf(uptr(0), 1)); }',
              f'tracepoint:sched:sched_process_exec /pid == {root_pid}/ '
              '{ printf("EXEC\\t%llu\\t%d\\t%d\\t%d\\n", nsecs, pid, tid, uid); }',
              f'tracepoint:sched:sched_process_fork /args->parent_pid == {root_pid}/ '
              '{ printf("FORK\\t%llu\\t%d\\t%d\\n", nsecs, args->parent_pid, args->child_pid); }']
    predicate = f'/pid == {root_pid}/'
    for label, name, candidates in HOOKS:
        raw_format = '\\t%llx' if label in ('broker', 'total-size', 'wrapper-destroy') else '\\t%llx\\t%llx'
        raw_arguments = 'arg0' if label in ('broker', 'total-size', 'wrapper-destroy') else 'arg0, arg1'
        blocks.append(f'uprobe:{library}:{name} {predicate} {{ ' + sequence() +
                      f'printf("ENTRY\\t{label}\\t%llu\\t%d\\t%d\\t%llu{raw_format}\\n", '
                      f'nsecs, pid, tid, $sequence, {raw_arguments}); ' + field_reads(label, candidates, fields) + '}')
        returned = (('task', 'CompileTask', 'retval'),) if label == 'queue-get' else ()
        scalar = ', retval, (uint32)retval' if label == 'total-size' else ''
        format_tail = '\\t%llx\\t%u' if label == 'total-size' else ''
        blocks.append(f'uretprobe:{library}:{name} {predicate} {{ ' + sequence() +
                      f'printf("RETURN\\t{label}\\t%llu\\t%d\\t%d\\t%llu{format_tail}\\n", '
                      f'nsecs, pid, tid, $sequence{scalar}); ' + field_reads(label + '-return', returned, fields) + '}')
    for kind in ('begin', 'end'):
        success = 'arg8' if kind == 'end' else '-1'
        statement = (f'usdt:{library}:hotspot:method__compile__{kind} {predicate} {{ ' + sequence() +
                     f'printf("USDT\\t{kind}\\t%llu\\t%d\\t%d\\t%llu\\t%lld\\n", '
                     f'nsecs, pid, tid, $sequence, {success}); ')
        for label, pointer, length in [('compiler', 0, 1), ('class', 2, 3), ('method', 4, 5), ('signature', 6, 7)]:
            statement += (f'printf("IDENTITY\\t{kind}\\t%llu\\t%d\\t%d\\t%llu\\t{label}\\t%llu\\t%rh\\n", '
                          f'nsecs, pid, tid, $sequence, arg{length}, '
                          f'buf(uptr(arg{pointer}), arg{length} < 128 ? arg{length} : 128)); ')
        blocks.append(statement + '}')
    blocks.append('END { let $threads = 0; let $events = 0; '
                  'for ($row : @sequence) { '
                  'printf("FINAL_SEQUENCE\\t%llu\\t%llu\\t%llu\\n", nsecs, $row.0, $row.1); '
                  '$threads++; $events += $row.1; } '
                  'printf("FINAL_SEQUENCE_TOTAL\\t%llu\\t%llu\\t%llu\\n", nsecs, $threads, $events); '
                  'printf("DRAIN_MARKER\\t%llu\\n", nsecs); clear(@sequence); }')
    return '\n'.join(blocks) + '\n'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('destination', type=Path)
    parser.add_argument('library', type=Path)
    parser.add_argument('entries', type=Path)
    parser.add_argument('image', type=Path)
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    out = args.destination
    out.mkdir(parents=True, exist_ok=True)
    receipt = {'diagnosticOnly': True, 'acceptedSavingsCredit': False, 'completed': False,
               'actualCallingConventionProven': False, 'livePointerProven': False,
               'scope': 'Raw unaccepted argument candidates and exported field bytes only. No private offsets, post-free reads, size substitution, root completeness or savings proof.'}
    try:
        receipt['entries'] = bound_entries(args.entries)
        library = args.library.resolve()
        layouts = metadata.inspect(library, LIBRARY_SHA256, out)
        (out / 'live-layout.json').write_text(json.dumps({**layouts, 'completed': True}, indent=2) + '\n')
        fields = layout_fields(layouts)
        receipt['fields'] = fields
        receipt['toolSources'] = {name: observer.file_digest(Path(__file__).with_name(name)) for name in
                                  ['native_vm_live_arguments.py', 'native_compiler_observer.py',
                                   'read_native_vm_metadata.py', 'native_vm_loaded_module.py']}
        command = args.command[1:] if args.command[:1] == ['--'] else args.command
        observer.observe_appimage(out / 'observer', library, args.image, command, 120,
                                  lambda path, pid: program(path, pid, fields))
        if observer.file_digest(library) != LIBRARY_SHA256:
            raise ValueError('Target library changed during live capture')
        receipt['completed'] = True
    except Exception as error:
        receipt['error'] = {'type': type(error).__name__, 'message': str(error)}
        raise
    finally:
        (out / 'live-arguments.json').write_text(json.dumps(receipt, indent=2) + '\n')


if __name__ == '__main__':
    main()
