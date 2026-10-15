"""Retain and snapshot an owned tracer's BTF-bound loss-counter map on Linux."""
import ctypes as c
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import select
import struct
import subprocess
import sys
import time

MAX_BTF_BYTES = 16 * 1024 * 1024
MAX_CPU_SLOTS = 4096
SECTION = '.data.event_loss_counter'
VARIABLE = '__bt__event_loss_counter'


class IdAttr(c.Structure):
    _fields_ = [('identifier', c.c_uint32), ('next_id', c.c_uint32), ('open_flags', c.c_uint32)]


class InfoAttr(c.Structure):
    _fields_ = [('fd', c.c_uint32), ('length', c.c_uint32), ('info', c.c_uint64)]


class ElemAttr(c.Structure):
    _fields_ = [('fd', c.c_uint32), ('key', c.c_uint64), ('value', c.c_uint64), ('flags', c.c_uint64)]


class CreateAttr(c.Structure):
    _fields_ = [(name, c.c_uint32) for name in ('map_type', 'key_size', 'value_size', 'max_entries',
                                               'map_flags', 'inner_map_fd', 'numa_node')] + [
        ('name', c.c_char * 16)] + [(name, c.c_uint32) for name in (
            'ifindex', 'btf_fd', 'btf_key_type_id', 'btf_value_type_id', 'btf_vmlinux_value_type_id')] + [
        ('map_extra', c.c_uint64)]


class MapInfo(c.Structure):
    _fields_ = [(name, c.c_uint32) for name in ('type', 'id', 'key_size', 'value_size', 'max_entries', 'flags')] + [
        ('name', c.c_char * 16), ('ifindex', c.c_uint32), ('btf_vmlinux_value_type_id', c.c_uint32),
        ('netns_dev', c.c_uint64), ('netns_ino', c.c_uint64), ('btf_id', c.c_uint32),
        ('btf_key_type_id', c.c_uint32), ('btf_value_type_id', c.c_uint32), ('padding', c.c_uint32),
        ('map_extra', c.c_uint64)]


class BtfInfo(c.Structure):
    _fields_ = [('btf', c.c_uint64), ('size', c.c_uint32), ('id', c.c_uint32),
                ('name', c.c_uint64), ('name_len', c.c_uint32), ('kernel_btf', c.c_uint32)]


class Bpf:
    def __init__(self):
        if platform.system() != 'Linux' or platform.machine() != 'x86_64' or sys.byteorder != 'little' or \
                c.sizeof(c.c_void_p) != 8:
            raise ValueError('Unsupported Linux BPF syscall interface')
        self.libc = c.CDLL('libc.so.6', use_errno=True)
        self.libc.syscall.restype = c.c_long

    def call(self, command, attr):
        result = self.libc.syscall(c.c_long(321), c.c_int(command), c.byref(attr), c.c_uint(c.sizeof(attr)))
        if result < 0:
            raise OSError(c.get_errno(), f'BPF command {command} failed')
        return result

    def open_id(self, identifier, btf=False):
        if not 0 < identifier < 2 ** 32:
            raise ValueError('Invalid kernel object ID')
        return self.call(19 if btf else 14, IdAttr(identifier))

    def info(self, fd, result):
        attr = InfoAttr(fd, c.sizeof(result), c.addressof(result))
        self.call(15, attr)
        if attr.length < c.sizeof(result):
            raise ValueError('Kernel returned incomplete object metadata')
        return result

    def map_info(self, fd):
        info = self.info(fd, MapInfo())
        return {name: bytes(getattr(info, name)).hex() if name == 'name' else getattr(info, name)
                for name, _ in MapInfo._fields_ if name != 'padding'}

    def btf(self, identifier):
        fd = self.open_id(identifier, btf=True)
        try:
            info = self.info(fd, BtfInfo())
            if info.id != identifier or info.kernel_btf or not 0 < info.size <= MAX_BTF_BYTES:
                raise ValueError('Invalid user BTF object metadata')
            raw = c.create_string_buffer(info.size)
            info.btf = c.addressof(raw)
            info.name = 0
            info.name_len = 0
            self.info(fd, info)
            if info.id != identifier or info.size != len(raw):
                raise ValueError('Kernel BTF object changed during read')
            return raw.raw
        finally:
            os.close(fd)

    def lookup(self, fd, size, key=0):
        if not 0 < size <= MAX_CPU_SLOTS * 8:
            raise ValueError('Counter read exceeds bounds')
        raw = c.create_string_buffer(size)
        index = c.c_uint32(key)
        self.call(1, ElemAttr(fd, c.addressof(index), c.addressof(raw), 0))
        return raw.raw

    def control(self, slots):
        if type(slots) is not int or not 1 <= slots <= MAX_CPU_SLOTS:
            raise ValueError('Kernel-map control slot count exceeds bounds')
        attr = CreateAttr(map_type=2, key_size=4, value_size=slots * 8,
                          max_entries=1, name=b'klause_loss_ctl')
        fd = self.call(0, attr)
        try:
            zero = self.lookup(fd, slots * 8)
            values = (c.c_uint64 * slots)()
            values[slots - 1] = 1
            key = c.c_uint32(0)
            self.call(2, ElemAttr(fd, c.addressof(key), c.addressof(values), 0))
            nonzero = self.lookup(fd, slots * 8)
            if snapshot(zero, slots)['sum'] != 0 or snapshot(nonzero, slots)['sum'] != 1 or \
                    snapshot(nonzero, slots)['slots'][-1]['value'] != 1:
                raise ValueError('Highest-slot kernel-map control failed')
            return {'map': self.map_info(fd), 'zero': snapshot(zero, slots),
                    'highestSlotNonzero': snapshot(nonzero, slots), 'passed': True}
        finally:
            os.close(fd)


