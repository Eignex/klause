"""Bounded RFC 1951/1952 decoding without native compression dependencies."""
import hashlib
import io
import re

MAX_WIRE_BYTES = 8 * 1024 * 1024
MAX_DECODED_BYTES = 64 * 1024 * 1024
MAX_LINE_BYTES = 8 * 1024 * 1024
MAX_ENVELOPE_BYTES = 8 * 1024 * 1024
LENGTH_BASE = (3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43,
               51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
LENGTH_BITS = (0,) * 8 + (1,) * 4 + (2,) * 4 + (3,) * 4 + (4,) * 4 + (5,) * 4 + (0,)
DISTANCE_BASE = (1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
                 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145,
                 8193, 12289, 16385, 24577)
DISTANCE_BITS = (0,) * 4 + tuple(n for n in range(1, 14) for _ in range(2))


def crc32(raw):
    table = []
    for value in range(256):
        for _ in range(8):
            value = (value >> 1) ^ (0xedb88320 if value & 1 else 0)
        table.append(value)
    value = 0xffffffff
    for byte in raw:
        value = table[(value ^ byte) & 255] ^ (value >> 8)
    return value ^ 0xffffffff


class Bits:
    def __init__(self, raw, offset):
        self.raw, self.position = raw, offset * 8

    def read(self, size):
        if self.position + size > len(self.raw) * 8:
            raise ValueError('truncated deflate input')
        offset = self.position // 8
        value = int.from_bytes(self.raw[offset:offset + 3], 'little') >> (self.position % 8)
        self.position += size
        return value & ((1 << size) - 1)

    def align(self):
        self.position = (self.position + 7) // 8 * 8


class Huffman:
    def __init__(self, lengths, *, complete=False):
        if any(not 0 <= size <= 15 for size in lengths):
            raise ValueError('invalid Huffman code length')
        counts = [lengths.count(size) for size in range(16)]
        counts[0] = 0
        left = 1
        for size in range(1, 16):
            left = left * 2 - counts[size]
            if left < 0:
                raise ValueError('oversubscribed Huffman alphabet')
        if left and (complete or sum(counts) != 1 or counts[1] != 1):
            raise ValueError('incomplete Huffman alphabet')
        self.codes = {}
        code, next_code = 0, [0] * 16
        for size in range(1, 16):
            code = (code + counts[size - 1]) << 1
            next_code[size] = code
        self.maximum = max(lengths, default=0)
        for symbol, size in enumerate(lengths):
            if size:
                self.codes[(size, next_code[size])] = symbol
                next_code[size] += 1

    def symbol(self, bits):
        code = 0
        for size in range(1, self.maximum + 1):
            code = (code << 1) | bits.read(1)
            symbol = self.codes.get((size, code))
            if symbol is not None:
                return symbol
        raise ValueError('invalid Huffman symbol')


def alphabets(bits):
    literals, distances, count = bits.read(5) + 257, bits.read(5) + 1, bits.read(4) + 4
    if literals > 286 or distances > 32:
        raise ValueError('invalid deflate alphabet size')
    order = (16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)
    sizes = [0] * 19
    for symbol in order[:count]:
        sizes[symbol] = bits.read(3)
    codes = Huffman(sizes, complete=True)
    sizes = []
    while len(sizes) < literals + distances:
        value = codes.symbol(bits)
        if value <= 15:
            sizes.append(value)
            continue
        if value == 16:
            if not sizes:
                raise ValueError('Huffman repeat has no previous length')
            size, count = sizes[-1], bits.read(2) + 3
        elif value == 17:
            size, count = 0, bits.read(3) + 3
        else:
            size, count = 0, bits.read(7) + 11
        if len(sizes) + count > literals + distances:
            raise ValueError('Huffman repeat exceeds alphabet')
        sizes.extend([size] * count)
    if not sizes[256]:
        raise ValueError('deflate block has no end symbol')
    distance_sizes = sizes[literals:]
    return Huffman(sizes[:literals]), Huffman(distance_sizes) if any(distance_sizes) else None


def inflate(raw, offset, maximum):
    bits, output = Bits(raw, offset), bytearray()
    final = False
    while not final:
        final, kind = bits.read(1), bits.read(2)
        if kind == 0:
            bits.align()
            size, complement = bits.read(16), bits.read(16)
            if size ^ complement != 65535:
                raise ValueError('invalid stored deflate block length')
            start = bits.position // 8
            if start + size > len(raw) or len(output) + size > maximum:
                raise ValueError('stored block exceeds input or decoded limit')
            output.extend(raw[start:start + size])
            bits.position += size * 8
            continue
        if kind == 1:
            literal = Huffman([8] * 144 + [9] * 112 + [7] * 24 + [8] * 8)
            distance = Huffman([5] * 32)
        elif kind == 2:
            literal, distance = alphabets(bits)
        else:
            raise ValueError('reserved deflate block type')
        while True:
            value = literal.symbol(bits)
            if value == 256:
                break
            if value < 256:
                if len(output) >= maximum:
                    raise ValueError('decoded stdout exceeds compressed transport limit')
                output.append(value)
                continue
            if value > 285 or distance is None:
                raise ValueError('invalid deflate length or missing distance alphabet')
            index = value - 257
            size = LENGTH_BASE[index] + bits.read(LENGTH_BITS[index])
            index = distance.symbol(bits)
            if index >= 30:
                raise ValueError('reserved deflate distance symbol')
            back = DISTANCE_BASE[index] + bits.read(DISTANCE_BITS[index])
            if back > len(output) or len(output) + size > maximum:
                raise ValueError('deflate distance or decoded size exceeds limit')
            chunk = bytes(output[-back:]) if back < size else bytes(output[-back:-back + size or None])
            output.extend((chunk * ((size + len(chunk) - 1) // len(chunk)))[:size])
    bits.align()
    return output, bits.position // 8


def decode(raw, maximum=MAX_DECODED_BYTES):
    output, offset = bytearray(), 0
    if not raw or len(raw) > MAX_WIRE_BYTES:
        raise ValueError('compressed stdout exceeds checker limit')
    while offset < len(raw):
        start = offset
        if raw[offset:offset + 3] != b'\x1f\x8b\x08' or offset + 10 > len(raw):
            raise ValueError('invalid or truncated gzip header')
        flags = raw[offset + 3]
        if flags & 0xe0:
            raise ValueError('reserved gzip header flags')
        offset += 10
        if flags & 4:
            if offset + 2 > len(raw):
                raise ValueError('truncated gzip extra length')
            size = int.from_bytes(raw[offset:offset + 2], 'little')
            offset += 2 + size
        for flag in (8, 16):
            if flags & flag:
                end = raw.find(b'\0', offset)
                if end < 0:
                    raise ValueError('unterminated gzip header field')
                offset = end + 1
        if flags & 2:
            if offset + 2 > len(raw) or crc32(raw[start:offset]) & 65535 != int.from_bytes(raw[offset:offset + 2], 'little'):
                raise ValueError('invalid gzip header CRC')
            offset += 2
        member, offset = inflate(raw, offset, maximum - len(output))
        if offset + 8 > len(raw):
            raise ValueError('truncated gzip trailer')
        if crc32(member) != int.from_bytes(raw[offset:offset + 4], 'little') or \
                len(member) % (1 << 32) != int.from_bytes(raw[offset + 4:offset + 8], 'little'):
            raise ValueError('gzip member CRC or length differs')
        output.extend(member)
        offset += 8
    return output


def envelope(path, expected_sha256, expected_bytes):
    if not isinstance(expected_sha256, str) or not re.fullmatch('[0-9a-f]{64}', expected_sha256):
        raise ValueError('missing complete stdout hash')
    if type(expected_bytes) is not int or not 0 <= expected_bytes <= MAX_DECODED_BYTES:
        raise ValueError('decoded stdout size outside transport limit')
    with path.open('rb') as stream:
        compressed = stream.read(MAX_WIRE_BYTES + 1)
    raw = decode(compressed)
    digest = hashlib.sha256(raw).hexdigest()
    if len(raw) != expected_bytes or digest != expected_sha256:
        raise ValueError('complete decoded stdout differs from producer')
    semantics, witness, objective, total = [], None, None, 0
    stream = io.BytesIO(raw)
    while raw_line := stream.readline(MAX_LINE_BYTES + 1):
        if len(raw_line) > MAX_LINE_BYTES:
            raise ValueError('stdout line exceeds checker limit')
        for line in raw_line.decode('utf-8').splitlines():
            if 'klause-source-semantics:' in line:
                semantics.append(line)
                total += len(line.encode()) + 1
            if line == 'v' or (len(line) > 1 and line[0] == 'v' and line[1].isspace()):
                witness = line
            if line.startswith('o '):
                objective = line
            size = total + sum(len(value.encode()) + 1 for value in (witness, objective) if value is not None)
            if size > MAX_ENVELOPE_BYTES:
                raise ValueError('stdout checking envelope exceeds limit')
    lines = semantics + ([witness] if witness is not None else []) + ([objective] if objective is not None else [])
    retained = ('\n'.join(lines) + ('\n' if lines else '')).encode()
    if len(retained) > MAX_ENVELOPE_BYTES:
        raise ValueError('stdout checking envelope exceeds limit')
    return retained.decode(), {'encoding': 'gzip', 'wireBytes': len(compressed), 'decodedBytes': len(raw),
        'wireSha256': hashlib.sha256(compressed).hexdigest(), 'decodedSha256': digest,
        'envelopeBytes': len(retained), 'envelopeSha256': hashlib.sha256(retained).hexdigest(), 'complete': True}
