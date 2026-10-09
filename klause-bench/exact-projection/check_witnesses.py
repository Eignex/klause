#!/usr/bin/env python3
"""Binary64 tolerance check of a printed MPS witness: every row and bound within 1e-7 row-relative.

Independent of klause: parses the source MPS itself (binary64 values, RANGES, integer markers) and the `v` line.
"""
import hashlib
import io
import json
import tarfile
import tempfile
import urllib.request
import math
import sys
from pathlib import Path

TOL = 1e-7
INF = 1e20


def parse(path):
    section, rows, objective, marker = None, {}, None, False
    integer, coeff, rhs, rng, lower, upper, columns = set(), {}, {}, {}, {}, {}, []
    indicators = {}
    seen = set()
    for raw in open(path, encoding='latin-1'):
        p = raw.split()
        if not p or raw[0] == '*':
            continue
        if raw[0] not in ' \t':
            section = p[0]
            continue
        if section == 'ROWS':
            rows[p[1]] = p[0]
            if p[0] == 'N' and objective is None:
                objective = p[1]
        elif section == 'COLUMNS':
            if "'MARKER'" in p:
                marker = "'INTORG'" in p
                continue
            col = p[0]
            if col not in seen:
                seen.add(col)
                columns.append(col)
            if marker:
                integer.add(col)
            for r, v in zip(p[1::2], p[2::2]):
                coeff.setdefault(r, []).append((col, float(v)))
        elif section in ('RHS', 'RANGES'):
            pairs = zip(p[1::2], p[2::2]) if len(p) % 2 == 1 else zip(p[0::2], p[1::2])
            for r, v in pairs:
                (rhs if section == 'RHS' else rng)[r] = float(v)
        elif section == 'INDICATORS':
            # IF <row> <column> <value>: the row is enforced only when the column takes the value.
            if p[0] == 'IF':
                indicators[p[1]] = (p[2], float(p[3]))
        elif section == 'BOUNDS':
            kind, col = p[0], p[2]
            value = float(p[3]) if len(p) > 3 else None
            if kind in ('UP', 'UI'):
                upper[col] = value
                if value < 0 and col not in lower:
                    lower[col] = -math.inf
            elif kind in ('LO', 'LI'):
                lower[col] = value
            elif kind == 'FX':
                lower[col] = upper[col] = value
            elif kind == 'FR':
                lower[col], upper[col] = -math.inf, math.inf
            elif kind == 'MI':
                lower[col] = -math.inf
            elif kind == 'PL':
                upper[col] = math.inf
            elif kind == 'BV':
                lower[col], upper[col] = 0.0, 1.0
                integer.add(col)
            if kind in ('LI', 'UI'):
                integer.add(col)
    return rows, objective, integer, coeff, rhs, rng, lower, upper, columns, indicators


def row_sides(kind, b, r):
    if kind == 'E':
        if r is None:
            return b, b
        return (b, b + r) if r >= 0 else (b + r, b)
    if kind == 'L':
        return (b - abs(r) if r is not None else -math.inf), b
    if kind == 'G':
        return b, (b + abs(r) if r is not None else math.inf)
    return -math.inf, math.inf


def check(source, stdout):
    text = Path(stdout).read_text(errors='replace').splitlines()
    vline = next((line for line in text if line.startswith('v ')), None)
    if vline is None:
        return {'witness': 'absent'}
    values = {}
    for token in vline[2:].split():
        name, raw = token.split('=', 1)
        if '/' in raw:
            num, den = raw.split('/')
            values[name] = int(num) / int(den)
        else:
            values[name] = float(raw)
    if any(not math.isfinite(x) for x in values.values()):
        return {"witness": "nonfinite"}
    rows, objective, integer, coeff, rhs, rng, lower, upper, columns, indicators = parse(source)
    worst = 0.0
    for col in columns:
        x = values.get(col)
        if x is None:
            return {'witness': 'missing column', 'column': col}
        lo = lower.get(col, 0.0)
        hi = upper.get(col, math.inf)
        for bound, below in ((lo, True), (hi, False)):
            if not math.isfinite(bound) or abs(bound) >= INF:
                continue
            gap = (bound - x) if below else (x - bound)
            worst = max(worst, gap / max(1.0, abs(bound)))
        if col in integer and abs(x - round(x)) > TOL:
            worst = max(worst, abs(x - round(x)))
    for r, kind in rows.items():
        if kind == 'N':
            continue
        if r in indicators and values[indicators[r][0]] != indicators[r][1]:
            continue
        terms = coeff.get(r, [])
        activity = sum(a * values[c] for c, a in terms)
        magnitude = sum(abs(a * values[c]) for c, a in terms)
        lo, hi = row_sides(kind, rhs.get(r, 0.0), rng.get(r))
        for bound, below in ((lo, True), (hi, False)):
            if not math.isfinite(bound):
                continue
            gap = (bound - activity) if below else (activity - bound)
            worst = max(worst, gap / max(1.0, abs(bound), magnitude))
    return {'witness': 'within-tolerance' if worst <= TOL else 'violates', 'max_relative_violation': worst}


def main():
    evidence = json.loads(Path(sys.argv[1]).read_text())
    archive = None
    results = []
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        for index, witness in enumerate(evidence):
            source = witness["source"]
            if source["kind"] == "vendored":
                data = Path(source["path"]).read_bytes()
            else:
                if archive is None:
                    url = "https://miplib2010.zib.de/miplib3/miplib3.tar.gz"
                    with urllib.request.urlopen(url, timeout=60) as response:
                        archive = tarfile.open(fileobj=io.BytesIO(response.read()), mode="r:gz")
                member = next(member for member in archive.getmembers()
                              if member.isfile() and member.name.split("/")[-1] == source["name"])
                data = archive.extractfile(member).read()
            if hashlib.sha256(data).hexdigest() != source["sha256"]:
                raise ValueError(f"source fingerprint mismatch: {source}")
            model, output = root / f"{index}.mps", root / f"{index}.out"
            model.write_bytes(data)
            output.write_text(witness["assignment"] + "\n")
            result = check(model, output)
            results.append({"problem": witness["problem"], "cases": witness["cases"], "check": result})
            if result.get("witness") != "within-tolerance":
                raise ValueError(f"invalid witness: {results[-1]}")
    print(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