def snapshot(raw, slots):
    if type(slots) is not int or not 1 <= slots <= MAX_CPU_SLOTS or len(raw) != slots * 8:
        raise ValueError('Counter snapshot has an invalid slot count or width')
    values = [{'index': index, 'rawHex': raw[index * 8:(index + 1) * 8].hex(),
               'value': int.from_bytes(raw[index * 8:(index + 1) * 8], 'little')} for index in range(slots)]
    return {'rawHex': raw.hex(), 'slots': values, 'sum': sum(row['value'] for row in values)}


def cpu_slots(text):
    if not isinstance(text, str) or len(text) > 65536:
        raise ValueError('Possible CPU range text exceeds bounds')
    result = []
    for part in text.strip().split(','):
        if not re.fullmatch(r'[0-9]+(?:-[0-9]+)?', part):
            raise ValueError('Malformed possible CPU range')
        bounds = list(map(int, part.split('-')))
        first, last = bounds[0], bounds[-1]
        if not 0 <= first <= last < MAX_CPU_SLOTS:
            raise ValueError('Possible CPU range exceeds bounds')
        result.extend(range(first, last + 1))
    if not result or result != list(range(len(result))):
        raise ValueError('Possible CPU IDs are not a unique contiguous range')
    return 1 << (len(result) - 1).bit_length()


