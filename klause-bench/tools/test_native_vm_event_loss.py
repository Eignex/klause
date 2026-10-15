import struct
from copy import deepcopy
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import native_vm_event_loss as loss


class NativeVmEventLossTest(unittest.TestCase):
    def test_counter_snapshot_includes_the_highest_cpu_slot(self):
        for slots in (1, 2, 8):
            with self.subTest(slots=slots):
                raw = b'\0' * ((slots - 1) * 8) + (7).to_bytes(8, 'little')

                result = loss.snapshot(raw, slots)

                self.assertEqual(7, result['sum'])
                self.assertEqual(7, result['slots'][-1]['value'])
                self.assertEqual(slots - 1, result['slots'][-1]['index'])
                self.assertEqual(raw.hex(), result['rawHex'])

    def test_counter_snapshots_require_exact_bounded_slot_widths(self):
        for raw, slots in [(b'', 0), (b'\0' * 7, 1), (b'\0' * 9, 1), (b'', 4097), (b'', True)]:
            with self.subTest(slots=slots, size=len(raw)), self.assertRaises(ValueError):
                loss.snapshot(raw, slots)

    def test_possible_cpu_ranges_include_every_masked_slot(self):
        for text, expected in [('0\n', 1), ('0-2\n', 4), ('0-7\n', 8), ('0-1,2\n', 4), ('0,1\n', 2)]:
            with self.subTest(text=text):
                self.assertEqual(expected, loss.cpu_slots(text))

    def test_possible_cpu_ranges_refuse_gaps_duplicates_and_unbounded_ids(self):
        for text in ['', '1-3', '0-1,1-2', '0,2', '2-0', '0-4096', '-1', '0,']:
            with self.subTest(text=text), self.assertRaises(ValueError):
                loss.cpu_slots(text)

    def test_btf_binding_requires_the_named_complete_unsigned_cpu_array(self):
        variants = [(True, 8, 0, 64, 0, 1, 5), (False, 7, 0, 64, 0, 1, 5),
                    (False, 8, 0, 64, 4, 1, 5), (False, 8, 1, 64, 0, 1, 5),
                    (False, 8, 0, 32, 0, 1, 5), (False, 8, 0, 64, 0, 2, 5),
                    (False, 8, 0, 64, 0, 1, 4)]
        for accepted, cpus, signed, bits, offset, variables, section_id in variants:
            with self.subTest(cpus=cpus, signed=signed, bits=bits, offset=offset,
                              variables=variables, section_id=section_id):
                strings = b'\0uint64_t\0__bt__event_loss_counter\0.data.event_loss_counter\0'
                types = struct.pack('<IIII', 1, 1 << 24, 8, signed << 24 | bits)
                types += struct.pack('<IIIIII', 0, 3 << 24, 0, 1, 1, 1)
                types += struct.pack('<IIIIII', 0, 3 << 24, 0, 2, 1, cpus)
                types += struct.pack('<IIII', strings.index(b'__bt__'), 14 << 24, 3, 1)
                types += struct.pack('<III', strings.index(b'.data'), 15 << 24 | variables, 64)
                types += struct.pack('<III', 4, offset, 64) * variables
                raw = struct.pack('<HBBIIIII', 0xeb9f, 1, 0, 24, 0, len(types), len(types), len(strings))
                raw += types + strings

                if accepted:
                    result = loss.btf_counter(raw, section_id, 8)
                    self.assertEqual('.data.event_loss_counter', result['section'])
                    self.assertEqual('__bt__event_loss_counter', result['variable'])
                    self.assertEqual(64, result['storageBytes'])
                    self.assertEqual(0, result['offset'])
                else:
                    with self.assertRaises(ValueError):
                        loss.btf_counter(raw, section_id, 8)

    def test_malformed_btf_is_rejected_before_counter_access(self):
        for raw in [b'', b'\0' * 24, struct.pack('<HBBIIIII', 0xeb9f, 1, 0, 24, 0, 12, 12, 1),
                    struct.pack('<HBBIIIII', 0xeb9f, 1, 0, 24, 0, 12, 12, 1) + b'\0' * 13]:
            with self.subTest(size=len(raw)), self.assertRaises(ValueError):
                loss.btf_counter(raw, 1, 1)

    def test_complete_counter_reports_retain_zero_and_nonzero_highest_slots(self):
        strings = b'\0uint64_t\0__bt__event_loss_counter\0.data.event_loss_counter\0'
        types = struct.pack('<IIII', 1, 1 << 24, 8, 64)
        types += struct.pack('<IIIIII', 0, 3 << 24, 0, 1, 1, 1)
        types += struct.pack('<IIIIII', 0, 3 << 24, 0, 2, 1, 8)
        types += struct.pack('<IIII', strings.index(b'__bt__'), 14 << 24, 3, 1)
        types += struct.pack('<IIIIII', strings.index(b'.data'), 15 << 24 | 1, 64, 4, 0, 64)
        btf = struct.pack('<HBBIIIII', 0xeb9f, 1, 0, 24, 0, len(types), len(types), len(strings)) + types + strings
        binding = loss.btf_counter(btf, 5, 8)
        zero = {'rawHex': '00' * 64, 'sum': 0,
                'slots': [dict(index=index, rawHex='00' * 8, value=0) for index in range(8)]}
        nonzero = deepcopy(zero)
        nonzero.update(rawHex='00' * 56 + '0100000000000000', sum=1)
        nonzero['slots'][-1].update(rawHex='0100000000000000', value=1)
        observer = {'pid': 100, 'startTicks': '9', 'executableSha256': 'a' * 64,
                    'comm': 'bpftrace', 'mountNamespace': 'mnt:[1]'}
        info = dict(id=7, type=2, key_size=4, value_size=64, max_entries=1, btf_id=8, btf_value_type_id=5)
        ready = dict(observer=observer, sourceSha256='b' * 64, possibleCpus='0-7\n', map=info, binding=binding,
                     host=dict(system='Linux', machine='x86_64', byteorder='little', pointerBytes=8, release='retained'),
                     observerDescriptor='map_id:\t7\n', initial=zero,
                     mapInspections=[dict(map=info, descriptor='map_id:\t7\n', btfSha256=binding['btfSha256'])],
                     controls=dict(passed=True, map={**info, 'id': 9}, zero=zero, highestSlotNonzero=nonzero))
        base = dict(completed=True, ready=ready,
                    result=dict(observer=observer, map=info, binding=binding, observerExited=True,
                                allSlotsRead=True, final=zero, finalCounterZero=True))
        for variant in ['zero', 'nonzero', 'claimed-zero', 'alias', 'missing-slot', 'observer', 'ambiguous']:
            with self.subTest(variant=variant):
                document = deepcopy(base)
                if variant in ('nonzero', 'claimed-zero'):
                    document['result']['final'] = nonzero
                    document['result']['finalCounterZero'] = variant == 'claimed-zero'
                elif variant == 'alias':
                    document['ready']['controls']['map']['id'] = 7
                elif variant == 'missing-slot':
                    document['result']['final'] = {**zero, 'rawHex': '00' * 56}
                elif variant == 'observer':
                    document['result']['observer'] = {**observer, 'startTicks': '10'}
                elif variant == 'ambiguous':
                    document['ready']['mapInspections'] *= 2

                if variant in ('zero', 'nonzero'):
                    result = loss.verify_report(document, btf, observer, 'b' * 64)
                    self.assertTrue(result['finalLossCounterProven'])
                    self.assertEqual(variant == 'zero', result['finalLossCounterZeroProven'])
                    self.assertFalse(result['globalLossFreeCaptureProven'])
                else:
                    with self.assertRaises(ValueError):
                        loss.verify_report(document, btf, observer, 'b' * 64)

    def test_kernel_map_identity_must_match_the_original_observer_descriptor(self):
        api = Mock()
        api.open_id.return_value = 70
        api.map_info.return_value = {'id': 6}
        with patch.object(Path, 'iterdir', return_value=[Path('/proc/100/fdinfo/7')]), \
                patch.object(Path, 'read_text', return_value='map_id:\t5\n'), \
                patch.object(loss.os, 'close') as close, self.assertRaisesRegex(ValueError, 'descriptor differs'):
            loss.bind_counter(api, 100, 8)

        close.assert_called_once_with(70)
        api.btf.assert_not_called()
        api.lookup.assert_not_called()

    def test_multiple_btf_bound_counter_maps_are_rejected_without_reading_values(self):
        api = Mock()
        api.open_id.side_effect = [70, 71]
        api.map_info.side_effect = [dict(id=identifier, type=2, key_size=4, max_entries=1,
                                         btf_id=1, btf_value_type_id=5, value_size=64) for identifier in (5, 6)]
        api.btf.return_value = b'bound BTF bytes'
        with patch.object(Path, 'iterdir', return_value=[Path('/proc/100/fdinfo/7'), Path('/proc/100/fdinfo/8')]), \
                patch.object(Path, 'read_text', side_effect=['map_id:\t5\n', 'map_id:\t6\n']), \
                patch.object(loss, 'btf_counter', return_value={'variable': '__bt__event_loss_counter'}), \
                patch.object(loss.os, 'close') as close, self.assertRaisesRegex(ValueError, 'ambiguous'):
            loss.bind_counter(api, 100, 8)

        self.assertEqual([70, 71], [call.args[0] for call in close.call_args_list])
        api.lookup.assert_not_called()


if __name__ == '__main__':
    unittest.main(verbosity=2)
