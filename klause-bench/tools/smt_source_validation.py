"""Bounded ordinary SMT source-claim checking with a private independent runtime."""
import atexit
import ctypes
import fractions
import importlib.machinery
import importlib.metadata
import json
import math
import numbers
import pathlib
import platform
import re
import signal
import sys
import tempfile
import time

TOOLS = {'smt_source_validation.py', 'mps_witness_validation.py', 'smt_witness_validation.py',
         'verify_nra_source_results.py', 'verify_source_results.py', 'requirements-source-checks.txt'}
GROUND = 'ground-original-smt-witness'
REFUTATION = 'independent-original-smt-refutation'
CHECKER = 'smt-original-claims-v2'
QUERY_MS = 30000
DERIVATION_BITS = 16384
DERIVATION_POWER = 64
DERIVATION_TERMS = 32768


def load(path, name):
    import importlib.util
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def bindings(out):
    import hashlib
    return {name: hashlib.sha256((out / name).read_bytes()).hexdigest()
            for name in ('source.smt2', 'model.smt2', 'stdout.txt', 'record.json', 'request.json')}


def eligible(record):
    stats = record.get('stats', {})
    if (record.get('kind') != 'satisfy' or record.get('objective') is not None
            or record.get('exactObjective') is not None or stats.get('exit') != '0'
            or 'killed' in stats or 'unsupported' in stats or 'quarantined' in stats
            or not record.get('command')):
        return None
    if record.get('feasible') is True and record.get('finalWitness'):
        return GROUND
    if record.get('feasible') is False and record.get('proven') is True:
        return REFUTATION
    return None


def evaluate(checker, text, model, scope):
    import z3
    result = {'status': 'unknown', 'reason': 'unsupported source or witness', 'scope': scope,
              'solverSearchQueries': 0, 'proofCertificateChecked': False, 'checkedWitness': None,
              'checker': CHECKER, 'referenceComparison': None, 'solverQueryCountObserved': True,
              'referenceLogic': None, 'sourceDerivation': None}
    try:
        if scope == GROUND:
            checked = checker.check_witness(text, model)
            result.update(status='valid', reason=checked['witnessCheck'], checkedWitness=model)
        else:
            print('source-formula-start', file=sys.stderr, flush=True)
            variables, constraints = checker.source_formula(text)
            print('source-formula-complete', file=sys.stderr, flush=True)
            logic = reference_logic(checker, variables, constraints)
            derivation = source_derivation(variables, constraints) if logic == 'QF_NRA' else None
            print('source-derivation-complete', file=sys.stderr, flush=True)
            if derivation is not None:
                result.update(status='valid', reason='independent exact original source contradiction',
                              sourceDerivation=derivation)
                return result
            result['referenceLogic'] = logic
            solver = z3.SolverFor('QF_NRA') if logic == 'QF_NRA' else z3.Solver()
            solver.set(timeout=QUERY_MS)
            solver.add(*constraints)
            result['solverSearchQueries'] = 1
            print('original-reference-query-start', file=sys.stderr, flush=True)
            verdict = solver.check()
            print('original-reference-query-complete', file=sys.stderr, flush=True)
            reason = '; reason: ' + solver.reason_unknown() if verdict == z3.unknown else ''
            assert verdict == z3.unsat, f'reference comparison expected unsat, got {verdict}{reason}'
            result.update(status='valid', reason='independent full original source refutation',
                          referenceComparison='unsat')
    except AssertionError as error:
        reason = str(error)
        invalid = ((reason.startswith('original source assertion ') and reason.endswith(' is false'))
                   or reason.startswith('reference comparison expected unsat, got sat')
                   or reason in ('unknown or duplicate model variable', 'incomplete source model',
                                 'model sort differs from source declaration', 'nonintegral integer coordinate'))
        result.update(status='invalid' if invalid else 'unknown', reason=reason[:500])
    except (ValueError, KeyError, TypeError, UnicodeError, RuntimeError, ZeroDivisionError,
            OverflowError, z3.Z3Exception) as error:
        result['reason'] = 'unsupported source or witness: ' + str(error)[:400]
    return result


