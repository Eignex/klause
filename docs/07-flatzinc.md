# 7. FlatZinc

FlatZinc is the flat constraint language that the MiniZinc compiler emits for a
solver, using the predicate declarations the solver advertises. Like the finite
formats of the previous chapter, it is a file of declarations and constraints, but
it adds float variables, annotations that steer search and output, and an output
model that reconstructs the original MiniZinc variables. The FlatZinc frontend
preserves source names, output annotations and constraints, and records whether
each float component uses exact arithmetic or a finite approximation.

The chapter starts with the declared semantics and how caller ranges change them,
then the integer and Boolean lowering, the annotations the frontend retains, and
the predicates it advertises as native. Floats come next, because their exact and
grid representations build on all of the above, and the chapter ends with output
rendering. A grid result never establishes an exact source optimum or refutation.

## Declared semantics and caller ranges

`flatZincSemantics` declares binary64 literal authority, default grid policy and
`[-1000000, 1000000]` for integer declarations without a range, and open real
endpoints for unranged float declarations. Parsing resolves
`SemanticOverrides` before declaration planning and retains both defaults and
effective policy in `FlatZincProgram.semantics`. String, streamed and execution
entries use the same resolver. Legacy positional arguments feed that resolver;
caller integer endpoints outside the default range are not clamped to it.

Integer endpoint replacements apply to unranged scalar and array declarations.
An omitted replacement keeps the other default endpoint; `BoundOverride.Open`
retains a genuinely open source side. An explicit source range or value set stays
authoritative, including restrictions introduced by a ranged alias. Open sides
remain marked in `Problem.intBounds`; the finite placeholder endpoints carry no
source-bound authority. Raw compilation retains the model without choosing a route.

Real endpoint replacements apply to unranged scalar and array declarations.
Continuous columns retain their effective finite or open sides, including bounds
outside the grid window. Explicit source ranges and their aliases remain
authoritative. A finite default range defines the grid for a bucketed declaration;
a missing side uses the approximation window. A disjoint window declines that grid
representation without refuting the source. Rational source-bound endpoints must
be exactly representable under binary64 authority; unsupported source endpoints
are not rounded. Private grid windows retain rational origins and steps separately
from their native approximate coordinates. Unsupported private scales or geometry
decline the view while preserving the original model and independently admitted routes.

Float grid windows remain separate from continuous source bounds. Typed grid
settings pass through the same declaration planner and its private placeholder
namespace. A singleton grid window cannot certify a finite source choice. Per-float
representation metadata identifies the actual approximated components separately
from the format's default grid policy.

The output protocol prints effective integer or real endpoints when they differ
from the defaults.
Such a run withholds default-source refutation, unboundedness, optimum markers
and unattained-bound metadata;
its effective-scope result is separate from proof of the unchanged default source.
Typed real defaults are available through `SemanticOverrides`; existing CLI float
endpoint arguments configure the approximation window. Legacy semantic
configuration is adapted at parsing boundaries.

## Integer extrema and selection

Binary `int_min`/`int_max` and array integer minimum/maximum predicates retain
`ArrayMinMax` factors. Integer and Boolean array selection retains `Element`,
including its one-based index range and ordered repeated entries. Result aliases
retain every operand role. MiniZinc's existing predicate wrappers use these same
FlatZinc paths. Finite projections use native filtering and scoring; selected
arithmetic projections install complete private guarded relations.

Set-array selection retains one shared one-based index across native `Element`
relations for its membership indicators. The union of all operand and result
universes includes implicit false indicators, so a selected member outside the
result universe remains forbidden. Empty universes still retain the index range.

Membership of an integer variable in a set retains its full signed-word domain.
Equality channels cover only declared universe elements present in that domain;
unreified membership requires one such element and its set indicator to hold.

## Integer division

