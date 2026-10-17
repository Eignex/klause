# Schema lowering

The Kotlin schema DSL compiles named variables and expressions into the shared
model, then retains metadata for decoding assignments. Integer and Boolean factors
use finite representations. Floats retain real source declarations; selected finite
consumers may construct a private grid over those declarations.

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

## Integer division

Integer `/` and `%` preserve a shared truncating `DivMod` relation for the same
materialized operands. The schema requires the denominator domain to exclude zero.
Quotient and remainder remain correlated through nested arithmetic and reified
comparisons. [Integer division relations](../model.md#integer-division-relations)
define exact overflow, remapping and consumer support.

## Schema floats

`schemaSemantics` declares binary64 literal authority, grid defaults and absent-value
canonicalization. Both schema compile entries resolve `SemanticOverrides` before
lowering and retain the result on `Problem.semantics`. Integer and real declarations
require explicit ranges, so undeclared-range overrides are inapplicable. The legacy
configuration arguments adapt their float bucket and absent-value choices into the
same capture; resource settings remain separate. Compilation preserves original real
bounds, strict comparisons, disequalities, reified rows and nonlinear relations.

An omitted `FloatSpec.buckets` or DSL bucket argument remains unspecified until
compilation, which captures the effective grid default. An explicit count retains
that variable's present-value grid size. These counts describe consumer geometry;
they do not replace the original real namespace or constrain an exact source route.

`CompiledSchema` retains one original source binding. `sourceId` and `sourceRealId`
refer to its original namespaces. Source solver factories use that identity and
compile a schema once. Each operation attaches fresh invocation state, using the
captured providers, caller controls and containing cancellation. A private view
cannot suppress an admitted original complete arm. A representation decline keeps
the original model available.

The compatibility `problem` getter and finite solver constructors request a lazy
immutable finite view directly, without creating an unrelated solving invocation.
The compatibility policy selects connected strict, disequal and optional float
components for grids under `FloatSemantics.Grid`; unrelated exact components remain
real. Products and affine auxiliaries join their participating real components.
`FloatRepresentation` describes that compatibility choice. A continuous-only policy
retains original real rows, including strict and optional relations.

Selected grids own finite filtering, local scoring, scaled row coefficients and
private arithmetic encodings. Their linear scale and integer payload must be
representable; a declined geometry, linear scale or coefficient does not reject
schema intake. Compatibility float-linear rows retain truncation of scaled
coefficients and non-strict bounds; private affine helper rows use nearest-integer
quantization. These choices belong to the selected view, not the original rows.
Grid exhaustion supplies no original-source refutation. Source execution reconstructs
and checks complete original coordinates before publication, with private proof
claims retained separately. See [private projections](../model.md#private-float-projections).

An absent optional float equals zero clamped to its original real declaration when
absent pinning is enabled. If that value lies between present grid points, a separate
private lattice carrier represents both the requested present grid and the absent
default. Presence guards channel that carrier to the unchanged present grid; absence
pins the default and the dead selector. Every private relation and objective uses
the carrier. No extra present value or different bucket count is introduced. A
nonrepresentable carrier declines the view. Disabling absent pinning removes the
source pin and its conditional carrier.

Decoders distinguish captured original and compatibility frames by identity. A
caller-created unbound sample follows the compatibility convention; a foreign
frame rejects. Optional decoding tests presence first. Exact source publications
retain their original frame after checking and preserve exact coordinates and
certificates independently of the invocation lifetime.

Helper objectives retain their finite native frame plus an immutable pairing to
original typed coordinates. Copy and negation preserve or explicitly replace exact
coefficients and constants. Source `optimize` resolves this pairing through the
exact quadratic carrier, including affine objectives and coefficients wider than
binary64's integer precision. A maximize helper is already negated; the operation's
explicit sense remains authoritative. `optimizeOpen` uses a lossless linear mapping
when representable and otherwise returns its incomplete unsupported result. Foreign
helper owners reject before execution.

Shared `lowering.SetLayout` owns sorted indicator universes and membership lookup;
source names, labels and set expressions stay with their callers.

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

Continuous products have inert finite deduction and native floating local-search
scores and repairs. Their zero score requires source certification; an LP completion
cannot certify their nonlinear equalities. Shared pipeline entry points
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

A named or constant result can be the retained product or power's result coordinate;
intermediate expressions use shared real auxiliaries. Selected private grids lower
these original relations to `GridProduct` and `GridPower`, using the same value
carrier for optional operands and all other rows. Auxiliary ranges belong to the
private geometry. Native finite filtering and scoring consume those private
relations. Enforcing encodings admit at most 4096 operand combinations; larger
requests decline that encoding while native finite execution remains available.
Continuous equalities requiring Boolean reification are rejected; top-level
constraints and conjunctions are supported.
Continuous products declare inert finite deduction, heuristic scoring requiring
source certification and no LP relaxation. LP leaf publication declines feasible or tolerance-accepted completions
when a continuous product remains; exact linear refutations remain sound. The
[polynomial theory](../theories/polynomial-real.md) owns nonlinear witness authority, and its
polynomial-source admission is separate from this schema representation.