def reference_logic(checker, variables, constraints):
    import z3
    if any(value.sort().kind() not in (z3.Z3_BOOL_SORT, z3.Z3_REAL_SORT) for value in variables.values()):
        return 'SMT'
    pending, seen = list(constraints), set()
    while pending:
        node = pending.pop()
        if node.get_id() in seen:
            continue
        seen.add(node.get_id())
        if z3.is_int(node) and not z3.is_int_value(node):
            numeral = node
            while numeral.decl().kind() == z3.Z3_OP_UMINUS:
                numeral = numeral.arg(0)
            if not z3.is_int_value(numeral):
                return 'SMT'
        pending.extend(node.children())
    cache = {}
    return 'SMT' if any(checker.contains_zero_power(node, cache) for node in constraints) else 'QF_NRA'


def source_scalar(node, cache):
    import z3
    pending = [(node, False)]
    while pending:
        value, expanded = pending.pop()
        key = value.get_id()
        if key in cache:
            continue
        if z3.is_rational_value(value) or z3.is_int_value(value):
            scalar = fractions.Fraction(value.as_long()) if z3.is_int_value(value) else value.as_fraction()
            cache[key] = scalar if scalar_bits(scalar) <= DERIVATION_BITS else None
            continue
        kind = value.decl().kind()
        if kind not in (z3.Z3_OP_UMINUS, z3.Z3_OP_TO_REAL, z3.Z3_OP_DIV):
            cache[key] = None
            continue
        if not expanded:
            pending.append((value, True))
            pending.extend((child, False) for child in value.children())
            continue
        children = [cache[child.get_id()] for child in value.children()]
        if any(child is None for child in children):
            cache[key] = None
        elif kind == z3.Z3_OP_UMINUS:
            cache[key] = -children[0]
        elif kind == z3.Z3_OP_TO_REAL:
            cache[key] = children[0]
        else:
            cache[key] = children[0] / children[1] if children[1] else None
    return cache[node.get_id()]


def scalar_bits(value):
    return max(abs(value.numerator).bit_length(), value.denominator.bit_length())


def source_monomial(node, variables, cache):
    import z3
    coefficient, powers = fractions.Fraction(1), {}
    pending = [(node, 1)]
    while pending:
        value, exponent = pending.pop()
        key = value.get_id()
        if key in variables:
            powers[key] = powers.get(key, 0) + exponent
            if powers[key] > DERIVATION_POWER:
                return None
            continue
        scalar = source_scalar(value, cache)
        if scalar is not None:
            if scalar_bits(scalar) * exponent > DERIVATION_BITS:
                return None
            coefficient *= scalar ** exponent
        elif value.decl().kind() == z3.Z3_OP_MUL:
            pending.extend((child, exponent) for child in value.children())
        elif value.decl().kind() == z3.Z3_OP_UMINUS:
            coefficient *= (-1) ** exponent
            pending.append((value.arg(0), exponent))
        elif value.decl().kind() == z3.Z3_OP_DIV:
            denominator = source_scalar(value.arg(1), cache)
            if denominator is None or denominator == 0 or scalar_bits(denominator) * exponent > DERIVATION_BITS:
                return None
            coefficient /= denominator ** exponent
            pending.append((value.arg(0), exponent))
        elif value.decl().kind() == z3.Z3_OP_POWER:
            power = source_scalar(value.arg(1), cache)
            if power is None or power.denominator != 1 or not 1 <= power <= DERIVATION_POWER:
                return None
            combined = exponent * power.numerator
            if combined > DERIVATION_POWER:
                return None
            pending.append((value.arg(0), combined))
        else:
            return None
        if scalar_bits(coefficient) > DERIVATION_BITS:
            return None
    return tuple(sorted(powers.items())), coefficient


def source_polynomial(left, right, variables, cache):
    import z3
    terms, pending = {}, [(left, 1), (right, -1)]
    while pending:
        value, orientation = pending.pop()
        kind = value.decl().kind()
        if kind == z3.Z3_OP_ADD:
            pending.extend((child, orientation) for child in value.children())
        elif kind == z3.Z3_OP_SUB:
            pending.append((value.arg(0), orientation))
            pending.extend((child, -orientation) for child in value.children()[1:])
        elif kind == z3.Z3_OP_UMINUS:
            pending.append((value.arg(0), -orientation))
        else:
            monomial = source_monomial(value, variables, cache)
            if monomial is None:
                return None
            powers, coefficient = monomial
            terms[powers] = terms.get(powers, 0) + coefficient * orientation
            if len(terms) > DERIVATION_TERMS or scalar_bits(terms[powers]) > DERIVATION_BITS:
                return None
    return {powers: coefficient for powers, coefficient in terms.items() if coefficient}


