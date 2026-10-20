# 5. Schema lowering

The previous chapter set the rules every frontend follows. This is the first of
the frontends, and the only one that does not read a file: the Kotlin schema DSL.
A schema declares named variables and constraints in Kotlin, compiles them into
the shared source model and retains the metadata needed to decode solutions back
into the caller's names and types.

The chapter covers how a schema is declared and compiled, the integer constructs
that need more than a direct factor, and floats and continuous products.

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

## Integer extrema and selection

Nested integer `min`/`max` expressions retain `ArrayMinMax` factors over their
operands, including repeated references. Array selection retains a zero-based
`Element`, and integer conditionals channel their condition to a 0/1 index over an
`Element` of the else and then values. Reified surrounding comparisons keep the
original condition polarity. Finite consumers use native filtering and scoring;
arithmetic consumers use the [selection encodings](03.2-engine-projections.md#factor-projection-selection).

## Integer division

Integer `/` and `%` share one truncating `DivMod` relation for the same operands,
so quotient and remainder stay correlated. The schema requires the denominator
domain to exclude zero. See [integer division relations](03.1-source-relations.md#integer-division-relations).

## Schema floats

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
[private projections](03.1-source-relations.md#private-float-projections).

## Product representations

| Factor | Source relation | Execution boundary |
|---|---|---|
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
[shared polynomial completion](24-polynomial-real.md#shared-model-composition)
beside finite factors. Private grids lower them to `GridProduct` and `GridPower`
instead.

---

Previous: [4. Input semantics](04-input-semantics.md) · [Contents](README.md) · Next: [6. Finite input formats](06-finite-formats.md)
