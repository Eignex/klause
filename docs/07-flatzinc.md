# 7. FlatZinc

FlatZinc is the flat constraint language that the MiniZinc compiler emits for a
solver, using the predicate declarations the solver advertises. Like the finite
formats of the previous chapter, it is a file of declarations and constraints, but
it adds float variables, annotations that steer search and output, and an output
model that reconstructs the original MiniZinc variables. The frontend preserves
source names, output annotations and constraints, and records whether each float
component uses exact arithmetic or a finite approximation.

The chapter covers the declared semantics, integer and Boolean lowering, retained
annotations and native predicates, then floats, which build on all of these, and
finally output rendering. A grid result never establishes an exact source optimum
or refutation.

## Declared semantics and caller ranges

`flatZincSemantics` declares binary64 literal authority, a default grid policy,
`[-1000000, 1000000]` for unranged integer declarations and open endpoints for
unranged floats. Parsing resolves `SemanticOverrides` before declaration planning
and keeps both defaults and effective policy in `FlatZincProgram.semantics`.

Endpoint replacements apply only to unranged scalar and array declarations; an
explicit source range or value set, including one introduced by a ranged alias,
stays authoritative. `BoundOverride.Open` keeps a genuinely open side, and the
finite placeholder endpoints carry no source-bound authority. Rational real
endpoints must be exactly representable in binary64 and are never rounded.

Float grid windows stay separate from continuous source bounds. A window disjoint
from the declared range declines the grid without refuting the source, and a
singleton window cannot certify a finite choice. When effective endpoints differ
from the defaults, the output protocol prints them and withholds default-source
refutation, unboundedness and optimum markers.

## Integer extrema and selection

`int_min`/`int_max` and array minimum/maximum predicates retain `ArrayMinMax`.
Integer and Boolean array selection retains `Element` with its one-based index
range and ordered repeated entries. Set-array selection shares one index across
`Element` relations for its membership indicators, so a selected member outside the
result universe stays forbidden. Integer set membership retains the full
signed-word domain of its variable.

## Integer division

