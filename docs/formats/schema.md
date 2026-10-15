# Schema lowering

The Kotlin schema DSL compiles named variables and expressions into the shared
model, then retains metadata for decoding assignments. Integer and Boolean factors
use finite representations. Float components choose continuous real columns or an
explicit finite grid according to the operations in that component.

This page owns source correspondence and refused contexts during schema lowering.
[Development](../development.md#kotlin-dsl) gives a small DSL example;
[model ownership](../model.md) and [library API](../api.md) define storage and mappings.

## Integer extrema and selection

Nested integer `min`/`max` expressions retain `ArrayMinMax` factors over their
materialized operands, including repeated references. Integer array selection
retains an ordered `Element` with zero-based indexes; indexes outside the array
remain forbidden. Integer conditionals channel their source condition to a 0/1
index and retain an `Element` over the else and then values. Reified surrounding
comparisons retain these expression definitions and their original condition
polarity. Optional-value handling retains its existing presence guards and pins.

Finite consumers use native filtering and local-search scoring. Selected arithmetic
consumers use the complete private [selection encodings](../model.md#factor-projection-selection).

## Schema floats

Schema float rows join the names they reference into components. Top-level
non-strict linear equalities and inequalities use real columns unless their component
also contains a strict/disequal row or an optional float. Such a component uses its
declared integer grid; unrelated Boolean constraints and float components do not
change that choice. Nested reified schema float expressions still decline. Shared
`lowering.SetLayout` owns sorted indicator universes and membership lookup; source
names, labels and set expressions stay with their callers.

Schema and FlatZinc compilers expose `FloatRepresentation` by source name, with component identity
and an explicit approximation reason. A null reason denotes source correspondence,
not a certificate from a solve. Grid search has no source completeness or optimum
claim. Independently check every original row and bound at the returned coordinates
before admitting a mixed model's witness; checking only the exact component is
insufficient. Schema callers can inspect this provenance before decoding or reporting
results. The benchmark strips grid proof credit even for a source-valid witness.

## Product representations

| Factor | Source relation | Execution boundary |
|---|---|---|
| `Product` | Int × Int → Int | Finite integer propagation and search |
| `RealProduct` | Finite Int × Real → Real | Fixing the integer operand gives an exact linear completion; unfixed operands can use LP relaxations |
| `ContinuousProduct` | Real × Real → Real | Shared polynomial completion, with finite factors remaining in their engine projection |

### Continuous product representation

`factor.arithmetic.ContinuousProduct(a, b, result)` describes the exact real equality
`result = a * b`. All three IDs belong to the real namespace. Repeated operands
express squares; results can feed other products. Membership deduplicates real IDs,
while fields and structural keys retain operand multiplicity and the result role.
Keys treat operand order as commutative. Remapping applies the real mapping to every
role and preserves aliases, including aliases introduced by a noninjective map.
`Product` and `RealProduct` retain their integer and mixed semantics.

`ContinuousRealExpr` stores affine terms and product trees in the model AST.
The schema DSL supports `(x * x * y) eq z` for `FloatTerm` operands and
continuous expression chains. Scalar `FloatTerm * Double` retains its affine DSL
semantics; multiplying a continuous expression by a constant retains a product node.
[Source lowering](schema.md#schema-continuous-products) defines exact constants,
auxiliary columns and refused contexts.

Continuous products have no finite propagator, local-search score or finite
assignment checker. Direct finite execution projections reject them, and an LP
completion cannot certify their nonlinear equalities. Shared pipeline entry points
route them through [shared polynomial completion](../theories/polynomial-real.md#shared-model-composition)
beside finite factors across backtracking, local search, ALNS and mixed portfolios.
Exact rational/algebraic witnesses retain their source coordinates. This route
supports finite integer domains and affine objectives over original Boolean, integer
and real coordinates. `OpenTheoryPipeline.optimize` normalizes a `LinearObjective`
losslessly into the existing exact quadratic executor and preserves source direction,
exact values, attainment and incomplete results. Exact quadratic objectives use their
objective overload. Open integer domains require
their separate theory route rather than this finite projection. [Source-aware strategy factories](../api.md#source-aware-strategy-entry-points) arrange
shared source composition for CP and LS. The retained baked solver facades expose
finite sessions and projections, with explicit finite-only constructor guidance.

### Schema continuous products

`ContinuousRealEquality` lowers continuous affine expressions and multiplication
chains into real columns, `ContinuousProduct` factors and exact affine equality
rows. The shared `ProblemBuilder.continuousProduct` entry checks every ID against
allocated real columns. Named schema variables keep their original IDs; anonymous
real auxiliaries carry intermediate affine values and product results. Equal source
subexpressions within an equality reuse their column, including square operands.
Constant terms use fixed real columns. Coefficients, constants and declared bounds
retain their exact binary64 rational meaning. Multiplication nodes are never folded
with floating arithmetic, so a chain preserves its exact polynomial relation.

The DSL exposes `(x * x * y) eq z`, `(x * y) eq 2.0` and affine operands such as
`((x + 0.5) * y) eq z`. Existing scalar affine arithmetic keeps its established
coefficient construction semantics. Model AST serialization preserves the product
tree and named affine operands. Source AST maps are borrowed read-only during
compilation, like other model payloads.

Products connect all participating float variables before representation selection.
A product connected to a grid-selected strict, disequal or optional float component
is rejected rather than approximated. Continuous equalities requiring Boolean
reification are rejected; top-level constraints and conjunctions are supported.
Continuous products declare unsupported finite execution capabilities and no LP
relaxation. LP leaf publication declines feasible or tolerance-accepted completions
when a continuous product remains; exact linear refutations remain sound. The
[polynomial theory](../theories/polynomial-real.md) owns nonlinear witness authority, and its
polynomial-source admission is separate from this schema representation.
