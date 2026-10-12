# FlatZinc and MiniZinc source semantics

FlatZinc is the solver input produced by MiniZinc compilation. Its frontend
preserves source names, output annotations and constraints, and records whether
float components use exact arithmetic or a finite approximation. A grid result
does not establish an exact source optimum or refutation.

[MiniZinc integration](../minizinc.md) owns setup; [shared input semantics](../formats.md)
owns route selection. The sections below cover Boolean lowering, float
representation and output reconstruction.

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

## FlatZinc floats

FlatZinc first preserves finite float choices from constant-array selection and
singleton domains. Supported connected operations lower to exact linear rows with
Boolean structure. This includes comparisons, strict/reified comparisons, absolute
values, minimum/maximum, constant-array selection, multiplication/division by
constants, and products with a finite selected operand.

Finite integer float images retain affine maps to source integer values. Fixed
integer selections and products can be eliminated while preserving source values
and output reconstruction. These maps must survive preparation and source checks.

A component requiring approximation, or more than 4096 finite alternatives, uses
integer buckets at a configurable resolution. Repeated array entries count toward
the alternative limit. Continuous components without finite choices also use the
grid under default lowering. Unconstrained floats outside the objective can remain
continuous. Unrestricted continuous-by-continuous products are outside linear
arithmetic.

Bucketed search decides a finite grid. It may miss solutions between grid points;
rounding can produce candidates violating the source constraints. Source-check a
returned candidate before assigning it feasibility credit. Even a source-valid
grid witness does not turn a grid optimum or refutation into a source proof.

`--exact` preserves continuous variables and open bounds, routes supported linear
satisfaction through the shared real/mixed theory, and requires exact certification
for continuous objectives. It declines unsupported float operations and expansion
above the alternative limit. Arithmetic overflow during lowering also declines
with a diagnostic.

Authoritative rational witness coordinates are emitted as `% klause-exact:`
comments while standard assignments retain MiniZinc-compatible decimal rendering.
The benchmark's independent checker reads the original FlatZinc text, interpreting
float literals as binary64 rationals and checking exact coordinates without rounding.
Its `flatzinc-binary64` scope is distinct from pinning original MiniZinc source.
Mixed exact/grid witnesses retain rational exact coordinates and the exact binary64
value of each decoded grid point, together with `% klause-float-component:` comments
identifying representation and approximation reason. Mixed/grid samples use
`% klause-coordinate:` rather than `% klause-exact:`. Grid CLI completion suppresses
optimum markers and turns grid refutation or unboundedness into unknown. These coordinate comments do
not certify an approximated component. Missing exact coordinates or unsupported
source predicates leave validation unknown.

### Rounded FlatZinc bounds

Exact FlatZinc means mathematical arithmetic over the binary64 values of its parsed
float literals, with every declared bound retained. It does not mean decimal-literal
arithmetic, rounded execution of every operation, or inferred MiniZinc source semantics.
No epsilon or widened bound repairs a compiler-rounded interval. Components detected
with integer-image endpoints rounded across a declared bound decline exact mode;
default compilation records the rounded-arithmetic approximation reason and uses the
grid. This conservative decline prevents presenting a different source solution set
as an exact MiniZinc result.

For the cylinder scaling row, binary64 `0.01` is
`5764607523034235/576460752303423488`. Multiplying by integer `200` gives
`144115188075855875/72057594037927936`, strictly greater than the declared upper
bound `2.0` by `3/72057594037927936`. The rational product violates that bound;
substituting rounded `2.0` violates the exact row. Decimal `1/100` would yield `2`,
but choosing it would change the literal policy. Neither assignment receives exact
binary64 witness credit. A default grid candidate may receive only feasibility
credit from an independent original MiniZinc source check under its numerical
semantics. An exact flattened checker reports its separate scope and cannot establish
that source claim. Source-feasible rounded arithmetic requires an explicitly different
semantics contract before admission to an exact route.

See [FlatZincFloatPolicy](../../klause/src/commonMain/kotlin/com/eignex/klause/formats/flatzinc/FlatZincFloatPolicy.kt)
and [FlatZincIntegerFloatImages](../../klause/src/commonMain/kotlin/com/eignex/klause/formats/flatzinc/FlatZincIntegerFloatImages.kt).

## MiniZinc output rendering

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