def verify_report(document, btf, expected_observer, expected_source):
    ready, result = document['ready'], document['result']
    host = ready['host']
    if (host['system'], host['machine'], host['byteorder'], host['pointerBytes']) != ('Linux', 'x86_64', 'little', 8):
        raise ValueError('Final counter host interface differs from the documented UAPI')
    if document['completed'] is not True or ready['observer'] != expected_observer or \
            result['observer'] != expected_observer or ready['sourceSha256'] != expected_source or \
            result['observerExited'] is not True or result['allSlotsRead'] is not True:
        raise ValueError('Final counter observer, source or lifetime binding differs')
    slots = cpu_slots(ready['possibleCpus'])
    info = ready['map']
    if any(type(info[key]) is not int for key in ('type', 'id', 'key_size', 'max_entries', 'value_size', 'btf_id', 'btf_value_type_id')) or \
            result['map'] != info or info['type'] != 2 or not 0 < info['id'] < 2 ** 32 or \
            info['key_size'] != 4 or info['max_entries'] != 1 or info['value_size'] != slots * 8 or \
            not 0 < info['btf_id'] < 2 ** 32:
        raise ValueError('Final retained counter map metadata differs')
    binding = btf_counter(btf, info['btf_value_type_id'], slots)
    if ready['binding'] != binding or result['binding'] != binding:
        raise ValueError('Final counter BTF binding differs')
    descriptor = re.findall(r'^map_id:\s*([0-9]+)\s*$', ready['observerDescriptor'], re.MULTILINE)
    if descriptor != [str(info['id'])]:
        raise ValueError('Final counter was not bound to the original observer descriptor')
    candidates = []
    inspections = ready['mapInspections']
    if not 1 <= len(inspections) <= 256:
        raise ValueError('Observer map inspection inventory is absent or excessive')
    for row in inspections:
        candidate = row['map']
        identifiers = re.findall(r'^map_id:\s*([0-9]+)\s*$', row['descriptor'], re.MULTILINE)
        if identifiers != [str(candidate['id'])] or candidate['btf_id'] != info['btf_id'] or \
                row['btfSha256'] != binding['btfSha256']:
            raise ValueError('Observer candidate descriptor or BTF ownership differs')
        try:
            btf_counter(btf, candidate['btf_value_type_id'], slots)
        except ValueError:
            continue
        candidates.append(candidate)
    if candidates != [info]:
        raise ValueError('Final loss-counter selection is absent or ambiguous')
    controls = ready['controls']
    control_map = controls['map']
    if controls['passed'] is not True or not 0 < control_map['id'] < 2 ** 32 or \
            control_map['id'] == info['id'] or control_map['type'] != 2 or \
            control_map['key_size'] != 4 or control_map['value_size'] != slots * 8 or control_map['max_entries'] != 1:
        raise ValueError('Private counter control map is absent, malformed or aliases the observed map')
    rows = [ready['initial'], controls['zero'], controls['highestSlotNonzero'], result['final']]
    for row in rows:
        if snapshot(bytes.fromhex(row['rawHex']), slots) != row:
            raise ValueError('Counter slot bytes disagree with the recorded values or total')
    if rows[0]['sum'] != 0 or rows[1]['sum'] != 0 or rows[2]['sum'] != 1 or rows[2]['slots'][-1]['value'] != 1:
        raise ValueError('Initial counter or highest-slot control failed')
    zero = rows[3]['sum'] == 0
    if result['finalCounterZero'] is not zero:
        raise ValueError('Final zero-counter claim differs from the full slot bytes')
    return {'finalLossCounterProven': True, 'finalLossCounterZeroProven': zero,
            'globalLossFreeCaptureProven': False, 'binding': binding, 'final': rows[3],
            'scope': 'Complete owned tracer ring-buffer loss-counter slots only. Probe delivery, root census, lifetimes, clocks and compiler acceptance remain separate.'}


def btf_types(raw):
    if not 24 <= len(raw) <= MAX_BTF_BYTES:
        raise ValueError('BTF data exceeds bounds')
    magic, version, flags, header, type_off, type_len, str_off, str_len = struct.unpack_from('<HBBIIIII', raw)
    if magic != 0xeb9f or version != 1 or flags != 0 or header < 24 or \
            header > len(raw) or type_off + type_len > len(raw) - header or \
            str_off + str_len > len(raw) - header or type_off + type_len > str_off:
        raise ValueError('Invalid BTF header or section bounds')
    strings = raw[header + str_off:header + str_off + str_len]
    if not strings or strings[0] != 0 or strings[-1] != 0:
        raise ValueError('Malformed BTF string section')

    def name(offset):
        if not 0 <= offset < len(strings):
            raise ValueError('BTF name exceeds the string section')
        return strings[offset:strings.index(0, offset)].decode('ascii')

    types, offset, end = {}, header + type_off, header + type_off + type_len
    while offset < end:
        if offset + 12 > end or len(types) >= 100000:
            raise ValueError('Truncated or excessive BTF types')
        name_off, info, value = struct.unpack_from('<III', raw, offset)
        kind, count = (info >> 24) & 31, info & 65535
        sizes = {1: 4, 2: 0, 3: 12, 4: count * 12, 5: count * 12, 6: count * 8,
                 7: 0, 8: 0, 9: 0, 10: 0, 11: 0, 12: 0, 13: count * 8, 14: 4,
                 15: count * 12, 16: 0, 17: 4, 18: 0, 19: count * 12}
        if kind not in sizes or offset + 12 + sizes[kind] > end:
            raise ValueError('Unsupported or truncated BTF type')
        types[len(types) + 1] = {'name': name(name_off), 'kind': kind, 'count': count, 'value': value,
                                 'payload': raw[offset + 12:offset + 12 + sizes[kind]]}
        offset += 12 + sizes[kind]
    return types


