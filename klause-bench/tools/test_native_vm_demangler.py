import ctypes
from pathlib import Path
import platform
import tempfile
import unittest
from unittest.mock import Mock, patch

import native_vm_demangler as demangler


class NativeVmDemanglerTest(unittest.TestCase):
    def test_non_cpp_external_names_do_not_call_the_native_api(self):
        query, release = Mock(), Mock()

        for name in [b'i', b'__cxa_demangle', b'plain', b'']:
            with self.subTest(name=name):
                result = demangler.demangle(name, query, release)

                self.assertEqual('not an external C++ mangling', result['disposition'])
                self.assertIsNone(result['status'])
        query.assert_not_called()
        release.assert_not_called()

    def test_embedded_terminators_and_oversized_inputs_are_rejected_before_calling(self):
        query, release = Mock(), Mock()

        for name in [b'_Z1fv\0', b'_Z' + b'a' * demangler.MAX_INPUT_BYTES]:
            with self.subTest(size=len(name)), self.assertRaises(ValueError):
                demangler.demangle(name, query, release)

        query.assert_not_called()
        release.assert_not_called()

    def test_native_rejection_status_is_retained_without_reading_memory(self):
        for error in [-1, -2, -3]:
            with self.subTest(status=error):
                def reject(name, buffer, capacity, status):
                    status._obj.value = error
                    return None

                release = Mock()
                with patch.object(ctypes, 'string_at') as read:
                    result = demangler.demangle(b'_Zinvalid', reject, release)

                self.assertEqual(error, result['status'])
                self.assertEqual('demangler rejected the external name', result['disposition'])
                read.assert_not_called()
                release.assert_not_called()

    def test_invalid_output_states_are_rejected_without_memory_access(self):
        for pointer, status_value, capacity_value in [(None, 0, 0), (0x1000, -2, 16),
                                                     (0x1000, 0, 0),
                                                     (0x1000, 0, demangler.MAX_OUTPUT_BYTES + 1)]:
            with self.subTest(pointer=pointer, status=status_value, capacity=capacity_value):
                def respond(name, buffer, capacity, status):
                    capacity._obj.value = capacity_value
                    status._obj.value = status_value
                    return pointer

                release = Mock()
                with patch.object(ctypes, 'string_at') as read, self.assertRaises(ValueError):
                    demangler.demangle(b'_Z1fv', respond, release)

                read.assert_not_called()
                if pointer:
                    release.assert_called_once_with(pointer)
                else:
                    release.assert_not_called()

    def test_output_read_or_decode_failure_still_releases_the_native_buffer(self):
        def respond(name, buffer, capacity, status):
            capacity._obj.value = 4
            status._obj.value = 0
            return 0x1000

        for raw in [b'none', b'\xff\0\0\0']:
            with self.subTest(raw=raw):
                release = Mock()
                with patch.object(ctypes, 'string_at', return_value=raw), self.assertRaises(ValueError):
                    demangler.demangle(b'_Z1fv', respond, release)

                release.assert_called_once_with(0x1000)

        release = Mock()
        with patch.object(ctypes, 'string_at', side_effect=OSError('read failed')), \
                self.assertRaises(demangler.DemanglingError) as failure:
            demangler.demangle(b'_Z1fv', respond, release)
        release.assert_called_once_with(0x1000)
        self.assertEqual(0, failure.exception.context['status'])
        self.assertEqual(4, failure.exception.context['allocatedBytes'])
        self.assertEqual('OSError', failure.exception.context['error']['type'])

    def test_success_uses_only_the_reported_allocation_and_releases_it(self):
        def respond(name, buffer, capacity, status):
            self.assertIsNone(buffer)
            capacity._obj.value = 8
            status._obj.value = 0
            return 0x1000

        release = Mock()
        with patch.object(ctypes, 'string_at', return_value=b'f()\0abcd') as read:
            result = demangler.demangle(b'_Z1fv', respond, release)

        self.assertEqual('f()', result['demangled'])
        self.assertEqual(0, result['status'])
        self.assertEqual(b'f()'.hex(), result['demangledHex'])
        read.assert_called_once_with(0x1000, 8)
        release.assert_called_once_with(0x1000)

    @unittest.skipUnless(platform.system() == 'Linux' and platform.machine() == 'x86_64',
                         'Provider binding supports Linux x86_64')
    def test_public_demangler_binds_its_actual_provider_before_use(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            reader = demangler.NativeDemangler(out)

            result = reader.read(b'_Z1fv')
            reader.verify_unchanged()

            self.assertEqual('f()', result['demangled'])
            self.assertEqual('__cxa_demangle', reader.provenance['symbol'])
            self.assertTrue(reader.provenance['loadedModule']['loadedProgramHeadersByteExactToFile'])
            self.assertTrue((out / 'demangler-provider.json').is_file())


if __name__ == '__main__':
    unittest.main(verbosity=2)
