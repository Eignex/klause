# 5. Schema lowering

The previous chapter set the rules every frontend follows. This is the first of
the frontends, and the only one that does not read a file: the Kotlin schema DSL.
A schema declares named variables and constraints in Kotlin, compiles them into
the shared source model and retains the metadata needed to decode solutions back
into the caller's names and types.

The chapter starts with how a schema is declared, compiled and bound to its
source model, then covers the integer constructs that need more than a direct
factor, and ends with floats and continuous products, whose real source
declarations stay exact while selected finite consumers may build a private grid.

## Declaring and compiling a schema

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

`CompiledSchema` retains one original source binding. `sourceId` and `sourceRealId`
refer to its original namespaces. Source solver factories use that identity and
compile a schema once. Each operation attaches fresh invocation state, using the
captured providers, caller controls and containing cancellation. A private view
cannot suppress an admitted original complete arm, and a representation decline
keeps the original model available. Decoders distinguish captured original and
compatibility frames by identity; a foreign frame rejects. Exact source publications
retain their original frame and exact coordinates independently of the invocation
lifetime. Shared `lowering.SetLayout` owns sorted indicator universes and membership
lookup; source names, labels and set expressions stay with their callers.

Helper objectives retain their finite native frame plus an immutable pairing to
original typed coordinates. Source `optimize` resolves this pairing through the
exact quadratic carrier, including coefficients wider than binary64's integer
precision; `CompiledSchema.sourceObjective` exposes the captured exact objective.
A maximize helper is already negated, and the operation's explicit sense remains
authoritative. `optimizeOpen` uses a lossless linear mapping when representable and
otherwise returns its incomplete unsupported result. Foreign helper owners reject
before execution.

## Integer extrema and selection

Nested integer `min`/`max` expressions retain `ArrayMinMax` factors over their
materialized operands, including repeated references. Integer array selection
retains an ordered `Element` with zero-based indexes; indexes outside the array
remain forbidden. Integer conditionals channel their source condition to a 0/1
index and retain an `Element` over the else and then values. Reified surrounding
comparisons retain these expression definitions and their original condition
polarity. Optional-value handling retains its existing presence guards and pins.

Finite consumers use native filtering and local-search scoring. Selected arithmetic
consumers use the complete private [selection encodings](03.2-engine-projections.md#factor-projection-selection).

## Integer division

Integer `/` and `%` preserve a shared truncating `DivMod` relation for the same
materialized operands. The schema requires the denominator domain to exclude zero.
Quotient and remainder remain correlated through nested arithmetic and reified
comparisons. [Integer division relations](03.1-source-relations.md#integer-division-relations)
define exact overflow, remapping and consumer support.

## Schema floats

`schemaSemantics` declares binary64 literal authority, grid defaults and absent-value
canonicalization. Both schema compile entries resolve `SemanticOverrides` before
lowering and retain the result on `Problem.semantics`. Integer and real declarations
require explicit ranges, so undeclared-range overrides are inapplicable. Compilation
preserves original real bounds, strict comparisons, disequalities, reified rows and
nonlinear relations. An omitted `FloatSpec.buckets` or DSL bucket argument captures
the effective grid default at compilation; an explicit count retains that variable's
present-value grid size. These counts describe consumer geometry; they do not
replace the original real namespace or constrain an exact source route.

The compatibility `problem` getter and finite solver constructors request a lazy
immutable finite view directly, without creating an unrelated solving invocation.
The compatibility policy selects connected strict, disequal and optional float
components for grids under `FloatSemantics.Grid`; unrelated exact components remain
real. Products and affine auxiliaries join their participating real components.
`FloatRepresentation` describes that compatibility choice. A continuous-only policy
retains original real rows, including strict and optional relations.

Selected grids own finite filtering, local scoring, scaled row coefficients and
private arithmetic encodings. Their linear scale and integer payload must be
representable; a declined geometry does not reject schema intake. Compatibility
float-linear rows truncate scaled coefficients to non-strict bounds; private affine
helper rows use nearest-integer quantization. Grid exhaustion supplies no
original-source refutation. Source execution reconstructs and checks complete
original coordinates before publication. An absent optional float equals zero
clamped to its declaration when absent pinning is enabled; when that value lies
between grid points, a private lattice carrier represents both the present grid and
the absent default, without adding present values. See
[private projections](03.1-source-relations.md#private-float-projections).

## Product representations

| Factor | Source relation | Execution boundary |
|---|---|---|
| `Product` | Int × Int → Int | Finite integer propagation and search |
| `RealProduct` | Finite Int × Real → Real | Fixing the integer operand gives an exact linear completion; unfixed operands can use LP relaxations |
| `ContinuousProduct` | Real × Real → Real | Shared polynomial completion, with finite factors remaining in their engine projection |

`factor.arithmetic.ContinuousProduct(a, b, result)` describes the exact real equality
`result = a * b` over the real namespace. Repeated operands express squares, and
results can feed other products. Fields and structural keys retain operand
multiplicity and the result role; remapping preserves aliases. The DSL exposes
`(x * x * y) eq z`, `(x * y) eq 2.0` and affine operands such as
`((x + 0.5) * y) eq z`; scalar `FloatTerm * Double` keeps its affine semantics.

`ContinuousRealEquality` lowers these expressions into real columns,
`ContinuousProduct` factors and exact affine equality rows. Named variables keep
their original IDs; anonymous real auxiliaries carry intermediate values, and equal
subexpressions within an equality reuse their column. Coefficients, constants and
bounds retain their exact binary64 rational meaning, and multiplication is never
folded with floating arithmetic. Continuous equalities requiring Boolean reification
are rejected; top-level constraints and conjunctions are supported.

Continuous products have inert finite deduction, heuristic local-search scores
requiring source certification, and no LP relaxation; LP leaf publication declines
while one remains. Shared pipeline entry points route them through
[shared polynomial completion](24-polynomial-real.md#shared-model-composition)
beside finite factors, across backtracking, local search, ALNS and mixed portfolios.
Selected private grids instead lower them to `GridProduct` and `GridPower`, whose
enforcing encodings admit at most 4096 operand combinations. Open integer domains
require their separate theory route rather than this finite projection.

---

Previous: [4. Input semantics](04-input-semantics.md) · [Contents](README.md) · Next: [6. Finite input formats](06-finite-formats.md)
