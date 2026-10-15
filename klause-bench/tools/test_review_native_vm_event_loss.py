import struct
import unittest

import native_vm_event_loss as loss


class ReviewNativeVmEventLossTest(unittest.TestCase):
    def test_type_graph_preserves_signed_integer_metadata_without_relaxing_the_counter_guard(self):
        strings = b'\0int64_t\0__bt__event_loss_counter\0.data.event_loss_counter\0'
        types = struct.pack('<IIII', 1, 1 << 24, 8, 1 << 24 | 64)
        types += struct.pack('<IIIIII', 0, 3 << 24, 0, 1, 1, 1)
        types += struct.pack('<IIIIII', 0, 3 << 24, 0, 2, 1, 8)
        types += struct.pack('<IIII', strings.index(b'__bt__'), 14 << 24, 3, 1)
        types += struct.pack('<IIIIII', strings.index(b'.data'), 15 << 24 | 1, 64, 4, 0, 64)
        raw = struct.pack('<HBBIIIII', 0xeb9f, 1, 0, 24, 0, len(types), len(types), len(strings)) + types + strings

        graph = loss.btf_type_graph(raw, 5)

        self.assertEqual('.data.event_loss_counter', graph[0]['name'])
        self.assertEqual(64, graph[0]['members'][0]['bytes'])
        self.assertIn({'encoded': 1 << 24 | 64, 'encoding': 1, 'offset': 0, 'bits': 64},
                      [row['integer'] for row in graph if 'integer' in row])
        with self.assertRaisesRegex(ValueError, 'unsigned'):
            loss.btf_counter(raw, 5, 8)

    def test_type_graph_retains_missing_references_and_stops_at_visited_types(self):
        types = struct.pack('<IIIIII', 0, 8 << 24, 2, 0, 8 << 24, 1)
        raw = struct.pack('<HBBIIIII', 0xeb9f, 1, 0, 24, 0, len(types), len(types), 1) + types + b'\0'

        cycle = loss.btf_type_graph(raw, 1)
        absent = loss.btf_type_graph(raw, 9)

        self.assertEqual([1, 2], [row['id'] for row in cycle])
        self.assertEqual([{'id': 9, 'error': 'Absent BTF type ID'}], absent)


if __name__ == '__main__':
    unittest.main(verbosity=2)
