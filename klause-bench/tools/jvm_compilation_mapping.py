"""Bind separate phase declarations to immutable saved census and source metadata."""
from collections import Counter
import re

import jvm_compilation_events as events
import review_jvm_task_log_cohort as cohort


def original_metadata(selected, index, name, load_json, maximum):
    records = [value for value in index['originalMetadataFiles'].values() if value['path'] == name]
    if len(records) != 1 or name not in selected:
        raise ValueError('Original mapping metadata is missing or ambiguous: ' + name)
    record, path = records[0], selected[name]
    identity = {key: record['originalIdentity'][key] for key in ('size', 'sha256')}
    if cohort.file_identity(path) != record['compressed'] or cohort.gzip_identity(path, maximum) != identity:
        raise ValueError('Original mapping metadata differs from producer identity')
    return load_json(path, True), identity


def bind(declaration, selected, index, manifest, load_json, maximum):
    if declaration.get('schema') != 1 or type(declaration['schema']) is not int or \
            declaration.get('inputCensusArchive') != {key: manifest['archive'][key] for key in ('size', 'sha256')}:
        raise ValueError('Phase mapping schema or pinned census archive differs')
    original, family_identity = original_metadata(selected, index, 'original-compilation-families.json.gz',
                                                  load_json, maximum)
    builds, builds_identity = original_metadata(selected, index, 'original-study-builds.json.gz', load_json, maximum)
    if declaration.get('originalFamiliesIdentity') != family_identity or \
            declaration.get('originalStudyBuildsIdentity') != builds_identity:
        raise ValueError('Phase mapping original metadata identities differ')
    heads = declaration.get('sourceHeads')
    if not isinstance(heads, dict) or set(heads) != {'control', 'candidate'} or \
            not all(isinstance(value, str) and re.fullmatch('[0-9a-f]{40}', value) for value in heads.values()) or \
            not isinstance(builds, list) or len(builds) != 2:
        raise ValueError('Phase mapping recorded source heads are malformed')
    represented = set()
    for build in builds:
        arm = build.get('arm')
        if arm not in heads or arm in represented or build.get('actualHead') != heads[arm] or \
                build.get('expectedHead') != heads[arm]:
            raise ValueError('Phase mapping differs from original recorded source heads')
        represented.add(arm)
    families = declaration.get('families')
    if not isinstance(original, dict) or not isinstance(families, dict) or set(families) != set(original):
        raise ValueError('Phase mapping must retain every original family')
    for name, definition in families.items():
        if not isinstance(definition, dict) or set(definition) != {'methods', 'ordinaryPhase', 'ordinaryPhaseAll'}:
            raise ValueError('Malformed phase mapping family')
        methods = definition['methods']
        if not isinstance(methods, list) or not methods or \
                not all(isinstance(value, str) and value for value in methods) or \
                len(set(methods)) != len(methods) or not set(original[name]['methods']) <= set(methods):
            raise ValueError('Phase mapping removed original supporting method selectors')
        for key in ('ordinaryPhase', 'ordinaryPhaseAll'):
            phases = definition[key]
            if not isinstance(phases, dict) or not set(phases) <= set(heads) or \
                    not all(isinstance(values, list) and all(isinstance(value, str) and value for value in values)
                            and len(set(values)) == len(values) for values in phases.values()):
                raise ValueError('Malformed mapped ordinary phase declaration')
        for arm in heads:
            if not set(definition['ordinaryPhaseAll'].get(arm, [])) <= set(definition['ordinaryPhase'].get(arm, [])):
                raise ValueError('Required mapped ordinary phases are undeclared')
    return families


def snapshots(case, selected, load_json, maximum):
    inputs = case.get('xmlInputs', [])
    if len(inputs) != 1 or not inputs[0]['originalMember'].endswith('/' + case['caseDirectory'] + '/hotspot.xml'):
        return {}, ['Mapped comparison has no unique original case XML input']
    retained = inputs[0]['retainedMetadata']
    path = selected.get(retained['path'])
    if path is None or cohort.file_identity(path) != retained['compressed'] or \
            cohort.gzip_identity(path, maximum) != retained['uncompressed']:
        raise ValueError('Retained XML metadata differs from producer identity')
    value = load_json(path, True)
    if inputs[0].get('completed') is not True or value.get('completed') is not True or value.get('error') is not None:
        return {}, ['Mapped comparison has incomplete original XML metadata']
    return value['metadata'], []


def reform(report, families, arm, xml):
    census = events.Census()
    census.roots = report.get('compilations', [])
    census.inlining = report.get('inlining', [])
    census.counts = Counter(report.get('eventCounts', {}))
    return dict(report, **census.finish(families, arm, xml))