def necessary_source_polynomials(constraints, variables):
    import z3
    operators = {z3.Z3_OP_EQ: 'eq', z3.Z3_OP_DISTINCT: 'ne', z3.Z3_OP_LT: 'lt',
                 z3.Z3_OP_LE: 'le', z3.Z3_OP_GT: 'gt', z3.Z3_OP_GE: 'ge'}
    opposite = {'eq': 'ne', 'ne': 'eq', 'lt': 'ge', 'le': 'gt', 'gt': 'le', 'ge': 'lt'}
    rows, cache = [], {}
    pending = [(index, node, False) for index, node in enumerate(constraints)]
    while pending:
        index, node, negative = pending.pop()
        if z3.is_not(node):
            pending.append((index, node.arg(0), not negative))
        elif z3.is_and(node) and not negative:
            pending.extend((index, child, False) for child in node.children())
        elif z3.is_true(node) or z3.is_false(node):
            if z3.is_false(node) != negative:
                rows.append((index, node, 'eq', {(): fractions.Fraction(1)}))
        elif node.decl().kind() in operators and node.num_args() == 2 and not z3.is_bool(node.arg(0)):
            polynomial = source_polynomial(node.arg(0), node.arg(1), variables, cache)
            if polynomial is not None:
                relation = operators[node.decl().kind()]
                rows.append((index, node, opposite[relation] if negative else relation, polynomial))
    return rows


def restrict_source_bound(bounds, variable, value, lower, opened):
    bound = bounds.setdefault(variable, {'lower': None, 'lowerOpen': False, 'upper': None, 'upperOpen': False})
    side, strict = ('lower', 'lowerOpen') if lower else ('upper', 'upperOpen')
    previous = bound[side]
    if previous is None or (value > previous if lower else value < previous):
        bound[side], bound[strict] = value, opened
    elif value == previous:
        bound[strict] = bound[strict] or opened


