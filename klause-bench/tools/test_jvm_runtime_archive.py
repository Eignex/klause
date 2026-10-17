import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

import jvm_runtime_archive as runtime


class JvmRuntimeArchiveTest(unittest.TestCase):
    def test_complete_runtime_matches_before_only_image_inputs_are_staged(self):
        files = {'lib/modules': b'modules', 'lib/jrt-fs.jar': b'provider', 'release': b'release', 'bin/java': b'java'}
        with tempfile.TemporaryDirectory() as directory:
            archive, destination = Path(directory) / 'runtime.tar.gz', Path(directory) / 'runtime'
            with tarfile.open(archive, 'w:gz') as stream:
                for name, raw in files.items():
                    member = tarfile.TarInfo('image/' + name)
                    member.size = len(raw)
                    stream.addfile(member, io.BytesIO(raw))
            expected = {name: hashlib.sha256(raw).hexdigest() for name, raw in files.items()}
            report = {}

            runtime.prepare_runtime({'provenance': {'runtime': expected}}, archive, runtime.digest(archive), destination, report)

            self.assertTrue(report['completed'])
            self.assertEqual(expected, report['actualRuntime'])
            self.assertEqual(b'modules', (destination / 'lib/modules').read_bytes())
            self.assertFalse((destination / 'bin/java').exists())

    def test_file_links_retain_their_original_runtime_inventory_paths(self):
        for kind, target in [(tarfile.SYMTYPE, '../release'), (tarfile.LNKTYPE, 'image/release')]:
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as directory:
                archive, destination = Path(directory) / 'runtime.tar.gz', Path(directory) / 'runtime'
                with tarfile.open(archive, 'w:gz') as stream:
                    for name in runtime.IMAGE_FILES:
                        member = tarfile.TarInfo('image/' + name)
                        member.size = 1
                        stream.addfile(member, io.BytesIO(b'x'))
                    member = tarfile.TarInfo('image/legal/copy')
                    member.type, member.linkname = kind, target
                    stream.addfile(member)
                expected = {name: hashlib.sha256(b'x').hexdigest() for name in (*runtime.IMAGE_FILES, 'legal/copy')}

                report = {}
                runtime.prepare_runtime({'provenance': {'runtime': expected}}, archive, runtime.digest(archive), destination, report)

                self.assertEqual(expected, report['actualRuntime'])
                self.assertFalse((destination / 'legal').exists())

    def test_missing_extra_or_changed_runtime_files_prevent_staging(self):
        for variant in ['missing', 'extra', 'changed']:
            with self.subTest(variant=variant), tempfile.TemporaryDirectory() as directory:
                archive, destination = Path(directory) / 'runtime.tar.gz', Path(directory) / 'runtime'
                with tarfile.open(archive, 'w:gz') as stream:
                    for name in (*runtime.IMAGE_FILES, *(('bin/java',) if variant == 'extra' else ())):
                        member = tarfile.TarInfo('image/' + name)
                        member.size = 1
                        stream.addfile(member, io.BytesIO(b'x'))
                expected = {name: hashlib.sha256(b'x').hexdigest() for name in runtime.IMAGE_FILES}
                if variant == 'missing':
                    expected['bin/java'] = hashlib.sha256(b'x').hexdigest()
                elif variant == 'changed':
                    expected['release'] = 'a' * 64
                report = {}

                with self.assertRaises(ValueError):
                    runtime.prepare_runtime({'provenance': {'runtime': expected}}, archive, runtime.digest(archive), destination, report)

                self.assertFalse(report['completed'])
                self.assertFalse(destination.exists())

    def test_unsafe_or_duplicate_archive_paths_prevent_staging(self):
        for name in ['../outside', '/outside', 'image\\outside', 'image/release']:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                archive, destination = Path(directory) / 'runtime.tar.gz', Path(directory) / 'runtime'
                with tarfile.open(archive, 'w:gz') as stream:
                    for entry in ['image/release', name]:
                        stream.addfile(tarfile.TarInfo(entry))
                expected = {key: 'a' * 64 for key in runtime.IMAGE_FILES}

                with self.assertRaises(ValueError):
                    runtime.prepare_runtime({'provenance': {'runtime': expected}}, archive, runtime.digest(archive), destination, {})

                self.assertFalse(destination.exists())

    def test_cyclic_or_external_runtime_links_prevent_staging(self):
        for target in ['modules', '/outside', '../../../outside']:
            with self.subTest(target=target), tempfile.TemporaryDirectory() as directory:
                archive, destination = Path(directory) / 'runtime.tar.gz', Path(directory) / 'runtime'
                with tarfile.open(archive, 'w:gz') as stream:
                    member = tarfile.TarInfo('image/lib/modules')
                    member.type, member.linkname = tarfile.SYMTYPE, target
                    stream.addfile(member)
                expected = {key: 'a' * 64 for key in runtime.IMAGE_FILES}

                with self.assertRaises(ValueError):
                    runtime.prepare_runtime({'provenance': {'runtime': expected}}, archive, runtime.digest(archive), destination, {})

                self.assertFalse(destination.exists())

    def test_archive_member_and_expansion_bounds_prevent_staging(self):
        for bound in ['MAX_ARCHIVE_BYTES', 'MAX_MEMBERS', 'MAX_FILE_BYTES', 'MAX_EXPANDED_BYTES']:
            with self.subTest(bound=bound), tempfile.TemporaryDirectory() as directory:
                archive, destination = Path(directory) / 'runtime.tar.gz', Path(directory) / 'runtime'
                with tarfile.open(archive, 'w:gz') as stream:
                    member = tarfile.TarInfo('image/release')
                    member.size = 1
                    stream.addfile(member, io.BytesIO(b'x'))
                expected = {key: 'a' * 64 for key in runtime.IMAGE_FILES}

                with patch.object(runtime, bound, 0), self.assertRaises(ValueError):
                    runtime.prepare_runtime({'provenance': {'runtime': expected}}, archive, runtime.digest(archive), destination, {})

                self.assertFalse(destination.exists())

    def test_wrong_archive_identity_prevents_staging(self):
        with tempfile.TemporaryDirectory() as directory:
            archive, destination = Path(directory) / 'runtime.tar.gz', Path(directory) / 'runtime'
            archive.write_bytes(b'not the retained runtime')

            with self.assertRaises(ValueError):
                runtime.prepare_runtime({'provenance': {'runtime': {}}}, archive, 'a' * 64, destination, {})

            self.assertFalse(destination.exists())

    def test_complete_saved_capture_authenticates_every_member_before_all_original_jars_are_staged(self):
        distribution = {'lib/first.jar': hashlib.sha256(b'first').hexdigest(),
                        'lib/second.jar': hashlib.sha256(b'second').hexdigest(),
                        'bin/cli': hashlib.sha256(b'launcher').hexdigest()}
        manifest = {'provenance': {'distribution': distribution}}
        match = {'matchesOriginal': True, 'expected': distribution, 'actual': distribution}
        files = {'captured-original-manifest.json': json.dumps(manifest).encode(),
                 'completion.json': json.dumps({'completed': True, 'requestedSourceHead': 'a' * 40,
                    'allInstalledDistributionBytesMatchedBeforeInspection': True}).encode(),
                 'control-distribution-match.json': json.dumps(match).encode(),
                 'candidate-distribution-match.json': json.dumps(match).encode(),
                 'private-installed-jars/lib/first.jar': b'first',
                 'private-installed-jars/lib/second.jar': b'second', 'unselected.txt': b'unselected'}
        with tempfile.TemporaryDirectory() as directory:
            archive, destination = Path(directory) / 'saved.tar.gz', Path(directory) / 'distribution'
            with tarfile.open(archive, 'w:gz') as stream:
                for name, raw in files.items():
                    member = tarfile.TarInfo(name)
                    member.size = len(raw)
                    stream.addfile(member, io.BytesIO(raw))
            expected = {name: {'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}
                        for name, raw in files.items()}
            capture = {'schema': 1, 'archiveBytes': archive.stat().st_size,
                       'archiveSha256': runtime.digest(archive), 'files': expected}
            report = {}

            runtime.prepare_saved_distribution(manifest, capture, archive, runtime.digest(archive),
                expected['captured-original-manifest.json']['sha256'], 'a' * 40, destination, report)

            self.assertTrue(report['completed'])
            self.assertEqual(expected, report['verifiedCaptureMembers'])
            self.assertEqual({'lib/first.jar': b'first', 'lib/second.jar': b'second'},
                {path.relative_to(destination).as_posix(): path.read_bytes()
                 for path in destination.rglob('*') if path.is_file()})

    def test_invalid_saved_capture_never_exposes_a_staged_jar_prefix(self):
        variants = ['archive', 'missing', 'extra', 'changed', 'duplicate', 'link', 'unsafe',
                    'jar-identity', 'extra-jar', 'source', 'completion', 'original-distribution',
                    'control', 'candidate', 'control-map', 'candidate-map',
                    'MAX_ARCHIVE_BYTES', 'MAX_EXPANDED_BYTES', 'MAX_MEMBERS', 'MAX_FILE_BYTES', 'MAX_JSON_BYTES']
        for variant in variants:
            with self.subTest(variant=variant), tempfile.TemporaryDirectory() as directory:
                distribution = {'lib/first.jar': hashlib.sha256(b'first').hexdigest()}
                if variant == 'jar-identity':
                    distribution['lib/first.jar'] = 'b' * 64
                manifest = {'provenance': {'distribution': distribution}}
                match = {'matchesOriginal': True, 'expected': distribution, 'actual': distribution}
                completion = {'completed': variant != 'completion', 'requestedSourceHead': 'a' * 40,
                              'allInstalledDistributionBytesMatchedBeforeInspection': variant != 'original-distribution'}
                if variant == 'source':
                    completion['requestedSourceHead'] = 'b' * 40
                files = {'captured-original-manifest.json': json.dumps(manifest).encode(),
                         'completion.json': json.dumps(completion).encode(),
                         'control-distribution-match.json': json.dumps(match).encode(),
                         'candidate-distribution-match.json': json.dumps(match).encode(),
                         'private-installed-jars/lib/first.jar': b'first', 'unselected.txt': b'unselected'}
                if variant in ('control', 'candidate'):
                    files[variant + '-distribution-match.json'] = json.dumps(dict(match, matchesOriginal=False)).encode()
                if variant in ('control-map', 'candidate-map'):
                    files[variant.split('-')[0] + '-distribution-match.json'] = json.dumps(
                        dict(match, actual={'lib/first.jar': 'b' * 64})).encode()
                if variant == 'extra-jar':
                    files['private-installed-jars/lib/extra.jar'] = b'extra'
                expected = {name: {'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}
                            for name, raw in files.items()}
                entries = list(files.items())
                if variant == 'missing':
                    entries = entries[:-1]
                elif variant in ('extra', 'duplicate', 'link', 'unsafe'):
                    name = {'extra': 'extra.txt', 'duplicate': 'unselected.txt',
                            'link': 'linked', 'unsafe': '../outside'}[variant]
                    entries.append((name, b'x'))
                elif variant == 'changed':
                    entries[-1] = ('unselected.txt', b'UNSELECTED')
                archive, destination = Path(directory) / 'saved.tar.gz', Path(directory) / 'distribution'
                with tarfile.open(archive, 'w:gz') as stream:
                    for name, raw in entries:
                        member = tarfile.TarInfo(name)
                        if variant == 'link' and name == 'linked':
                            member.type, member.linkname = tarfile.SYMTYPE, 'unselected.txt'
                            stream.addfile(member)
                        else:
                            member.size = len(raw)
                            stream.addfile(member, io.BytesIO(raw))
                archive_hash = runtime.digest(archive)
                capture = {'schema': 1, 'archiveBytes': archive.stat().st_size,
                           'archiveSha256': archive_hash, 'files': expected}
                report = {}
                bound = variant if variant.startswith('MAX_') else 'MAX_MEMBERS'
                limit = 0 if variant.startswith('MAX_') else runtime.MAX_MEMBERS

                with patch.object(runtime, bound, limit), self.assertRaises(ValueError):
                    runtime.prepare_saved_distribution(manifest, capture, archive,
                        'c' * 64 if variant == 'archive' else archive_hash,
                        expected['captured-original-manifest.json']['sha256'], 'a' * 40, destination, report)

                self.assertFalse(report['completed'])
                self.assertFalse(destination.exists())

    def test_only_complete_original_two_arm_study_archives_expose_both_cli_jar_sets(self):
        variants = [None, 'archive', 'missing', 'extra', 'changed', 'duplicate', 'link', 'unsafe',
                    'directory', 'source', 'request', 'missing-build', 'duplicate-arm',
                    'provenance-hash', 'harness-provenance-hash', 'distribution', 'harness-distribution',
                    'extra-jar', 'missing-jar', 'duplicate-json-key', 'schema', 'size', 'manifest-sha',
                    'member-hash', 'boolean-size', 'file-prefix',
                    'MAX_ARCHIVE_BYTES', 'MAX_EXPANDED_BYTES', 'MAX_MEMBERS', 'MAX_FILE_BYTES', 'MAX_JSON_BYTES']
        for variant in variants:
            with self.subTest(variant=variant), tempfile.TemporaryDirectory() as directory:
                heads = {'control': 'a' * 40, 'candidate': 'b' * 40}
                files = {'unselected.txt': b'unselected'}
                builds = []
                for arm, head in heads.items():
                    retained = {}
                    build = {'arm': arm, 'expectedHead': head, 'actualHead': head,
                             'cli': '/original/' + arm + '/cli'}
                    for kind in ('cli', 'harness'):
                        contents = {'lib/first.jar': b'first', 'bin/cli': b'launcher'}
                        retained[kind] = {name: {'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}
                                          for name, raw in contents.items()}
                        original = {'launcher': build['cli'], 'provenance': {'distribution': {
                            name: item['sha256'] for name, item in retained[kind].items()}}}
                        suffix = '-provenance.json' if kind == 'cli' else '-harness-provenance.json'
                        files[arm + suffix] = json.dumps(original).encode()
                        key = 'provenanceSha256' if kind == 'cli' else 'harnessProvenanceSha256'
                        build[key] = hashlib.sha256(files[arm + suffix]).hexdigest()
                        files.update({'private-installed/' + arm + '/' + kind + '/' + name: raw
                                      for name, raw in contents.items()})
                    if arm == 'control' and variant in ('distribution', 'harness-distribution'):
                        retained['cli' if variant == 'distribution' else 'harness']['lib/first.jar']['sha256'] = 'c' * 64
                    files[arm + '-retained-distributions.json'] = json.dumps(retained).encode()
                    builds.append(build)
                if variant == 'source':
                    builds[0]['actualHead'] = 'c' * 40
                elif variant == 'missing-build':
                    builds.pop()
                elif variant == 'duplicate-arm':
                    builds[1]['arm'] = 'control'
                elif variant == 'provenance-hash':
                    builds[0]['provenanceSha256'] = 'c' * 64
                elif variant == 'harness-provenance-hash':
                    builds[0]['harnessProvenanceSha256'] = 'c' * 64
                files['study-builds.json'] = json.dumps(builds).encode()
                files['allocation-request.json'] = json.dumps(dict(heads, control='c' * 40)
                    if variant == 'request' else heads).encode()
                if variant == 'duplicate-json-key':
                    files['allocation-request.json'] = ('{"control":"' + heads['control'] +
                        '","control":"' + heads['control'] + '","candidate":"' + heads['candidate'] + '"}').encode()
                elif variant == 'extra-jar':
                    files['private-installed/control/cli/lib/extra.jar'] = b'extra'
                elif variant == 'missing-jar':
                    del files['private-installed/control/cli/lib/first.jar']
                elif variant == 'file-prefix':
                    files['unselected.txt/child'] = b'child'
                expected = {name: {'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}
                            for name, raw in files.items()}
                if variant == 'member-hash':
                    expected['unselected.txt']['sha256'] = 'c' * 64
                elif variant == 'boolean-size':
                    expected['unselected.txt']['bytes'] = True
                entries = list(files.items())
                if variant == 'missing':
                    entries = [(name, raw) for name, raw in entries if name != 'unselected.txt']
                elif variant == 'changed':
                    entries = [(name, b'UNSELECTED' if name == 'unselected.txt' else raw) for name, raw in entries]
                elif variant in ('extra', 'duplicate', 'link', 'unsafe', 'directory'):
                    entries.append(({'extra': 'extra.txt', 'duplicate': 'unselected.txt', 'link': 'linked',
                                     'unsafe': '../outside', 'directory': 'unselected.txt'}[variant], b'x'))
                archive, destination = Path(directory) / 'study.tar.gz', Path(directory) / 'distribution'
                with tarfile.open(archive, 'w:gz') as stream:
                    for index, (name, raw) in enumerate(entries):
                        member = tarfile.TarInfo(name)
                        if variant == 'link' and name == 'linked':
                            member.type, member.linkname = tarfile.SYMTYPE, 'unselected.txt'
                            stream.addfile(member)
                        elif variant == 'directory' and index == len(entries) - 1:
                            member.type = tarfile.DIRTYPE
                            stream.addfile(member)
                        else:
                            member.size = len(raw)
                            stream.addfile(member, io.BytesIO(raw))
                archive_hash = runtime.digest(archive)
                capture = {'schema': 1, 'archiveBytes': archive.stat().st_size,
                           'archiveSha256': archive_hash, 'files': expected}
                if variant == 'schema':
                    capture['schema'] = True
                elif variant == 'size':
                    capture['archiveBytes'] += 1
                elif variant == 'manifest-sha':
                    capture['archiveSha256'] = 'c' * 64
                report = {}
                bound = variant if variant and variant.startswith('MAX_') else 'MAX_MEMBERS'
                limit = 0 if variant and variant.startswith('MAX_') else runtime.MAX_MEMBERS

                if variant is None:
                    runtime.prepare_study_distributions(capture, archive, archive_hash, heads, destination, report)

                    self.assertTrue(report['completed'])
                    self.assertEqual(expected, report['verifiedCaptureMembers'])
                    self.assertEqual({'control/lib/first.jar': b'first', 'candidate/lib/first.jar': b'first'},
                        {path.relative_to(destination).as_posix(): path.read_bytes()
                         for path in destination.rglob('*') if path.is_file()})
                else:
                    with patch.object(runtime, bound, limit), self.assertRaises(ValueError):
                        runtime.prepare_study_distributions(capture, archive,
                            'c' * 64 if variant == 'archive' else archive_hash, heads, destination, report)

                    self.assertFalse(report['completed'])
                    self.assertFalse(destination.exists())


if __name__ == '__main__':
    unittest.main(verbosity=2)
