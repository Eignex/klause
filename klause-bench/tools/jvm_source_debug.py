"""Bounded SourceDebugExtension decoding and lossless resolved-SMAP candidates."""
import base64
import binascii
import hashlib
import re

MAX_PAYLOAD_BYTES = 16 * 1024 * 1024
MAX_SECTIONS = 4096
MAX_ITEMS = 100_000
MAX_NUMBER = 2 ** 31 - 1


class SourceDebugLimit(ValueError):
    pass


def modified_utf8(raw):
    if len(raw) > MAX_PAYLOAD_BYTES:
        raise SourceDebugLimit('Source-debug payload exceeds bounds')
    units, index = bytearray(), 0
    while index < len(raw):
        first = raw[index]
        width = 1 if 1 <= first <= 0x7f else 2 if 0xc0 <= first <= 0xdf else 3 if 0xe0 <= first <= 0xef else 0
        if not width or index + width > len(raw):
            raise ValueError('Invalid modified UTF-8 lead or truncated unit')
        value = first & (0x7f if width == 1 else 0x1f if width == 2 else 0x0f)
        for following in raw[index + 1:index + width]:
            if following & 0xc0 != 0x80:
                raise ValueError('Invalid modified UTF-8 continuation')
            value = (value << 6) | (following & 0x3f)
        if width == 2 and value < 0x80 and raw[index:index + 2] != b'\xc0\x80' or \
                width == 3 and value < 0x800:
            raise ValueError('Overlong modified UTF-8 unit')
        units.extend(value.to_bytes(2, 'big'))
        index += width
    return units.decode('utf-16-be', errors='surrogatepass')


def number(text, minimum=0):
    if len(text) > 10:
        raise SourceDebugLimit('SMAP number exceeds bounds')
    if not re.fullmatch('[0-9]+', text):
        raise ValueError('Invalid SMAP number')
    value = int(text)
    if value > MAX_NUMBER:
        raise SourceDebugLimit('SMAP number exceeds bounds')
    if value < minimum:
        raise ValueError('SMAP number is below its allowed minimum')
    return value


def source_name(text):
    value = text.lstrip(' \t')
    if not value or value.startswith('*'):
        raise ValueError('Missing SMAP name')
    return value


def file_section(lines):
    files, ids, index = [], set(), 0
    while index < len(lines):
        line = lines[index].lstrip(' \t')
        match = re.fullmatch(r'(\+[ \t]*)?([0-9]+)(.*)', line)
        if not match:
            raise ValueError('Invalid SMAP file entry')
        file_id, name = number(match[2]), source_name(match[3])
        if file_id in ids:
            raise ValueError('Duplicate SMAP file ID')
        ids.add(file_id)
        path, original = None, [lines[index]]
        index += 1
        if match[1] is not None:
            if index == len(lines):
                raise ValueError('Missing SMAP file path')
            path = source_name(lines[index])
            original.append(lines[index])
            index += 1
        files.append({'id': file_id, 'name': name, 'path': path, 'rawLines': original})
    return files


def line_section(lines):
    result, previous_file = [], 0
    pattern = r'[ \t]*([0-9]+)[ \t]*(?:#[ \t]*([0-9]+)[ \t]*)?(?:,[ \t]*([0-9]+)[ \t]*)?:[ \t]*([0-9]+)[ \t]*(?:,[ \t]*([0-9]+)[ \t]*)?'
    for raw in lines:
        match = re.fullmatch(pattern, raw)
        if not match:
            raise ValueError('Invalid SMAP line entry')
        if match[2] is not None:
            previous_file = number(match[2])
        result.append({'inputStart': number(match[1], 1), 'fileId': previous_file,
                       'repeatCount': number(match[3], 1) if match[3] is not None else 1,
                       'outputStart': number(match[4], 1),
                       'outputIncrement': number(match[5]) if match[5] is not None else 1,
                       'rawLine': raw})
    return result