def btf_type_graph(raw, identifier):
    types = btf_types(raw)
    pending, seen, result = [identifier], set(), []
    while pending:
        identifier = pending.pop(0)
        if identifier in seen:
            continue
        if len(seen) >= 128:
            raise ValueError('BTF diagnostic graph exceeds bounds')
        seen.add(identifier)
        value = types.get(identifier)
        if value is None:
            result.append({'id': identifier, 'error': 'Absent BTF type ID'})
            continue
        row = {key: data for key, data in value.items() if key != 'payload'}
        row.update(id=identifier, payloadHex=value['payload'].hex())
        kind = value['kind']
        if kind == 15:
            row['members'] = [dict(zip(('typeId', 'offset', 'bytes'), struct.unpack_from('<III', value['payload'], index * 12)))
                              for index in range(value['count'])]
            pending.extend(item['typeId'] for item in row['members'])
        elif kind in (8, 9, 10, 11, 14, 18):
            pending.append(value['value'])
        elif kind == 3:
            element, index, count = struct.unpack('<III', value['payload'])
            row['array'] = {'elementTypeId': element, 'indexTypeId': index, 'elements': count}
            pending.extend([element, index])
        elif kind == 1:
            encoded = struct.unpack('<I', value['payload'])[0]
            row['integer'] = {'encoded': encoded, 'encoding': encoded >> 24 & 15,
                              'offset': encoded >> 16 & 255, 'bits': encoded & 255}
        result.append(row)
    return result


def btf_counter(raw, section_id, slots):
    if type(slots) is not int or not 1 <= slots <= MAX_CPU_SLOTS:
        raise ValueError('BTF counter slot count exceeds bounds')
    types = btf_types(raw)
    section = types.get(section_id)
    if not section or section['kind'] != 15 or section['name'] != SECTION or section['count'] != 1:
        raise ValueError('Map BTF does not identify the unique loss-counter section')
    variable_id, variable_offset, variable_size = struct.unpack('<III', section['payload'])
    variable = types.get(variable_id)
    if not variable or variable['kind'] != 14 or variable['name'] != VARIABLE or variable_offset != 0:
        raise ValueError('Map BTF does not identify the loss-counter variable at offset zero')
    visited = set()

    def resolve(identifier):
        value = types.get(identifier)
        while value and value['kind'] in (8, 9, 10, 11, 18):
            if identifier in visited:
                raise ValueError('Cyclic BTF counter type')
            visited.add(identifier)
            identifier = value['value']
            value = types.get(identifier)
        return value

    value = resolve(variable['value'])
    for expected in (slots, 1):
        if not value or value['kind'] != 3:
            raise ValueError('BTF counter lacks its CPU and per-CPU arrays')
        element, _, count = struct.unpack('<III', value['payload'])
        if count != expected:
            raise ValueError('BTF counter array does not cover the expected CPU slots')
        value = resolve(element)
    if not value or value['kind'] != 1 or value['value'] != 8 or \
            struct.unpack('<I', value['payload'])[0] != 64 or variable_size != slots * 8 or section['value'] != slots * 8:
        raise ValueError('BTF loss-counter storage is not a complete unsigned 64-bit CPU array')
    return {'sectionId': section_id, 'section': SECTION, 'variableId': variable_id,
            'variable': VARIABLE, 'offset': variable_offset, 'slotBytes': 8, 'storageBytes': variable_size,
            'btfSha256': hashlib.sha256(raw).hexdigest()}


def process_identity(pid):
    proc = Path('/proc') / str(pid)
    stat = (proc / 'stat').read_text()
    with (proc / 'exe').open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    return {'pid': pid, 'startTicks': stat[stat.rindex(')') + 2:].split()[19],
            'executableSha256': digest, 'comm': (proc / 'comm').read_text().strip(),
            'mountNamespace': os.readlink(proc / 'ns/mnt')}