`int_div` and `int_mod`, including MiniZinc-generated calls, retain truncating
`DivMod` factors. Matching calls share the quotient/remainder relation and link any
separate declared outputs with exact equalities. A zero divisor is forbidden by the
relation; a domain containing zero keeps its nonzero alternatives. Output aliases
and signed-word endpoints retain [exact division semantics](03.1-source-relations.md#integer-division-relations).

## FlatZinc Boolean channels

FlatZinc retains `bool2int` channels and recognizes them independently of constraint
order. Nonnegative weighted sums of channelled integers constrained to zero
(`<= 0` or `= 0`) lower to Boolean conjunctions over the complemented input
literals. Reified sums use the shared clause encoder; unreified sums pin those
literals. Zero coefficients contribute no requirement. Negative bounds on these
nonnegative sums encode false, leaving their input channels unrestricted in reified
forms. Positive bounds and signed
sums retain arithmetic propagation. Before emitting integer linear rows, lowering
estimates the total clause expansion of eligible rows. If it exceeds 32,768
clauses, all these rows retain arithmetic propagation. This model-wide limit
bounds expansion without making constraint order select a partial encoding.

## Wide integer distinctness

`all_different_int` and `alldifferent_except_0` retain `AllDifferent` across finite
signed-word ranges without an Int-sized union window or pairwise frontend rows.
The bounds annotation remains factor intent. Native propagation and local search
select their own bounded or sparse mechanisms; excluded values and source positions
survive reconstruction. [Distinct integer positions](03.1-source-relations.md#distinct-integer-positions)
defines finite enforcement and arithmetic-completion composition.

## Fixed powers

`int_pow`, `int_pow_fixed` and `klause_int_pow` retain `Power` for fixed
nonnegative integer exponents. Variable powers remain outside the native advertised surface. Raw variable or negative exponent predicates
decline. Exact `float_pow` accepts fixed nonnegative integral exponents and retains
real coordinates for polynomial completion. Default grid components retain
`GridPower` with the same fixed-exponent contract and nearest-result-bucket policy
as multiplication. Negative/fractional exponents and variable real exponents decline.

## Source annotation constraints

`redundant_constraint` and `symmetry_breaking_constraint`, including their `fzn_`
spellings, retain their Boolean requirement for every caller. The legacy
`forLocalSearch` hint does not change the source factor set or skip argument
validation. Direct wrappers and `klause_redundant` / `klause_symmetry` annotations
mark the emitted factors in `Problem.impliedFactorMask`; symmetry declarations also
set `Problem.hasSymmetryBreaking`.

Local search uses this source metadata to seed a lower violation weight through
`FactorWeightBook`. These factors remain enforced and available to every other
consumer. Presolve reads the symmetry flag before adding its own symmetry breaks.
[Local-search weights](15-local-search.md) belong to the selected consumer;
source parsing does not remove constraints for that policy.

## Source definitions

`FlatZincProgram.definitionHints` retains `defines_var` targets and integer/Boolean
relation data in the compiled source coordinates. Inputs retain constants, aliases,
repeated roles and literal polarity; strict comparisons retain their original
endpoint. Unrecognized or mismatched orientations remain reference hints. Source
constraints enforce every relation independently of these annotations.

Both plain parsing and the compatibility execution request capture this data before
snapshotting `Problem`, including any fixed literal columns referenced by the data.
Parsing does not order a definition graph or construct a local-search objective.
`parseFlatZincExecution` explicitly delegates its requested views to the native
consumer after the source program exists. [Native definition materialization](15.2-preparation.md#source-definition-materialization)
owns ordering, arithmetic and the gradient view. Model rewrites must remap hint
coordinates alongside their source mappings; a removed dependency declines a hint.

## Native predicate coverage

The MiniZinc library in `klause-mzn-lib` declares which predicates Klause handles
natively; everything else reaches the frontend as MiniZinc's standard
decomposition. Its `redefinitions.mzn` is the authoritative advertised surface. It
includes Boolean, integer and float linear predicates and declarations for the
supported globals. Every declaration must have a matching FlatZinc parser and
lowering path; declaring a predicate alone does not implement a constraint.

Fixed nonnegative integer `pow` uses the `int_pow_fixed` override and emits
`klause_int_pow`. Reifying it uses a private total power value and reifies only
its equality to the requested result. Negative powers keep the standard
integer-division decomposition, and variable powers are not advertised. `float_pow`
follows the same fixed-exponent contract described under [fixed powers](#fixed-powers).

Extending native coverage therefore means: implement the factor and its
model and lowering paths, declare the predicate, and add focused source and
end-to-end tests.

## FlatZinc floats

Canonical compilation retains original real declarations and strong real relations.
`FlatZincProgram.sourceProgram` and `sourceAttachment()` expose that frame without
requesting a grid. A Grid policy grants permission to a selected private view and
does not replace original real bounds or namespaces. Automatic execution
(`prepareSourceExecution`) prefers an admitted original complete arm; only when none
exists may the permitted grid view run, with the original route retained on decline.
Original execution uses original renderers and proof qualification. The legacy
`problem` getter, name decoders and `prepareFiniteCompatibility` request the lazy
finite compatibility view explicitly and bind it to the same source invocation.

Supported connected float operations retain exact relations: comparisons, strict
and reified comparisons, absolute values (`RealAbs`), minimum and maximum
(`RealArrayMinMax`), constant-array selection (`RealElement`), multiplication or
division by constants, and products. Finite float choices from constant-array
selection and singleton domains are kept as `RealFiniteDomain` memberships, and
products with a finite operand retain `ContinuousProduct` with the declared operand
values; unrestricted products retain their exact equality for
[polynomial completion](24-polynomial-real.md). Selected linear consumers install
the complete alternatives privately; frontends do not expand them into guarded rows,
and an LP relaxation alone cannot establish membership or a product. Literal-only
exact products retain fixed real operands so binary64 multiplication cannot round
away a failed exact equality. Finite integer float images retain affine maps to
source integer values, which must survive preparation and source checks.

In the compatibility view, a component that needs approximation, or has more than
4096 finite alternatives, uses integer buckets at a configurable resolution; this
limit selects a representation and never limits original declarations. Bucketed
search decides a finite grid. It may miss solutions between grid points, and
rounding can produce candidates violating the source constraints. Grid
`float_min`/`float_max` retain `GridArrayMinMax`, absolute values `GridAbs`, and
multiplication and fixed powers `GridProduct` and `GridPower`, each keeping its
step, origin and nearest-bucket rounding. Their native filtering handles at most
4096 operand combinations per pass, and selected private encodings decline above
that or on cancellation. A source-valid grid witness does not turn a grid optimum
or refutation into a source proof.

`--exact` preserves continuous variables and open bounds, routes supported linear
satisfaction through the shared real/mixed theory, and requires exact certification
for continuous objectives. Arithmetic overflow during lowering declines with a
diagnostic, as do integer constants in float arithmetic whose exact value binary64
cannot represent.

### Rounded FlatZinc bounds

Exact FlatZinc means mathematical arithmetic over the binary64 values of its parsed
float literals, with every declared bound retained. It does not mean decimal-literal
arithmetic, rounded execution of every operation, or inferred MiniZinc source semantics.
No epsilon or widened bound repairs a compiler-rounded interval. Components detected
with integer-image endpoints rounded across a declared bound decline exact mode;
default compilation records the rounded-arithmetic approximation reason and uses the
grid. This conservative decline prevents presenting a different source solution set
as an exact MiniZinc result.

For example, binary64 `0.01` is `5764607523034235/576460752303423488`. Multiplying
by integer `200` gives `144115188075855875/72057594037927936`, strictly greater
than a declared upper bound `2.0` by `3/72057594037927936`. The rational product
violates that bound, and substituting rounded `2.0` violates the exact row, so
neither assignment receives exact binary64 witness credit. Admitting such rounded
arithmetic would require an explicitly different semantics contract.

See [FlatZincFloatPolicy](../klause/src/commonMain/kotlin/com/eignex/klause/formats/flatzinc/FlatZincFloatPolicy.kt)
and [FlatZincIntegerFloatImages](../klause/src/commonMain/kotlin/com/eignex/klause/formats/flatzinc/FlatZincIntegerFloatImages.kt).

## MiniZinc output rendering

Authoritative rational witness coordinates are emitted as `% klause-exact:`
comments while standard assignments retain MiniZinc-compatible decimal rendering.
Mixed exact/grid witnesses use `% klause-coordinate:` comments with the exact
binary64 value of each decoded grid point, together with `% klause-float-component:`
comments identifying representation and approximation reason. These comments do
not certify an approximated component. Grid completion suppresses optimum markers
and turns grid refutation or unboundedness into unknown.

`OznApplier` renders solver bindings through the parsed `.ozn` output model.
Output integers, set elements, range endpoints and array indices retain signed
64-bit values. Solver finite-set layouts keep their integer storage; the applier
widens those elements at the rendering boundary. Integer arithmetic overflow and
solver integer bindings outside signed 64-bit range raise `OznParseException`
without publishing a partial solution block.

`array1d` through `array6d` retain each contiguous integer index set and coerce
source array elements in row-major order. Constructor cardinalities must match the
flat element count; subscripts must match the dimension count and lie within every
axis before computing a flat offset. Explicit integer sets used as indices must be
contiguous. One-argument `array1d` and one-dimensional concatenation rebase indices
to `1..length`.

Ranges remain symbolic for display and membership. Array axis cardinalities and
shape products must fit a signed 32-bit list size. Eager range expansion, array
literals, set accumulation, comprehension output and single-generator Cartesian
products are limited to 1,000,000 elements; one-dimensional concatenation and 2D
rendering row counts use the same limit. Unsupported sizes, invalid constructors
and invalid subscripts raise `OznParseException`.

---

Previous: [6. Finite input formats](06-finite-formats.md) · [Contents](README.md) · Next: [8. MPS source semantics](08-mps.md)