def parse_smap(raw):
    text = modified_utf8(raw)
    lines = re.split(r'\r\n|\r|\n', text)
    if len(lines) > MAX_ITEMS:
        raise SourceDebugLimit('SMAP line count exceeds bounds')
    if len(lines) < 5 or lines[-1] != '' or lines[0] != 'SMAP':
        raise ValueError('Invalid resolved SMAP framing')
    lines.pop()
    output, default = source_name(lines[1]), source_name(lines[2])
    sections, strata, current, index, ended = [], [], None, 3, False
    while index < len(lines):
        marker = lines[index].lstrip(' \t')
        index += 1
        if re.fullmatch(r'\*E[ \t]*', marker):
            if index != len(lines):
                raise ValueError('Data follows SMAP end')
            ended = True
            break
        if len(sections) >= MAX_SECTIONS:
            raise SourceDebugLimit('SMAP section count exceeds bounds')
        if not re.match(r'^\*[^ \t\r\n]', marker):
            raise ValueError('Invalid SMAP section')
        if marker.startswith(('*O', '*C')):
            raise ValueError('Embedded source maps are unresolved')
        body = []
        while index < len(lines) and not lines[index].lstrip(' \t').startswith('*'):
            body.append(lines[index])
            index += 1
        section = {'ordinal': len(sections), 'marker': marker, 'rawLines': body}
        sections.append(section)
        tag = marker[:2] if re.fullmatch(r'\*[FLV][ \t]*', marker) else marker
        if marker.startswith('*S'):
            name = source_name(marker[2:])
            if body or name == 'Java' or any(item['name'] == name for item in strata):
                raise ValueError('Duplicate, final-source or malformed SMAP stratum')
            current = {'name': name, 'files': None, 'lines': None, 'sections': []}
            strata.append(current)
        elif tag in ('*F', '*L'):
            key = 'files' if tag == '*F' else 'lines'
            if current is None or current[key] is not None:
                raise ValueError('Missing stratum or duplicate SMAP section')
            current[key] = file_section(body) if key == 'files' else line_section(body)
        elif tag == '*V':
            if not body or not source_name(body[0]).strip(' \t'):
                raise ValueError('Missing SMAP vendor identity')
        elif marker[1] in 'SFLEV':
            raise ValueError('Malformed reserved SMAP section')
        elif len(marker.rstrip(' \t')) != 2:
            raise ValueError('Malformed future SMAP section')
        if current is not None:
            current['sections'].append(section['ordinal'])
    if not ended or not strata or default != 'Java' and default not in {item['name'] for item in strata}:
        raise ValueError('Missing SMAP end, strata or default stratum')
    for stratum in strata:
        if stratum['files'] is None or stratum['lines'] is None:
            raise ValueError('Missing complete SMAP file or line section')
        ids = {item['id'] for item in stratum['files']}
        if any(item['fileId'] not in ids for item in stratum['lines']):
            raise ValueError('Undefined SMAP line file ID')
    return {'outputFile': output, 'defaultStratum': default, 'rawHeaderLines': lines[:3],
            'rawEndMarker': marker, 'strata': strata, 'sections': sections}


def payload_maps(metadata):
    identities, contents = metadata.get('sourceDebugExtensionIdentities'), metadata.get('sourceDebugExtensionContentsBase64')
    attributes = metadata.get('attributes')
    if not isinstance(identities, list) or not isinstance(contents, list) or len(identities) != len(contents) or \
            len(contents) > MAX_SECTIONS or not isinstance(attributes, list) or \
            attributes.count('SourceDebugExtension') != len(contents):
        return [{'status': 'unavailable', 'error': 'Missing, unequal or excessive payload inventories',
                 'inputIdentityVerified': False,
                 'boundExceeded': isinstance(contents, list) and len(contents) > MAX_SECTIONS}]
    result = []
    for index, (identity, content) in enumerate(zip(identities, contents)):
        item = {'attributeIndex': index, 'originalIdentityCopiedVerbatim': identity,
                'inputIdentityVerified': False, 'status': 'unavailable', 'boundExceeded': False}
        result.append(item)
        try:
            if not isinstance(identity, dict) or type(identity.get('bytes')) is not int or \
                    identity['bytes'] < 0 or not isinstance(content, str):
                raise ValueError('Invalid source-debug payload identity')
            if identity['bytes'] > MAX_PAYLOAD_BYTES or len(content) > 4 * ((MAX_PAYLOAD_BYTES + 2) // 3):
                raise SourceDebugLimit('Source-debug payload exceeds bounds')
            raw = base64.b64decode(content, validate=True)
            if len(raw) != identity['bytes'] or hashlib.sha256(raw).hexdigest() != identity.get('sha256'):
                raise ValueError('Source-debug payload differs from identity')
            item['inputIdentityVerified'] = True
            item['map'] = parse_smap(raw)
            item['status'] = 'parsed-resolved-smap'
            item['outputFileAgreesWithSourceFileAttributes'] = item['map']['outputFile'] in metadata.get('sourceFiles', [])
        except (ValueError, binascii.Error, UnicodeError) as error:
            item['error'] = type(error).__name__ + ': ' + str(error)
            item['boundExceeded'] = isinstance(error, SourceDebugLimit)
    return result


def map_line(smap, line_number):
    result = []
    for stratum_index, stratum in enumerate(smap['strata']):
        files = {item['id']: item for item in stratum['files']}
        matches = []
        for index, rule in enumerate(stratum['lines']):
            increment, delta = rule['outputIncrement'], line_number - rule['outputStart']
            if increment == 0 or not 0 <= delta < rule['repeatCount'] * increment:
                continue
            matches.append({'stratumIndex': stratum_index, 'stratum': stratum['name'], 'ruleIndex': index,
                            'file': {key: files[rule['fileId']][key] for key in ('id', 'name', 'path')},
                            'inputLine': rule['inputStart'] + delta // increment,
                            'firstMatchingRule': not matches,
                            'defaultStratum': smap['defaultStratum'] == stratum['name']})
        result.extend(matches)
    return result