def collect_source_bounds(rows):
    bounds = {}
    reverse = {'eq': 'eq', 'ne': 'ne', 'lt': 'gt', 'le': 'ge', 'gt': 'lt', 'ge': 'le'}
    for _, _, relation, terms in rows:
        nonconstant = [(powers, coefficient) for powers, coefficient in terms.items() if powers]
        constant = terms.get((), fractions.Fraction(0))
        if len(nonconstant) == 1 and len(nonconstant[0][0]) == 1 and nonconstant[0][0][0][1] == 1:
            powers, coefficient = nonconstant[0]
            variable = powers[0][0]
            value = -constant / coefficient
            oriented = relation if coefficient > 0 else reverse[relation]
            if oriented in ('eq', 'gt', 'ge'):
                restrict_source_bound(bounds, variable, value, True, oriented == 'gt')
            if oriented in ('eq', 'lt', 'le'):
                restrict_source_bound(bounds, variable, value, False, oriented == 'lt')
        if not nonconstant or relation == 'ne':
            continue
        orientation = -1 if relation in ('gt', 'ge') or (relation == 'eq' and nonconstant[0][1] < 0) else 1
        if any(coefficient * orientation <= 0 or any(power % 2 for _, power in powers)
               for powers, coefficient in nonconstant):
            continue
        ceiling = -constant * orientation
        if ceiling < 0:
            continue
        for powers, coefficient in nonconstant:
            if len(powers) != 1 or powers[0][1] != 2:
                continue
            ratio = ceiling / (coefficient * orientation)
            ceiling_integer = -(-ratio.numerator // ratio.denominator)
            radius = math.isqrt(ceiling_integer)
            if radius * radius < ceiling_integer:
                radius += 1
            restrict_source_bound(bounds, powers[0][0], fractions.Fraction(-radius), True, False)
            restrict_source_bound(bounds, powers[0][0], fractions.Fraction(radius), False, False)
    return bounds


def polynomial_source_signs(terms, bounds):
    total = {0}
    for powers, coefficient in terms.items():
        product = {1 if coefficient > 0 else -1}
        for variable, exponent in powers:
            bound = bounds.get(variable, {})
            lower, upper = bound.get('lower'), bound.get('upper')
            signs = {-1, 0, 1}
            if lower is not None and lower >= 0:
                signs.discard(-1)
                if lower > 0 or bound['lowerOpen']:
                    signs.discard(0)
            if upper is not None and upper <= 0:
                signs.discard(1)
                if upper < 0 or bound['upperOpen']:
                    signs.discard(0)
            factors = {0 if sign == 0 else 1 if exponent % 2 == 0 else sign for sign in signs}
            product = {left * right for left in product for right in factors}
        combined = set()
        for left in total:
            for right in product:
                combined.update({right} if left == 0 else {left} if right == 0 or left == right else {-1, 0, 1})
        total = combined
    return total


def polynomial_source_interval(terms, bounds):
    lower = upper = fractions.Fraction(0)
    for powers, coefficient in terms.items():
        lo = hi = coefficient
        for variable, exponent in powers:
            bound = bounds.get(variable, {})
            a, b = bound.get('lower'), bound.get('upper')
            if a is None or b is None or max(scalar_bits(a), scalar_bits(b)) * exponent > DERIVATION_BITS:
                return None
            ap, bp = a ** exponent, b ** exponent
            minimum = 0 if exponent % 2 == 0 and a <= 0 <= b else min(ap, bp)
            maximum = max(ap, bp)
            products = (lo * minimum, lo * maximum, hi * minimum, hi * maximum)
            lo, hi = min(products), max(products)
            if max(scalar_bits(lo), scalar_bits(hi)) > DERIVATION_BITS:
                return None
        lower, upper = lower + lo, upper + hi
        if max(scalar_bits(lower), scalar_bits(upper)) > DERIVATION_BITS:
            return None
    return lower, upper


def source_derivation(variables, constraints):
    import hashlib
    import z3
    names = {value.get_id(): name for name, value in variables.items() if z3.is_real(value)}
    rows = necessary_source_polynomials(constraints, names)
    bounds = collect_source_bounds(rows)
    empty = any(bound['lower'] is not None and bound['upper'] is not None and
                (bound['lower'] > bound['upper'] or (bound['lower'] == bound['upper'] and
                 (bound['lowerOpen'] or bound['upperOpen']))) for bound in bounds.values())
    allowed = {'eq': {0}, 'ne': {-1, 1}, 'lt': {-1}, 'le': {-1, 0}, 'gt': {1}, 'ge': {0, 1}}
    for index, node, relation, terms in rows:
        signs = polynomial_source_signs(terms, bounds)
        method = 'empty-source-bounds' if empty else 'source-signs' if signs.isdisjoint(allowed[relation]) else None
        interval = polynomial_source_interval(terms, bounds) if method is None else None
        if interval is not None:
            lower, upper = interval
            excluded = {'eq': lower > 0 or upper < 0, 'ne': lower == upper == 0,
                        'lt': lower >= 0, 'le': lower > 0, 'gt': upper <= 0, 'ge': upper < 0}
            if excluded[relation]:
                method = 'source-interval'
        if method is not None:
            return {'kind': 'source-polynomial-enclosure-v1', 'method': method,
                    'assertions': sorted({row[0] for row in rows}), 'contradictionAssertion': index,
                    'relation': relation,
                    'expressionSha256': hashlib.sha256(node.sexpr().encode()).hexdigest(),
                    'bounds': {names[variable]: {key: str(value) if key in ('lower', 'upper') and value is not None
                               else value for key, value in bound.items()} for variable, bound in bounds.items()}}
    return None


def darwin_images():
    loader = ctypes.CDLL(None)
    count = loader._dyld_image_count
    count.argtypes, count.restype = [], ctypes.c_uint32
    name = loader._dyld_get_image_name
    name.argtypes, name.restype = [ctypes.c_uint32], ctypes.c_char_p
    header = loader._dyld_get_image_header
    header.argtypes, header.restype = [ctypes.c_uint32], ctypes.c_void_p
    size = count()
    assert 0 < size <= 4096
    images = []
    for index in range(size):
        path, address = name(index), header(index)
        assert path and address and len(path) <= 4096
        path = str(pathlib.Path(path.decode('utf-8')).resolve())
        words = ctypes.cast(address, ctypes.POINTER(ctypes.c_uint32))
        images.append({'path': path, 'address': format(address, 'x'),
                       'magic': format(words[0], 'x'), 'cpuType': words[1]})
    raw = ''.join(json.dumps(row, sort_keys=True, separators=(',', ':')) + '\n' for row in images)
    assert 0 < len(raw.encode('utf-8')) <= 262144
    return images, raw


def darwin_provider_paths(images, executable, version, machine):
    cpu = {'arm64': 0x100000c, 'x86_64': 0x1000007}[machine]
    expected = '.'.join(version.split('.')[:2])
    selected = {executable}
    assert executable.startswith('/') and not any(c.isspace() or c == '\\' for c in executable)
    assert 0 < len(images) <= 4096
    loaded = images[0]['path']
    if loaded != executable:
        suffix = '/Resources/Python.app/Contents/MacOS/Python'
        assert loaded.endswith(suffix)
        framework = loaded[:-len(suffix)]
        assert re.search(r'/Python\.framework/Versions/' + re.escape(expected) + r'[dmt]*$', framework)
        assert framework + '/Python' in [row['path'] for row in images]
        directories = {framework + '/bin'}
        if '/Frameworks/Python.framework/' in framework:
            directories.add(framework.split('/Frameworks/Python.framework/', 1)[0] + '/bin')
        invoked = pathlib.PurePosixPath(executable)
        assert str(invoked.parent) in directories
        assert re.fullmatch(r'python(?:' + re.escape(expected) + '|' + re.escape(version.split('.')[0]) + r')[dmt]*', invoked.name)
    addresses = set()
    for row in images:
        assert set(row) == {'path', 'address', 'magic', 'cpuType'}
        assert row['magic'] == 'feedfacf' and row['cpuType'] == cpu
        assert re.fullmatch(r'[0-9a-f]{1,16}', row['address']) and int(row['address'], 16) > 0
        assert row['address'] not in addresses
        addresses.add(row['address'])
        path = row['path']
        name = pathlib.PurePosixPath(path).name
        python = name.startswith('libpython') or (name == 'Python' and '/Python.framework/' in path)
        if path not in (executable, loaded) and not python:
            continue
        assert path.startswith('/') and not any(c.isspace() or c == '\\' for c in path)
        if python and path not in (executable, loaded):
            assert re.fullmatch(r'libpython' + re.escape(expected) + r'[dmt]*\.dylib', name) or \
                re.search(r'/Python\.framework/Versions/' + re.escape(expected) + r'[dmt]*/Python$', path)
        selected.add(path)
    assert executable in selected and 0 < len(selected) <= 3
    return sorted(selected)


def runtime_identity(out, dependencies, helper):
    import z3
    runtime = json.loads((out / 'bootstrap.json').read_bytes())
    assert pathlib.Path(z3.__file__).resolve().is_relative_to(dependencies.resolve())
    assert importlib.metadata.version('z3-solver') == '4.15.3.0' and z3.get_version_string() == '4.15.3'
    for row in runtime['extractedFiles']:
        path = dependencies / row['path']
        assert path.stat().st_size == row['bytes'] and (helper.file_identity(
            path, 100663296)['sha256'] if row['bytes'] else helper.digest(b'')) == row['sha256']
    executable = pathlib.Path(sys.executable).resolve(strict=True)
    darwin = platform.system() == 'Darwin'
    if darwin:
        images, maps = darwin_images()
        (out / 'native-images.jsonl').write_text(maps)
        providers = darwin_provider_paths(images, str(executable), platform.python_version(), platform.machine())
        libraries = [row for row in images if 'libz3' in pathlib.PurePosixPath(row['path']).name]
        assert len(libraries) == 1, 'missing or foreign native checker provider'
        loaded = libraries[0]
        assert loaded['magic'] == 'feedfacf' and loaded['cpuType'] == {'arm64': 0x100000c, 'x86_64': 0x1000007}[platform.machine()]
        library = pathlib.Path(loaded['path'])
        assert library in [dependencies.resolve() / ('z3/lib/' + name)
                           for name in ('libz3.dylib', 'libz3.4.15.dylib')]
        raw_maps = maps.encode('utf-8')
    else:
        assert platform.system() == 'Linux'
        assert pathlib.Path('/proc/self/exe').resolve(strict=True) == executable
        with pathlib.Path('/proc/self/maps').open('rb') as stream:
            raw_maps = stream.read(262145)
        assert len(raw_maps) <= 262144
        maps = raw_maps.decode('utf-8')
        providers = helper.python_provider_paths(maps, str(executable), platform.python_version())
        library = dependencies.resolve() / 'z3/lib/libz3.so'
        native_paths = set()
        for line in maps.splitlines():
            fields = line.split(None, 5)
            if len(fields) == 6 and 'libz3' in pathlib.PurePosixPath(fields[5]).name:
                assert fields[5] == str(library) and int(fields[4]) > 0
                if 'x' in fields[1]:
                    native_paths.add(fields[5])
        assert native_paths == {str(library)}, 'missing or foreign native checker provider'
    modules = {module.__name__: helper.file_identity(pathlib.Path(module.__file__), 4194304)
               for module in (fractions, numbers)}
    builtin = None
    if getattr(math, '__file__', None):
        assert pathlib.Path(math.__spec__.origin).resolve(strict=True) == pathlib.Path(math.__file__).resolve(strict=True)
        modules['math'] = helper.file_identity(pathlib.Path(math.__file__), 4194304)
    elif darwin:
        assert math.__spec__.origin == 'built-in' and math.__spec__.loader is importlib.machinery.BuiltinImporter
        builtin = {'origin': 'built-in', 'loader': '_frozen_importlib.BuiltinImporter',
                   'executablePath': str(executable), 'maps': maps, 'mapsSha256': helper.digest(raw_maps),
                   'providers': [helper.file_identity(pathlib.Path(p), 67108864) for p in providers]}
    else:
        builtin = helper.builtin_math_identity()
    arithmetic = {'implementation': 'smt-exact-source-arithmetic-v2', 'pythonVersion': platform.python_version(),
                  'pythonExecutableSha256': helper.file_identity(executable, 67108864)['sha256'],
                  'modules': modules, 'builtinMath': builtin}
    native = {'executablePath': str(executable), 'maps': maps, 'mapsSha256': helper.digest(raw_maps),
              'library': helper.file_identity(library, 100663296),
              'pythonProviders': [helper.file_identity(pathlib.Path(p), 67108864) for p in providers]}
    if darwin:
        native.update(platform='Darwin', systemVersion=platform.mac_ver()[0], mappingKind='dyld-images-v1')
    return arithmetic, native


def check(out, dependencies):
    sys.path.insert(0, str(dependencies))
    helper = load(out / 'tools/mps_witness_validation.py', 'mps_witness_validation')
    load(out / 'tools/verify_source_results.py', 'verify_source_results')
    checker = load(out / 'tools/verify_nra_source_results.py', 'verify_nra_source_results')
    record = json.loads((out / 'record.json').read_bytes())
    request = json.loads((out / 'request.json').read_bytes())
    source, model, raw = [(out / name).read_bytes() for name in ('source.smt2', 'model.smt2', 'stdout.txt')]
    bound = bindings(out)
    assert record['sourceHashes'] == {'model': bound['source.smt2']}
    assert record['sourceOutputSha256'] == bound['stdout.txt']
    scope = eligible(record)
    assert scope in (GROUND, REFUTATION) and request == {'scope': scope, 'queryMillis': QUERY_MS}
    assert (record.get('finalWitness') or '').encode('utf-8') == model
    assert not model or model in raw
    arithmetic, native = runtime_identity(out, dependencies, helper)
    result = evaluate(checker, source.decode('utf-8'), model.decode('utf-8'), scope)
    after_arithmetic, after_native = runtime_identity(out, dependencies, helper)
    assert after_arithmetic['modules'] == arithmetic['modules']
    assert after_arithmetic['pythonExecutableSha256'] == arithmetic['pythonExecutableSha256']
    before_builtin, after_builtin = arithmetic['builtinMath'], after_arithmetic['builtinMath']
    assert (before_builtin is None) == (after_builtin is None)
    if before_builtin is not None:
        assert all(before_builtin[k] == after_builtin[k] for k in ('origin', 'loader', 'executablePath', 'providers'))
    assert after_native['library'] == native['library']
    assert after_native['pythonProviders'] == native['pythonProviders']
    result.update(bindings=bound, groundArithmetic=arithmetic, nativeRuntime=native)
    print(json.dumps(result))


def supervise(out, expected, tools, parent):
    assert set(tools) == TOOLS and bindings(out) == expected
    assert json.loads((out / 'tools.json').read_bytes()) == tools
    runner = load(out / 'tools/smt_witness_validation.py', 'smt_witness_validation')
    assert all(runner.file_digest(out / 'tools' / name) == value for name, value in tools.items())
    scope = json.loads((out / 'request.json').read_bytes())['scope']
    assert scope in (GROUND, REFUTATION)
    runner.PARENT = parent
    runner.DEADLINE = time.monotonic() + (60 if scope == GROUND else 90)
    for signum in runner.SIGNALS:
        signal.signal(signum, runner.interrupt)
    atexit.register(runner.release)
    result = {'status': 'unknown', 'reason': 'source checking incomplete', 'scope': scope,
              'bindings': expected, 'toolHashes': tools, 'solverSearchQueries': 0,
              'proofCertificateChecked': False, 'checkedWitness': None, 'checker': CHECKER,
              'referenceComparison': None, 'referenceLogic': None, 'sourceDerivation': None,
              'solverQueryCountObserved': False,
              'setupMs': 0, 'checkMs': 0, 'cleanupComplete': False}
    runner.save(out / 'result.json', result)
    scratch = tempfile.TemporaryDirectory(prefix='smt-source-runtime-')
    working = str(pathlib.Path(scratch.name).resolve(strict=True))
    dependencies = pathlib.Path(working) / 'dependencies'
    try:
        start = time.monotonic_ns()
        try:
            bootstrap = runner.owned([sys.executable, '-I', str(out / 'tools/smt_witness_validation.py'),
                'bootstrap', str(out), str(dependencies)], out / 'bootstrap', working, 30, 2147483648,
                resident_memory=True)
        finally:
            result['setupMs'] = (time.monotonic_ns() - start) // 1000000
        resident = bootstrap.pop('residentMemory', None)
        if resident is not None:
            result.setdefault('residentMemory', {})['bootstrap'] = resident
        result['bootstrapProcess'] = bootstrap
        assert bootstrap['exit'] == 0 and bootstrap['complete'] and not any(
            bootstrap[k] for k in ('timedOut', 'overflow', 'parentStop')), 'pinned checker setup incomplete'
        result['runtime'] = json.loads((out / 'bootstrap.json').read_bytes())
        start = time.monotonic_ns()
        try:
            process = runner.owned([sys.executable, '-I', str(out / 'tools/smt_source_validation.py'),
                'check', str(out), str(dependencies)], out / 'check', working,
                30 if scope == GROUND else 40, 4294967296 if scope == GROUND else 8589934592,
                resident_memory=True)
        finally:
            result['checkMs'] = (time.monotonic_ns() - start) // 1000000
        resident = process.pop('residentMemory', None)
        if resident is not None:
            result.setdefault('residentMemory', {})['check'] = resident
        result['checkProcess'] = process
        assert process['exit'] == 0 and process['complete'] and not any(
            process[k] for k in ('timedOut', 'overflow', 'parentStop')), 'checker process incomplete'
        checked = json.loads((out / 'check.stdout').read_bytes())
        assert checked['bindings'] == expected and checked['scope'] == scope
        assert checked['status'] in ('valid', 'invalid', 'unknown') and checked['proofCertificateChecked'] is False
        result.update(checked)
    except (OSError, AssertionError, RuntimeError, ValueError, KeyError, TypeError) as error:
        result.update(status='unknown', reason=str(error)[:500], checkedWitness=None)
    finally:
        runner.release()
        result['processLogs'] = {}
        for prefix in ('bootstrap', 'check'):
            for stream in ('stdout', 'stderr'):
                path = out / (prefix + '.' + stream)
                if path.is_file():
                    result['processLogs'][path.name] = path.read_bytes().decode('utf-8', errors='replace')
        scratch.cleanup()
        result['cleanupComplete'] = not pathlib.Path(scratch.name).exists()
        if (not result['cleanupComplete'] or runner.stopped() or bindings(out) != expected
                or any(runner.file_digest(out / 'tools' / name) != value for name, value in tools.items())):
            result.update(status='unknown', reason='checker custody or cleanup incomplete', checkedWitness=None)
        runner.save(out / 'result.json', result)
    return 0


if __name__ == '__main__':
    if sys.argv[1] == 'supervise':
        raise SystemExit(supervise(pathlib.Path(sys.argv[2]), json.loads(sys.argv[3]),
                                   json.loads(sys.argv[4]), int(sys.argv[5])))
    elif sys.argv[1] == 'check':
        check(pathlib.Path(sys.argv[2]), pathlib.Path(sys.argv[3]))
    else:
        raise ValueError('unsupported fixed checker mode')