def bind_counter(api, pid, slots, audit=None):
    directory = Path('/proc') / str(pid) / 'fdinfo'
    entries = list(directory.iterdir())
    if len(entries) > 8192:
        raise ValueError('Observer has excessive file descriptors')
    selected, seen, btfs = [], set(), {}
    try:
        for path in entries:
            if not path.name.isdigit():
                continue
            text = path.read_text()
            identifiers = re.findall(r'^map_id:\s*([0-9]+)\s*$', text, re.MULTILINE)
            if len(identifiers) > 1:
                raise ValueError('Ambiguous observer map descriptor')
            if not identifiers or int(identifiers[0]) in seen:
                continue
            identifier = int(identifiers[0])
            seen.add(identifier)
            row = {'descriptor': text, 'mapIdFromDescriptor': identifier, 'eligibleArray': False}
            if audit is not None:
                audit.append(row)
            fd = api.open_id(identifier)
            try:
                info = api.map_info(fd)
                row['map'] = info
                if info['id'] != identifier:
                    raise ValueError('Observer descriptor differs from opened kernel map')
                if info['type'] != 2 or info['key_size'] != 4 or info['max_entries'] != 1 or not info['btf_id']:
                    continue
                row['eligibleArray'] = True
                if audit is not None:
                    if sum(item['eligibleArray'] for item in audit) > 256:
                        raise ValueError('Excessive candidate observer array maps')
                btf = btfs.setdefault(info['btf_id'], None)
                if len(btfs) != 1:
                    raise ValueError('Observer array maps do not share one BTF object')
                if btf is None:
                    btf = btfs[info['btf_id']] = api.btf(info['btf_id'])
                    if sum(len(raw) for raw in btfs.values() if raw is not None) > MAX_BTF_BYTES:
                        raise ValueError('Observer BTF objects exceed total bounds')
                    if audit is not None:
                        row['btfRawHex'] = btf.hex()
                row['btfSha256'] = hashlib.sha256(btf).hexdigest()
                try:
                    binding = btf_counter(btf, info['btf_value_type_id'], slots)
                except ValueError as error:
                    row['rejection'] = str(error)
                    continue
                if info['value_size'] != slots * 8:
                    raise ValueError('Bound counter map does not cover every possible CPU slot')
                selected.append((fd, info, binding, btf, text))
                fd = None
            finally:
                if fd is not None:
                    os.close(fd)
        if len(selected) != 1:
            raise ValueError('Owned observer loss-counter map is absent or ambiguous')
        return selected.pop()
    finally:
        for fd, *_ in selected:
            os.close(fd)


