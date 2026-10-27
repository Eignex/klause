# 3. Input semantics and the schema DSL

A frontend preserves source declarations, relations, objective and names in a shared
model. Numeric interpretation belongs to the captured semantics; execution selection
belongs to the pipeline. This chapter defines the common contract and the Kotlin
schema frontend. The next two chapters cover file formats.

## Source contracts

| Source | Detailed contract |
|---|---|
| Kotlin schema DSL | [Schema lowering](#schema-lowering): float representation, exact products and refused contexts |
| DIMACS, WCNF, OPB/WBO and XCSP3 | [Finite formats](04-finite-formats.md): encodings and objectives |
| FlatZinc | [FlatZinc semantics](04-finite-formats.md#flatzinc): Boolean channels, exact/grid floats and reconstruction |
| MPS | [MPS semantics](05-arithmetic-formats.md): decimal correspondence, exact certification and tolerances |
| SMT-LIB | [SMT-LIB semantics](05-arithmetic-formats.md#smt-lib-source-semantics): admission, lowering and exact models |

## Numeric authority and execution selection

Formats declare their defaults as
[typed source semantics](02-model.md#typed-source-semantics) that caller overrides
replace before lowering; [source setup](19-api.md#source-setup) consumes them.
MPS attaches its original linear snapshot to the same setup and selects numerical
execution under its captured certification policy.

| Source | Numeric authority |
|---|---|
| Library IR and schema DSL | Declared integers, binary64 rational real coefficients and explicit exact objective coefficients |
| FlatZinc and compiled MiniZinc | Exact float components use binary64 rational literals; grid components retain their declared approximation |
| SMT-LIB | Exact numerals, lexical scopes and original declarations; no invented integer domains |
| MPS | Original decimals, indicator conditions and objective units; tolerance publication and exact certification are distinct |
| XCSP3 | Finite integer arithmetic only |
| DIMACS/WCNF and OPB/WBO | Boolean declarations and exact integer weights |

An exact source representation does not select an arithmetic engine. Completion
consumes captured encoder support, and a numerical representation limit can
withhold one projection while the source model stays the same. See
[encoder composition](14-theories.md#extension-roles-and-completeness).

## Finite and open models

A frontend admits only syntax it can preserve losslessly under its declared
semantics. A supported model can still receive UNKNOWN from an incomplete or
resource-limited solve; unsupported syntax keeps its own diagnostic.

Source routing chooses a finite solve, an open-theory solve, a refutation or an
unsupported result. It can first prove that open integer sides have finite bounds,
using a slice of the preparation allowance; failure to close a bound never replaces
it with a guessed domain. `--param open-bound-proof=false` disables that proof for
MPS, SMT-LIB and exact FlatZinc.

`-e` selects complete traversal, local search, ALNS or a mixed portfolio over the
selected route. Supported linear and polynomial sources keep the same constraints
and objective across those strategies. SMT-LIB can fall back to open local search
for an unsupported fragment, supplying witnesses without a completeness claim;
exact FlatZinc declines unsupported open fragments. MPS keeps finite search for
bounded integer choices and numerical branch-and-bound for surviving open
integers. That open numerical route declines LS and ALNS (see
[MPS semantics](05-arithmetic-formats.md#routing-and-result-strength)).

## Solving coverage

This is current execution coverage, separate from what a frontend can parse.
The format chapters define source-specific admission and strategy limits.

| Model family | Mechanisms | Boundary |
|---|---|---|
| Boolean and finite integer factors | Finite propagation and backtracking; local search and portfolios | Refutations and optima require completed traversal and proof checks |
| [Difference logic](14-theories.md#difference-logic) | Integer graph components or real graph checks inside the linear route | Requires complete fragment coverage |
| [Linear real arithmetic](15-linear-arithmetic.md) | Exact real theory or certified LP completion | Bounds, strictness and Boolean structure stay authoritative |
| [Linear integer arithmetic](15-linear-arithmetic.md#linear-integer-arithmetic) | Shared search, exact linear checks, integer reductions and branching | Open integer state stays theory-owned |
| [Linear mixed arithmetic](15-linear-arithmetic.md#linear-mixed-arithmetic) | Exact linear checks with integer branching | Relaxation feasibility alone does not establish integrality |
| [Nonlinear integer arithmetic](16-nonlinear-arithmetic.md) | Exact integer candidates and repairs; quadratic optimization by integer branching | Incomplete; exhausted allowances yield UNKNOWN |
| [Polynomial real arithmetic](16-nonlinear-arithmetic.md#polynomial-real-arithmetic) | Exact conditioned completion with backtracking, local search, ALNS and portfolios | Finite factors and exact quadratic objectives compose |
| [Transcendental real arithmetic](16-nonlinear-arithmetic.md#transcendental-real-arithmetic) | Exact function checks through shared traversal and LS/ALNS | Incomplete; unsupported equality certification yields UNKNOWN |
| [Quadratic optimization](16-nonlinear-arithmetic.md#quadratic-objectives) | Checked convex bounds, objective-image completion and integer splits | Nonconvex or unresolved branches withhold global claims |

A relaxation supplies deductions, not support for omitted source constraints.
[Factor capabilities](../klause/src/commonMain/kotlin/com/eignex/klause/solver/FactorExecutionCapabilities.kt)
list the implemented engine roles.

## Arithmetic role preservation

Arithmetic products retain every operand occurrence and the separate result role,
including coordinates shared by several roles, and remapping preserves those
positions. Native projections and selected encodings describe the same source
equation. Bounded integer/real products keep finite completion when available.

Schema multiplication, FlatZinc `int_times` and XCSP3 `mul` retain repeated operands
and result aliases. Fixed nonnegative powers from the schema, FlatZinc, bounded
SMT-LIB and XCSP3 retain `Power`; SMT-LIB polynomial snapshots keep native power
expressions. MPS, DIMACS and WCNF have no product path. OPB/WBO monomials are
conjunctions: repeated literals are idempotent and complementary literals make the
term false.

## Absolute-value source forms

Schema integer `abs`, FlatZinc `int_abs`, SMT-LIB integer `abs` and XCSP3
`abs`/`dist` retain [absolute-value relations](02-model.md#absolute-value-relations).
Wide SMT operands use `AffineAbs`; FlatZinc floats use `RealAbs` or `GridAbs`
under their selected semantics. SMT-LIB real `abs` is unsupported; the other file
formats have no absolute-value syntax.

## Execution metadata

`ir.SearchHints` carries ordered Boolean/integer variable groups, selection intent,
fallback policy and solution guidance. Frontends translate syntax into these groups
before finite execution; real columns never enter integer branch groups, and
backtracking owns selector allocation.

## Schema lowering

The Kotlin DSL compiles named declarations and constraints and retains typed handles
for decoding source assignments.

### Declaring and compiling a schema

A schema is a `VariableSchema` subclass. Its delegated properties declare Boolean,
integer, float, nominal and set variables (`boolVar`, `intVar`, `floatVar`,
`nominal`, `setVar`, with optional `opt…` forms), and `constraint` blocks state
relations over them:

```kotlin
class Order : VariableSchema() {
    val a by intVar(min = 1, max = 3)
    val b by intVar(min = 1, max = 3)
    val c by intVar(min = 1, max = 3)
    val unique by constraint { allDifferent(a, b, c) }
}

val schema = Order()
val compiled = schema.compile()
for (sample in BacktrackSolver(compiled).enumerate().take(5)) {
    println(compiled.decode(schema.a, sample))
}
```

`compile()` lowers the declarations into a `Problem` and returns a `CompiledSchema`.
Integer and Boolean variables use finite representations; optional variables add
presence literals and guards, and constraints over them lower to reified or
aggregation-aware forms. `CompiledSchema.decode` maps a sample back to each handle's
Kotlin value, testing presence first for optional handles.

`CompiledSchema` retains one original source binding; `sourceId` and `sourceRealId`
refer to its original namespaces. Source solver factories compile a schema once and
attach fresh invocation state per operation. A private view cannot suppress an
admitted original complete arm, and decoders reject a foreign frame. Shared
`lowering.SetLayout` owns sorted indicator universes for set variables.

Helper objectives keep their finite native frame plus a pairing to original typed
coordinates. Source `optimize` resolves it through the exact quadratic carrier,
including coefficients wider than binary64's integer precision, and
`CompiledSchema.sourceObjective` exposes the exact objective. `optimizeOpen` uses a
lossless linear mapping when representable and otherwise returns unsupported.

### Schema JSON

`definition()` snapshots a schema as a `SchemaDef` of `SchemaEntry` rows, the model
the compiler reads. Its JSON form gives each node one operator key holding its
operands: a named reference is `{"ref": name}`, an integer literal is a bare number,
positional operands follow the Kotlin field order and other nodes name their fields
with defaults omitted. `JsonSchema.parse` reads such documents and
`JsonSchema.jsonSchema()` returns the draft 2020-12 JSON Schema derived from the
same serializers.

```json
{"entries": {
  "size": {"int": [1, 10]},
  "shift": {"nominal": ["day", "night"]},
  "nightMinSize": {"constraint": {"implies": [
    {"nominalEq": ["shift", "night"]},
    {"ge": [{"ref": "size"}, 3]}]}}}}
```

| Vocabulary | Keys |
|---|---|
| `SchemaEntry` | `bool`, `presence`, `nominal`, `int`, `float`, `set`, `multiple`, `constraint` |
| `IntExpr` | `ref`, literal, `add`, `mul`, `truncDiv`, `truncMod`, `pow`, `abs`, `min`, `max`, `if`, `element`, `card` |
| `BoolExpr` logic | `ref`, `notRef`, `nominalEq`, `not`, `and`, `or`, `xor`, `implies`, `iff`, `atMost`, `atLeast`, `cardinality`, `pseudoBoolean` |
| `BoolExpr` relations | `le`, `lt`, `ge`, `gt`, `eq`, `ne`, `floatLinear`, `realEq`, set `in`, `labelIn`, `subset`, `disjoint`, `setEq` |
| `BoolExpr` globals | `allDifferent`, `symmetricAllDifferent`, `inverse`, `table`, `regular`, `costRegular`, `mdd`, `costMdd`, `circuit`, `subcircuit`, `cumulative`, `disjunctive`, `sort`, `increasing`, `diffn` and the `…Opt` presence forms |
| `SetExpr` | `ref`, `elements`, `labels`, `union`, `intersect`, `diff` |
| `ContinuousRealExpr` | `affine`, `mul`, `pow` |

Integer vocabularies accept only integer literals; `floatLinear` keeps binary64
coefficients, and exact real expressions form their own vocabulary. Decoding is
strict: unknown keys, wrong operand counts and constructor invariants fail at their
document path, such as `$.entries.size.int[1]`. `ModelJson.catalog` composes the
vocabularies into other compact formats; non-JSON formats keep the sealed-class
representation.

### Renaming references

Each expression family and `SchemaEntry` has `mapReferences`, which rewrites every
variable reference: integer, Boolean, set and continuous operands, global arrays,
optional-form presence lists and the value a `presence` entry gates. Nominal labels
are values and stay unchanged. Each traversal is an exhaustive `when` over its
sealed family, so a new node does not compile until it rewrites its references.
`SchemaDef.renameDeclarations` applies one function to the entry keys and to every
reference inside the entries, preserving order, and rejects two entries mapped to
one name. A rename that merges two coefficients of one continuous affine term is
rejected, since adding them would round in binary64.

### Portable expressions

Schema JSON and Kumulant share one expression profile, so a fragment such as
`{"ge": [{"ref": "value"}, 0]}` is a schema constraint or a statistical filter. The
host fixes the numeric environment: schema integer expressions evaluate exactly,
Kumulant scalar expressions in binary64, rounding each node in the nesting given. A
key names one mathematical operation everywhere; an environment that cannot
evaluate it rejects the expression rather than reinterpreting it.

| Shape | Keys |
|---|---|
| reference | `{"ref": name}` |
| literal | bare number |
| single bare operand | `abs`, `not` |
| two operands, left then right | `add`, `mul`, `min`, `max`, `pow`, `and`, `or`, `lt`, `le`, `eq`, `ge`, `gt` |
| condition, then, else | `if` |

A schema binds each reference to the declared variable of its operand's sort, and
compilation fails naming an unbound one. An integer literal is a number with an
integral value in 32-bit range, in any notation such as `100`, `100.0` or `1e2`, and
`pow` takes a nonnegative integer literal exponent with `0^0 = 1`. Literals such as
`2.5`, and any other exponent, are binary64 only, and integer vocabularies reject
them. Both environments write integral values as integer tokens, so integral
fragments stay portable. Two-operand keys take exactly two operands in binary64,
where a longer list would hide the rounding order. Integer vocabularies also decode
`add`, `min`, `max`, `and` and `or` over any nonempty list, and name truncating
division `truncDiv` and `truncMod`, whose remainder takes the dividend's sign.
`div` is binary64 division with IEEE 754 non-finite results, and integer
vocabularies reject it. Klause's other keys, and Kumulant's `sub`, `neg` and
transcendental functions, are extensions of one side.

Schema JSON writes the Kotlin DSL's integer and Boolean trees in portable shapes.
`add`, `min`, `max`, `and` and `or` nodes with more than two children nest to the
left, so `x + 1 + 2` is `{"add": [{"add": [x, 1]}, 2]}`. Subtraction adds the
negated operand, and negation and constant multiplication are `mul` with the integer
constant first, so `x - y` is `{"add": [x, {"mul": [-1, y]}]}`. Integer evaluation
is exact, so the nesting never changes a value. Decoding keeps every grouping except
an `add`, `and` or `or` chain nested in the first operand, which merges into its
parent as the DSL's `+`, `and` and `or` build it, and reads `mul` with a numeric
left operand as a scaled term. Canonical documents therefore re-encode unchanged,
and trees built with the DSL operators and receiver helpers such as `x.min(y)`
decode to the trees that encoded them; an n-ary `minOf` or `maxOf` decodes as its
nested chain.

The conformance fixture
`klause/src/jvmTest/resources/portable-expression/conformance.json`, which Kumulant
keeps verbatim, records each environment's values, canonical encodings and
rejections, and the Kotlin DSL construction of each DSL-emitted shape.

The Kotlin builders of both libraries spell the shared operations alike: infix
`lt`, `le`, `eq`, `ne`, `ge` and `gt` with a literal on either side, arithmetic
operators with literal operands, the receiver helpers `x.abs()`, `x.min(y)`,
`x.max(y)` and `x.pow(n)`, and `ifThenElse(cond, then, otherwise)` with literal or
expression branches. Integer `/` and `%` build `truncDiv` and `truncMod`, and
`minOf` and `maxOf` build the variadic integer forms. Each library keeps its own
nodes, so equal spellings can still encode differently: Klause writes `ne` and
constant multiples with its `ne` and `scale` keys.

### Integer extrema and selection

Nested integer `min`/`max` expressions retain `ArrayMinMax` factors over their
operands, including repeated references. Array selection retains a zero-based
`Element`, and integer conditionals channel their condition to a 0/1 index over an
`Element` of the else and then values. Reified surrounding comparisons keep the
original condition polarity. Finite consumers use native filtering and scoring;
arithmetic consumers use the [selection encodings](02-model.md#factor-projection-selection).

### Integer division

Integer `/` and `%` share one truncating `DivMod` relation for the same operands,
so quotient and remainder stay correlated. The schema requires the denominator
domain to exclude zero. See [integer division relations](02-model.md#integer-division-relations).

### Schema floats

`schemaSemantics` declares binary64 literal authority, grid defaults and
absent-value canonicalization; compilation resolves `SemanticOverrides` and keeps
the result on `Problem.semantics`. Declarations require explicit ranges, so
undeclared-range overrides do not apply. Compilation preserves original real
bounds, strict comparisons, disequalities, reified rows and nonlinear relations. A
`FloatSpec.buckets` count describes consumer geometry only; it never constrains an
exact source route.

The compatibility `problem` getter and finite solver constructors request a lazy
finite view. Under `FloatSemantics.Grid` it grids connected strict, disequal and
optional float components while unrelated exact components stay real;
`FloatRepresentation` describes that choice. Grids own finite filtering, scoring and
private encodings, and a geometry they cannot represent declines the view without
rejecting the schema. Grid exhaustion is no source refutation: source execution
reconstructs and checks original coordinates before publication. See
[private projections](02-model.md#private-float-projections).

### Product representations

| Factor | Source relation | Execution |
|---|---|---|
| `Product` | Int × Int → Int | Finite propagation and search |
| `RealProduct` | Finite Int × Real → Real | Exact linear completion once the integer is fixed; LP relaxation otherwise |
| `ContinuousProduct` | Real × Real → Real | Exact polynomial completion beside finite factors |

The DSL admits product equalities such as `(x * x * y) eq z`, `(x * y) eq 2.0`
and `((x + 0.5) * y) eq z`; multiplication by a constant stays affine.
`ContinuousRealEquality` reuses equal subexpressions and lowers to products and
exact affine rows without floating multiplication. Boolean reification of these
equalities is unsupported. Private grids use `GridProduct` and `GridPower` under
their captured approximation policy.

---|---|---|
| `Product` | Int × Int → Int | Finite integer propagation and search |
| `RealProduct` | Finite Int × Real → Real | Fixing the integer operand gives an exact linear completion; unfixed operands can use LP relaxations |
| `ContinuousProduct` | Real × Real → Real | Shared polynomial completion, with finite factors remaining in their engine projection |

`factor.arithmetic.ContinuousProduct(a, b, result)` is the exact real equality
`result = a * b`; repeated operands express squares and results can feed other
products. The DSL exposes `(x * x * y) eq z`, `(x * y) eq 2.0` and affine operands
such as `((x + 0.5) * y) eq z`, while `FloatTerm * Double` stays affine.
`ContinuousRealEquality` lowers these into real columns, products and exact affine
rows, reusing equal subexpressions and never folding multiplication in floating
point. Equalities that need Boolean reification are rejected.

Continuous products have no finite deduction or LP relaxation and only heuristic
scores, so shared entry points route them through
[shared polynomial completion](16-nonlinear-arithmetic.md#shared-model-composition)
beside finite factors. Private grids lower them to `GridProduct` and `GridPower`
instead.

---

Previous: [2. Model representation and ownership](02-model.md) · [Contents](README.md) · Next: [4. Finite formats and FlatZinc](04-finite-formats.md)