`int_div` and `int_mod` retain truncating `DivMod` factors. Matching calls share one
quotient/remainder relation and link separate declared outputs by exact equalities.
A divisor domain containing zero keeps its nonzero alternatives (see
[integer division relations](03.1-source-relations.md#integer-division-relations)).

## FlatZinc Boolean channels

The frontend recognizes `bool2int` channels independently of constraint order.
Nonnegative weighted sums of channelled integers constrained to zero lower to
Boolean conjunctions over the complemented inputs; reified sums use the shared
clause encoder. Positive bounds and signed sums keep arithmetic propagation. If the
eligible rows would expand to more than 32,768 clauses in total, all of them keep
arithmetic propagation, so constraint order never selects a partial encoding.

## Wide integer distinctness

`all_different_int` and `alldifferent_except_0` retain `AllDifferent` over finite
signed-word ranges without a union window or pairwise rows. Native propagation and
local search choose their own mechanisms (see
[distinct integer positions](03.1-source-relations.md#distinct-integer-positions)).

## Fixed powers

`int_pow`, `int_pow_fixed` and `klause_int_pow` retain `Power` for fixed
nonnegative integer exponents; variable or negative exponents decline. Exact
`float_pow` accepts fixed nonnegative integral exponents over real coordinates for
polynomial completion, and grid components retain `GridPower`.

## Source annotation constraints

`redundant_constraint` and `symmetry_breaking_constraint` (and their `fzn_`
spellings) keep their Boolean requirement. Wrappers and the `klause_redundant` and
`klause_symmetry` annotations mark the emitted factors in `Problem.impliedFactorMask`,
and symmetry declarations set `Problem.hasSymmetryBreaking`. Local search seeds a
lower violation weight for these factors, and presolve reads the symmetry flag
before adding its own breaks; the factors stay enforced for every consumer.

## Source definitions

`FlatZincProgram.definitionHints` retains `defines_var` targets and their relation
data in compiled source coordinates, with constants, aliases, repeated roles and
polarity. Unrecognized orientations remain reference hints. Parsing does not order
a definition graph; [definition materialization](15.2-preparation.md#source-definition-materialization)
does that on request. Source constraints enforce every relation independently of
these annotations.

## Native predicate coverage

The MiniZinc library in `klause-mzn-lib` declares which predicates Klause handles
natively; everything else arrives as MiniZinc's standard decomposition. Its
`redefinitions.mzn` is the authoritative advertised surface: Boolean, integer and
float linear predicates and the supported globals. Every declaration needs a
matching parser and lowering path. Fixed nonnegative integer `pow` emits
`klause_int_pow`, and its reification reifies only the equality to the result.

Extending native coverage means implementing the factor and its lowering,
declaring the predicate, and adding focused and end-to-end tests.

## FlatZinc floats

Canonical compilation retains original real declarations and exact real relations;
`FlatZincProgram.sourceProgram` and `sourceAttachment()` expose that frame without
a grid. A grid policy only permits a private view. Automatic execution prefers an
admitted original complete arm and runs the grid view only when none exists. The
legacy `problem` getter and `prepareFiniteCompatibility` request the finite
compatibility view explicitly.

Supported float operations retain exact relations: strict and reified comparisons,
`RealAbs`, `RealArrayMinMax`, constant-array `RealElement`, constant scaling and
products. Finite float choices become `RealFiniteDomain` memberships, products with
a finite operand keep `ContinuousProduct` with the declared values, and
unrestricted products go to [polynomial completion](24-polynomial-real.md). Linear
consumers install alternatives privately; an LP relaxation alone never establishes
membership or a product.

In the compatibility view, a component that needs approximation or has more than
4,096 finite alternatives uses integer buckets. Bucketed search decides a finite
grid: it may miss solutions between grid points, and rounding can produce
candidates that violate the source. Grid relations (`GridArrayMinMax`, `GridAbs`,
`GridProduct`, `GridPower`) keep their step, origin and nearest-bucket rounding.

`--exact` preserves continuous variables and open bounds, routes linear
satisfaction through the shared real/mixed theory and requires exact certification
for continuous objectives. Overflow during lowering and integer constants that
binary64 cannot represent decline with a diagnostic.

### Rounded FlatZinc bounds

Exact FlatZinc means mathematical arithmetic over the binary64 values of parsed
literals, with every declared bound kept. No epsilon repairs a compiler-rounded
interval. Components whose integer-image endpoints were rounded across a declared
bound decline exact mode; default compilation records the approximation and uses
the grid.

For example, binary64 `0.01` times integer `200` is
`144115188075855875/72057594037927936`, which exceeds a declared upper bound `2.0`
by `3/72057594037927936`. The rational product violates the bound and substituting
`2.0` violates the row, so neither assignment receives exact witness credit. See
[FlatZincFloatPolicy](../klause/src/commonMain/kotlin/com/eignex/klause/formats/flatzinc/FlatZincFloatPolicy.kt).

## MiniZinc output rendering

Exact rational coordinates are emitted as `% klause-exact:` comments beside
MiniZinc-compatible decimal assignments. Mixed exact/grid witnesses add
`% klause-coordinate:` and `% klause-float-component:` comments with each grid
point's binary64 value and approximation reason; these certify nothing. Grid
completion suppresses optimum markers and turns grid refutation into unknown.

`OznApplier` renders bindings through the parsed `.ozn` output model with signed
64-bit integers. `array1d` through `array6d` keep contiguous index sets and
row-major order, and subscripts are checked against every axis. Ranges stay
symbolic; expansion, comprehensions and Cartesian products are limited to
1,000,000 elements. Overflow, unsupported sizes and invalid subscripts raise
`OznParseException` without publishing a partial solution block.

---

Previous: [6. Finite input formats](06-finite-formats.md) · [Contents](README.md) · Next: [8. MPS source semantics](08-mps.md)
