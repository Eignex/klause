# FlatZinc and MiniZinc source semantics

FlatZinc is the solver input produced by MiniZinc compilation. Its frontend
preserves source names, output annotations and constraints, and records whether
float components use exact arithmetic or a finite approximation. A grid result
does not establish an exact source optimum or refutation.

[MiniZinc integration](../minizinc.md) owns setup; [shared input semantics](../formats.md)
owns route selection. The sections below cover Boolean lowering, float
representation and output reconstruction.

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
and signed-word endpoints retain [exact division semantics](../model.md#integer-division-relations).

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
[Local-search weights](../solver/local-search.md) belong to the selected consumer;
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
consumer after the source program exists. [Native definition materialization](../solver/local-search.md#source-definition-materialization)
owns ordering, arithmetic and the gradient view. Model rewrites must remap hint
coordinates alongside their source mappings; a removed dependency declines a hint.

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

## Fixed powers

`int_pow`, `int_pow_fixed` and `klause_int_pow` retain `Power` for fixed
nonnegative integer exponents. The MiniZinc library routes fixed nonnegative
`pow` through `klause_int_pow`; its negative fixed powers retain the standard integer-division decomposition.
The MiniZinc float predicate retains `float_pow` for the same fixed exponent surface.
Variable powers remain outside the native advertised surface. Raw variable or negative exponent predicates
decline. Exact `float_pow` accepts fixed nonnegative integral exponents and retains
real coordinates for polynomial completion. Default grid components retain
`GridPower` with the same fixed-exponent contract and nearest-result-bucket policy
as multiplication. Negative/fractional exponents and variable real exponents decline.

## FlatZinc floats

Canonical compilation retains original real declarations and strong real relations.
`FlatZincProgram.sourceProgram` and `sourceAttachment()` expose that frame without
requesting a grid. Unrestricted products remain `ContinuousProduct` relations for
selected polynomial completion; finite-choice metadata preserves the existing
guarded linear consumers. A Grid policy grants permission to a selected private
view and does not replace original real bounds or namespaces.

The historical `problem`, name/array decoders and component metadata request a lazy
finite compatibility view. `prepareFiniteCompatibility` binds that view to the same
source invocation with the caller's actual operation, providers and containing stop.
`parseFlatZincExecution` uses this binding and exposes its `sourceInvocation`.
`prepareSourceExecution` selects automatic execution from the same original invocation.
The CLI reads canonical metadata and captures this invocation before requesting any
optional geometry; legacy compatibility getters do not select its route. The observed
overload retains actual preparation LP work, and CLI timing/logging covers that same
invocation for both original and private execution.
An actually admitted original complete arm takes precedence over a private grid; mixed
requests retain that original arm through the common dispatcher. Otherwise the permitted
compatibility view may run, with the original route retained on decline. Admission uses
the captured operation, objective and participating arms. Explicit
`prepareFiniteCompatibility` requests may still select private-grid CP or mixed execution.
Original execution uses original renderers and proof qualification; availability of a
legacy grid does not downgrade an original-source proof.
Cached geometry retains only immutable source/model definitions; another operation
binds its own consumer, checks and stop. Structured view declines preserve the
canonical original route. Source-frame samples use the original decoder; unbound
samples retain the documented legacy convention, and a bound foreign frame is rejected.

`FlatZincProgram` preserves its thirteen constructor, copy, component and property
signatures as an ordinary class. Positional access, logical equality, hashing and
string rendering use the public fields. A copy over the same model retains source
correspondence and legacy problem identity; an explicit foreign model drops it.
Kotlin data-class metadata and reflection `isData` change. Copying, equality, hashing
and string rendering may request the historical compatibility fields; source-only
metadata access uses `sourceProgram`.

A source-fixed float-array index also pins its finite value literal, preserving
Boolean propagation across the shared finite choices.
Continuous `float_min` and `float_max` retain `RealArrayMinMax`, including constant
operands and aliases. Complete arithmetic consumers own the equality alternatives.

Grid `float_min` and `float_max` retain `GridArrayMinMax`. Each coordinate keeps
its grid step, origin and scaling policy, so comparisons retain their signed
rounding even when operands use different bucket units. Native filtering enforces
the inequalities and requires an operand equality; selected linear/polynomial
consumers install the complete alternatives privately. Grid witness and proof
limits follow the component's existing approximation metadata.

Constant float-array selection retains `RealElement` over continuous result
columns and integer `Element` over grid bucket coordinates. Both retain the
one-based source index and repeated entries. Grid entries use the result's existing
bucket conversion and representability checks. Lowering visits array positions
inside the index domain, including domains wider than a signed integer.

The requested legacy compatibility view resolves aliases in a private declaration pass with one
shared integer placeholder namespace for integer and float columns. It captures the
caller's bounds, exact-float mode and `ProblemSettings`, including integer domain
storage. Exact-mode open floats stay open in this pass. Grid search bounds on
unranged floats are search windows, including singleton windows; they do not supply
source-fixed choices or exactness evidence. Explicit source ranges remain separate
from these fallback windows.

FlatZinc first preserves finite float choices from constant-array selection and
source singleton domains. Supported connected operations retain exact relations with
Boolean structure or native real selection. This includes comparisons, strict/reified comparisons, absolute
values, minimum/maximum, constant-array selection, multiplication/division by
constants, and products with a finite selected operand. Exact absolute values retain
`RealAbs` in the source model; the selected consumer installs its complete equality
alternatives privately. Implied inequalities alone do not enforce this relation.
Finite membership retains `RealFiniteDomain` for every applicable original real,
including disconnected declarations and compatibility components. No domain-cover
Booleans are allocated at intake. Finite-choice products retain `ContinuousProduct`
with their declared operand values, including constant results and operand aliases.
Other products retain their unrestricted original equality. The alternative limit
selects a requested compatibility representation; it does not discard canonical
operand membership or reject original source intake. Selected exact encoders retain
their containing resource and representation limits.
Private selected linear consumers install the complete product alternatives;
frontends do not expand those products into guarded rows. Literal-only exact
products retain fixed real operands so binary64 multiplication cannot round away
a failed exact source equality.
The finite backtracking facade uses the same selected exact linear completion for
these value covers. An LP relaxation alone cannot establish membership or a product.

Finite integer float images retain affine maps to source integer values. Fixed
integer selections and products can be eliminated while preserving source values
and output reconstruction. These maps must survive preparation and source checks.

A requested compatibility component requiring approximation, or more than 4096 finite alternatives, uses
integer buckets at a configurable resolution. Repeated array entries count toward
the alternative limit. This limit selects a private representation and does not limit
original real declarations or selection relations. Continuous components without finite choices also use the
grid in that compatibility view. Unconstrained floats outside the objective can remain
continuous. Unrestricted continuous-by-continuous products are outside linear
arithmetic.

Bucketed search decides a finite grid. It may miss solutions between grid points;
rounding can produce candidates violating the source constraints. Bucketed
absolute values retain `GridAbs` with the same rounding image; their tuple
encodings belong to selected private consumers. Grid multiplication and fixed powers
retain `GridProduct` and `GridPower` instead of frontend tables. Native filtering
limits each pass to 4096 operand combinations and checks pinned tuples; native
scoring grades the rounded relation. Selected private enforcing encodings decline
above 4096 operand combinations or on containing cancellation. Nonrepresentable
grid ranges decline with a source diagnostic. Source-check a
returned candidate before assigning it feasibility credit. Even a source-valid
grid witness does not turn a grid optimum or refutation into a source proof.

`--exact` preserves continuous variables and open bounds, routes supported linear
satisfaction through the shared real/mixed theory, and requires exact certification
for continuous objectives. Other retained real relations use their selected complete
or incomplete consumers without requesting a grid. Arithmetic overflow during lowering declines
with a diagnostic. Integer constants used in float arithmetic also decline when
their exact source value cannot be represented in binary64.

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

## Wide integer distinctness

`all_different_int` and `alldifferent_except_0` retain `AllDifferent` across finite
signed-word ranges without an Int-sized union window or pairwise frontend rows.
The bounds annotation remains factor intent. Native propagation and local search
select their own bounded or sparse mechanisms; excluded values and source positions
survive reconstruction. The independent FlatZinc source checker checks plain
and except-zero distinctness directly from the original coordinates. [Distinct integer positions](../model.md#distinct-integer-positions)
defines finite enforcement and arithmetic-completion composition.