def main():
    pid, start, executable, namespace, timeout = sys.argv[1:]
    pid, timeout = int(pid), int(timeout)
    if pid <= 0 or not 1 <= timeout <= 3600 or not re.fullmatch('[0-9a-f]{64}', executable):
        raise ValueError('Invalid observer identity or timeout')
    expected = {'pid': pid, 'startTicks': start, 'executableSha256': executable,
                'comm': 'bpftrace', 'mountNamespace': namespace}
    identity = process_identity(pid)
    if identity != expected:
        print('IDENTITY_FAILURE\t' + json.dumps({'expected': expected, 'actual': identity}), flush=True)
        raise ValueError('Original observer process identity differs')
    possible = Path('/sys/devices/system/cpu/possible').read_text()
    slots = cpu_slots(possible)
    api = Bpf()
    audit = []
    try:
        fd, info, binding, btf, descriptor = bind_counter(api, pid, slots, audit)
    except BaseException:
        print('BIND_FAILURE\t' + json.dumps({'observer': expected, 'inspections': audit}), flush=True)
        raise
    try:
        controls = api.control(slots)
        initial = snapshot(api.lookup(fd, info['value_size']), slots)
        if initial['sum'] != 0 or process_identity(pid) != expected:
            raise ValueError('Initial counter is nonzero or observer identity changed')
        if controls['map']['id'] == info['id']:
            raise ValueError('Private control map aliases the observed counter')
        ready = {'observer': expected, 'possibleCpus': possible, 'map': info, 'binding': binding,
                 'host': {'system': platform.system(), 'machine': platform.machine(), 'release': platform.release(),
                          'byteorder': sys.byteorder, 'pointerBytes': c.sizeof(c.c_void_p)},
                 'btfRawHex': btf.hex(), 'observerDescriptor': descriptor,
                 'mapInspections': [{key: value for key, value in row.items() if key != 'btfRawHex'}
                                    for row in audit if row['eligibleArray']],
                 'descriptorInventory': [{key: value for key, value in row.items() if key != 'btfRawHex'} for row in audit],
                 'helperPid': os.getpid(), 'helperStartTicks': Path('/proc/self/stat').read_text().rsplit(')', 1)[1].split()[19],
                 'controls': controls, 'initial': initial, 'sourceSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
        print('READY\t' + json.dumps(ready), flush=True)
        if not select.select([sys.stdin], [], [], timeout)[0] or sys.stdin.readline(32) != 'FINAL\n':
            raise ValueError('Final snapshot command absent or malformed')
        proc = Path('/proc') / str(pid)
        if proc.exists():
            try:
                stat = (proc / 'stat').read_text()
                fields = stat[stat.rindex(')') + 2:].split()
                if fields[19] == start and fields[0] not in ('Z', 'X'):
                    raise ValueError('Original observer is still live at final counter snapshot')
            except FileNotFoundError:
                pass
        if Path('/sys/devices/system/cpu/possible').read_text() != possible or api.map_info(fd) != info:
            raise ValueError('Possible CPUs or retained map metadata changed')
        final = snapshot(api.lookup(fd, info['value_size']), slots)
        print('FINAL\t' + json.dumps({'observer': expected, 'map': info, 'binding': binding,
                                       'final': final, 'observerExited': True, 'allSlotsRead': True,
                                       'finalCounterZero': final['sum'] == 0}), flush=True)
    finally:
        os.close(fd)


class Monitor:
    def __init__(self, destination, pid, start, executable, namespace, timeout):
        self.destination = destination
        self.buffer = b''
        self.ready = None
        self.errors = (destination / 'event-loss.stderr').open('wb')
        self.output = (destination / 'event-loss.stdout').open('wb')
        self.process = None
        try:
            self.process = subprocess.Popen(['sudo', '-n', 'python3', str(Path(__file__).resolve()),
                                             str(pid), start, executable, namespace, str(timeout + 60)],
                                            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.errors)
            self.ready = self.frame('READY')
            expected = {'pid': pid, 'startTicks': start, 'executableSha256': executable,
                        'comm': 'bpftrace', 'mountNamespace': namespace}
            if self.ready['observer'] != expected or self.ready['sourceSha256'] != \
                    hashlib.sha256(Path(__file__).read_bytes()).hexdigest():
                raise ValueError('Loss monitor observer or source binding differs')
            btf = bytes.fromhex(self.ready.pop('btfRawHex'))
            if len(btf) > MAX_BTF_BYTES or hashlib.sha256(btf).hexdigest() != self.ready['binding']['btfSha256']:
                raise ValueError('Loss monitor raw BTF binding differs')
            (destination / 'event-loss.btf').write_bytes(btf)
            (destination / 'event-loss-ready.json').write_text(json.dumps(self.ready, indent=2) + '\n')
        except BaseException:
            self.close()
            raise

    def frame(self, label):
        deadline = time.monotonic() + 30
        while b'\n' not in self.buffer:
            if len(self.buffer) > 2 * MAX_BTF_BYTES + 8 * 1024 * 1024 or time.monotonic() >= deadline:
                raise ValueError('Loss monitor frame is oversized or timed out')
            if not select.select([self.process.stdout], [], [], max(0, deadline - time.monotonic()))[0]:
                raise ValueError('Loss monitor response timed out')
            chunk = os.read(self.process.stdout.fileno(), 65536)
            if not chunk:
                raise ValueError('Loss monitor stopped before its complete response')
            self.output.write(chunk)
            self.output.flush()
            self.buffer += chunk
        line, self.buffer = self.buffer.split(b'\n', 1)
        prefix = label.encode() + b'\t'
        if not line.startswith(prefix):
            raise ValueError('Loss monitor response label differs')
        return json.loads(line[len(prefix):])

    def finish(self):
        self.process.stdin.write(b'FINAL\n')
        self.process.stdin.flush()
        final = self.frame('FINAL')
        self.process.stdin.close()
        self.process.stdin = None
        if self.process.wait(timeout=10) != 0 or self.buffer or self.process.stdout.read(1):
            raise ValueError('Loss monitor finalization failed or has extra records')
        report = {'completed': True, 'ready': self.ready, 'result': final,
                  'scope': 'Owned tracer all-slot ring-buffer loss counter only; no root, probe census, clock, size or compiler proof.'}
        (self.destination / 'event-loss.json').write_text(json.dumps(report, indent=2) + '\n')
        self.close()
        return report

    def close(self):
        try:
            if self.process is not None:
                if self.process.stdin is not None:
                    try:
                        self.process.stdin.close()
                    except BrokenPipeError:
                        pass
                    self.process.stdin = None
                try:
                    self.process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    if self.ready is not None:
                        pid = self.ready['helperPid']
                        try:
                            stat = Path(f'/proc/{pid}/stat').read_text().rsplit(')', 1)[1].split()
                            if stat[19] == self.ready['helperStartTicks']:
                                subprocess.run(['sudo', '-n', 'kill', '-TERM', str(pid)], check=True)
                        except FileNotFoundError:
                            pass
                    self.process.wait(timeout=5)
                if self.process.stdout is not None:
                    self.process.stdout.close()
        finally:
            self.errors.close()
            self.output.close()


if __name__ == '__main__':
    main()
