import io
import unittest
from unittest.mock import patch

import read_jvm_class_origins as origins


class ReadJvmClassOriginsTest(unittest.TestCase):
    def test_original_installed_path_and_hash_are_retained_without_reading_a_jar(self):
        manifest = {'version': 1, 'launcher': '/owned/cli/bin/cli',
                    'provenance': {'distribution': {'lib/core.jar': 'a' * 64}}}
        source = io.BytesIO(b'[time][info][class,load] p.C source: file:/owned/cli/lib/core.jar\n')

        result = origins.parse(source, manifest)

        self.assertTrue(result['completed'])
        self.assertEqual({'distributionPath': '/owned/cli', 'path': 'lib/core.jar', 'sha256': 'a' * 64},
                         result['records'][0]['recordedInstalledJar'])
        self.assertFalse(result['runtimeSourceBindingProven'])

    def test_equal_jar_names_outside_the_original_distribution_do_not_bind(self):
        manifest = {'version': 1, 'launcher': '/owned/cli/bin/cli',
                    'provenance': {'distribution': {'lib/core.jar': 'a' * 64}}}
        source = io.BytesIO(b'[time][info][class,load] p.C source: file:/other/lib/core.jar\n')

        result = origins.parse(source, manifest)

        self.assertIsNone(result['records'][0]['recordedInstalledJar'])
        self.assertEqual('file:/other/lib/core.jar', result['records'][0]['source'])

    def test_shared_generated_and_missing_manifest_origins_stay_unbound(self):
        for origin in ('shared objects file', '__JVM_LookupDefineClass__', 'file:/owned/cli/lib/core.jar'):
            with self.subTest(origin=origin):
                raw = ('[time][info][class,load] p.C source: ' + origin + '\n').encode()

                result = origins.parse(io.BytesIO(raw), None)

                self.assertIsNone(result['records'][0]['recordedInstalledJar'])
                self.assertEqual(origin, result['records'][0]['source'])
                self.assertEqual(['Original installed CLI manifest is absent'], result['exclusions'])

    def test_percent_encoded_paths_match_only_the_exact_recorded_path(self):
        manifest = {'version': 1, 'launcher': '/owned/with space/bin/cli',
                    'provenance': {'distribution': {'lib/core.jar': 'a' * 64}}}
        raw = b'[time][info][class,load] p/C source: file:/owned/with%20space/lib/core.jar\r\n'

        result = origins.parse(io.BytesIO(raw), manifest)

        self.assertEqual('p.C', result['records'][0]['className'])
        self.assertEqual('a' * 64, result['records'][0]['recordedInstalledJar']['sha256'])

    def test_query_fragment_authority_traversal_and_invalid_escapes_cannot_bind(self):
        for source in ('file:/owned/cli/lib/core.jar?q=1', 'file:/owned/cli/lib/core.jar#x',
                       'file://other/owned/cli/lib/core.jar', 'file:/owned/cli/lib/../lib/core.jar',
                       'file:/owned/cli/lib/%2e%2e/lib/core.jar', 'file:/owned/cli/lib/%00core.jar',
                       'file:/owned/cli/lib/%zzcore.jar', 'file:/owned/cli/lib/%ffcore.jar'):
            with self.subTest(source=source):
                self.assertIsNone(origins.jar_path(source))

    def test_malformed_original_distribution_metadata_is_rejected(self):
        for version, launcher, files in [(True, '/owned/bin/cli', {}), (1, 'relative/bin/cli', {}),
                                         (1, '/owned/bin/cli', {'../core.jar': 'a' * 64}),
                                         (1, '/owned/bin/cli', {'lib/core.jar': 'not-a-digest'})]:
            with self.subTest(version=version, launcher=launcher, files=files):
                with self.assertRaises(ValueError):
                    origins.parse(io.BytesIO(b''), {'version': version, 'launcher': launcher,
                                                   'provenance': {'distribution': files}})

    def test_unparsed_lines_are_retained_and_prevent_prefix_binding(self):
        manifest = {'version': 1, 'launcher': '/owned/cli/bin/cli',
                    'provenance': {'distribution': {'lib/core.jar': 'a' * 64}}}
        raw = b'[time][info][class,load] p.C source: file:/owned/cli/lib/core.jar\nunknown line\n'

        parsed = origins.parse(io.BytesIO(raw), manifest)
        result = origins.associate({'compilations': [{'eventIndex': 3, 'method': 'p.C.f()V'}]}, parsed)

        self.assertFalse(parsed['completed'])
        self.assertEqual([{'line': 2, 'text': 'unknown line'}], parsed['unparsedLines'])
        self.assertIsNone(result['classes'][0]['unambiguousRecordedInstalledJar'])

    def test_invalid_utf8_and_complete_input_bounds_refuse_partial_success(self):
        for raw, field, bound in [(b'\xff', 'MAX_LINES', 1), (b'a\nb\n', 'MAX_LINES', 1),
                                   (b'long line\n', 'MAX_LINE_BYTES', 4)]:
            with self.subTest(raw=raw), patch.object(origins, field, bound):
                with self.assertRaises((ValueError, UnicodeError)):
                    origins.parse(io.BytesIO(raw), None)

    def test_conflicting_class_origins_preserve_every_record_without_choosing_one(self):
        manifest = {'version': 1, 'launcher': '/owned/cli/bin/cli',
                    'provenance': {'distribution': {'lib/a.jar': 'a' * 64, 'lib/b.jar': 'b' * 64}}}
        raw = (b'[time][info][class,load] p.C source: file:/owned/cli/lib/a.jar\n'
               b'[time][info][class,load] p.C source: file:/owned/cli/lib/b.jar\n')

        parsed = origins.parse(io.BytesIO(raw), manifest)
        result = origins.associate({'compilations': [{'eventIndex': 1, 'method': 'p.C.f()V'}]}, parsed)

        self.assertEqual([0, 1], result['classes'][0]['originRecordIndices'])
        self.assertEqual(['a' * 64, 'b' * 64], [r['recordedInstalledJar']['sha256'] for r in parsed['records']])
        self.assertIsNone(result['classes'][0]['unambiguousRecordedInstalledJar'])

    def test_a_bound_record_cannot_hide_an_unbound_origin_for_the_same_class(self):
        manifest = {'version': 1, 'launcher': '/owned/cli/bin/cli',
                    'provenance': {'distribution': {'lib/core.jar': 'a' * 64}}}
        raw = (b'[time][info][class,load] p.C source: file:/owned/cli/lib/core.jar\n'
               b'[time][info][class,load] p.C source: __JVM_LookupDefineClass__\n')

        parsed = origins.parse(io.BytesIO(raw), manifest)
        result = origins.associate({'compilations': [{'eventIndex': 1, 'method': 'p.C.f()V'}]}, parsed)

        self.assertEqual([0, 1], result['classes'][0]['originRecordIndices'])
        self.assertIsNone(result['classes'][0]['unambiguousRecordedInstalledJar'])

    def test_all_root_and_inlining_class_references_include_missing_and_malformed_methods(self):
        census = {'compilations': [{'eventIndex': 4, 'method': 'p.C.f()V'}, {'eventIndex': 5, 'method': None}],
                  'inlining': [{'eventIndex': 6, 'caller': 'p.C.f()V', 'callee': 'q.Generated$1.g()V'}]}

        result = origins.associate(census, {'completed': True, 'records': []})

        self.assertEqual([{'eventIndex': 4, 'classIndex': 0}, {'eventIndex': 5, 'classIndex': None}],
                         result['compilationClassReferences'])
        self.assertEqual([{'eventIndex': 6, 'callerClassIndex': 0, 'calleeClassIndex': 1}],
                         result['inliningClassReferences'])
        self.assertEqual(['p.C', 'q.Generated$1'], [r['className'] for r in result['classes']])
        self.assertTrue(all(r['originRecordIndices'] == [] for r in result['classes']))

    def test_repeated_matching_origins_keep_each_occurrence_and_do_not_prove_loaded_bytes(self):
        manifest = {'version': 1, 'launcher': '/owned/cli/bin/cli',
                    'provenance': {'distribution': {'lib/core.jar': 'a' * 64}}}
        raw = b'[time][info][class,load] p.C source: file:/owned/cli/lib/core.jar\n' * 2

        parsed = origins.parse(io.BytesIO(raw), manifest)
        result = origins.associate({'compilations': [{'eventIndex': 1, 'method': 'p.C.f()V'}]}, parsed)

        self.assertEqual([0, 1], result['classes'][0]['originRecordIndices'])
        self.assertEqual('a' * 64, result['classes'][0]['unambiguousRecordedInstalledJar']['sha256'])
        self.assertFalse(result['classes'][0]['actualLoadedByteDigestProven'])
        self.assertFalse(result['classes'][0]['classLoaderIdentityProven'])
        self.assertFalse(result['acceptedSavingsCredit'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
