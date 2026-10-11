# Input semantics

Format parsing and lowering live under
[formats](../klause/src/commonMain/kotlin/com/eignex/klause/formats/).
The [CLI](cli.md#formats-and-dispatch) lists dispatch names and extensions.

## Finite and open models

Source routing chooses a finite solve, an open-theory solve, a refutation or an
unsupported result. It can first prove that open integer sides have finite bounds.
That proof uses a slice of the shared preparation allowance and the solve's
cancellation token. Failure to close a bound must not replace it with a guessed
finite domain.

`-e` selects a finite engine and can control an open local-search request. Models
routed to a complete open theory use the shared theory solve independently of
that choice. SMT-LIB and MPS can fall back to open local search for
an unsupported fragment or objective; it supplies witnesses without a completeness
claim. Exact FlatZinc declines unsupported open fragments.
`--param open-bound-proof=false` disables the
routing proof for MPS, SMT-LIB and exact FlatZinc, allowing a supported source to
be measured through its open route. Unsupported syntax or a declined exact
operation remains undecided.

## Execution metadata and schema floats

`ir.SearchHints` carries ordered Boolean/integer variable groups, variable-selection
and value-selection intent, fallback policy and solution guidance. Frontends translate
syntax into these groups before finite execution; real-column identifiers never enter
integer branch groups. Backtracking owns selector allocation. Source expressions,
FlatZinc output, MiniZinc output templates and protocol comments belong to the
FlatZinc frontend. The shared pipeline consumes neutral finite execution data.

Schema float rows join the names they reference into components. Top-level
non-strict linear equalities and inequalities use real columns unless their component
also contains a strict/disequal row or an optional float. Such a component uses its
declared integer grid; unrelated Boolean constraints and float components do not
change that choice. Nested reified schema float expressions still decline. Shared
`lowering.SetLayout` owns sorted indicator universes and membership lookup; source
names, labels and set expressions stay with their callers.

Both compilers expose `FloatRepresentation` by source name, with component identity
and an explicit approximation reason. A null reason denotes source correspondence,
not a certificate from a solve. Grid search has no source completeness or optimum
claim. Independently check every original row and bound at the returned coordinates
before admitting a mixed model's witness; checking only the exact component is
insufficient. Schema callers can inspect this provenance before decoding or reporting
results. The benchmark strips grid proof credit even for a source-valid witness.

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

See [FlatZincFloatPolicy](../klause/src/commonMain/kotlin/com/eignex/klause/formats/flatzinc/FlatZincFloatPolicy.kt)
and [FlatZincIntegerFloatImages](../klause/src/commonMain/kotlin/com/eignex/klause/formats/flatzinc/FlatZincIntegerFloatImages.kt).

## SMT-LIB

The frontend supports quantifier-free linear integer and real arithmetic, including
difference fragments, Boolean structure and supported minimize/maximize objectives.
The theory packages cover difference arithmetic, LIA and LRA/LIRA. Bitvectors,
arrays, strings, quantifiers and unrestricted non-linear arithmetic are outside
this frontend's supported theory surface.

Asserted and reified equalities between an integer conditional result and a constant
can lower directly to Boolean tests of its branches. Completed conditional definitions and equality
results are shared within one parse; nested definitions are traversed iteratively.
Primitive equality literals are shared across source uses and branch expansion.
Comparisons finish open decision lists before expanding their branch tests.
Constant branch images of at most 1,024 distinct values can reject a comparison
before visiting its definition tree; unknown or larger images retain exact fallback.
Immutable guard meanings exclude unreachable arms from those images. A default
omits selector values covered by its guards, and a fully covered selector image
excludes the default even when that default is open arithmetic.
An integer conditional with a constant guard returns its selected branch directly.
Conditional definitions retain immutable equality meanings for their guards.
When a branch compares a simple integer variable with a constant, these meanings
can exclude conflicting selector tests and omit guard tests implied by that comparison.
Conflicting branches and excluded defaults are skipped before traversing their definitions.
Equality keys whose sign reversal exceeds a signed word retain their arithmetic encoding.
A parse expands comparisons after lowering all assertions and objectives, visiting
the newest comparisons first. Expansion shares a limit of 65,536 visits; comparisons
that cannot finish retain their exact arithmetic encoding. Numeric branch definitions remain enforced whenever
a surviving constraint, objective or shared reified predicate needs them. Unused
fresh definitions are omitted after following these dependencies to a fixpoint.
Their unused integer columns and private reified predicates are removed with
surviving constraints and objectives remapped in both variable namespaces.
The same dependency closure retains Boolean connective gates and arithmetic
predicate definitions only when a surviving use needs their value. Boolean-to-integer
channels remain consumers of an existing Boolean definition, so arithmetic uses retain
the gate and its inputs.
Source declarations and arithmetic leaves remain authoritative, including open
defaults and sparse decision-list selectors. Shared search has no unconstrained
predicate columns from these omitted definitions.
Decision lists whose selector tests have this Boolean expansion keep their shared
guarded encoding. They do not become numeric tables that require the symbolic
selector's auxiliary value; direct numeric selector tests remain eligible for
finite-domain table lowering.

The exact real component can certify supported difference fragments with a graph,
retaining the LP route for other states and conflict explanations. Its admission,
strict-edge encoding and lifecycle belong to the
[search contract](solver/search.md#open-real-arithmetic).

Optimization emits `; objective=<value>` for each incumbent in the source objective's
direction and `; optimizationStatus=<status>` at completion, including `optimal`,
`best-found` and `unbounded`. These comments appear without `-s`; `sat` alone
does not establish optimality.
Exact theory objectives use arbitrary-precision integer or reduced `numerator/denominator`
text, including values outside `Long` and non-terminating rational values.
Finite witnesses report objectives from certified real values and exact integer
sums. Supported linear integer optimization uses the complete theory route when
its objective range can exceed the consecutive-integer range of binary64.

Open integers retain arbitrary-precision source bounds. A linear relaxation witness
does not establish integrality: shared search splits fractional integers at exact
floor/ceiling thresholds. Strict real inequalities require an interior witness or
an exact strict-feasibility refutation. A closure point on a strict boundary is
insufficient. Budgets can leave the solve unknown; support for linear syntax is
not an unlimited completeness guarantee for every integer model.

### Polynomial source lowering

The internal `SmtLibPolynomial.parse` entry point preserves pure Real/Bool polynomial
satisfaction sources separately from `SmtLib.parse` and its linear factors. It performs
no theory selection and publishes no SAT/UNSAT result. The existing SMT facade and
CLI retain their supported linear routes.

`ir.PolynomialExpression` retains exact reduced arbitrary-precision rational leaves,
source real identities, sums, products and nonnegative integer powers. Relations
retain both polynomial sides and equality, disequality or strict/non-strict ordering.
`ir.PolynomialRealFormula` carries constants, source Boolean identities, negation,
conjunction and disjunction. The mathematical kernel owns polynomial normalization;
source IR does not implement sparse polynomial algebra. Structural equality describes
the owned expression/formula graph, rather than general algebraic equivalence.
`VarRemap` preserves coefficients, relations and Boolean polarity, including aliases;
removing a referenced variable declines. Captured operands and source maps cannot be
changed through caller buffers or getter results. Variable counts are checked before
publication.

The source entry point supports exact decimal/numeral coefficients, constant rational
division, repeated-variable products, powers with exact constant nonnegative integer
exponents (including lexical/helper-bound constants), chained comparisons, pairwise `distinct`,
Boolean equality/xor/implication and conditionals. Numeric conditionals use fresh real
identities with guarded defining equalities. Source declaration counts and name maps
remain separate from total auxiliary counts; unconstrained declarations stay present.
Name-map keys use canonical unquoted symbols; rendering must quote them when needed.
A syntactic nonlinear marker distinguishes products of multiple nonconstant operands
and nonconstant powers above one, without requiring polynomial expansion.

Parallel `let` values read the enclosing scope. Typed nonrecursive `define-fun` macros
capture their definition scope, and assertions retain the declarations visible at
that source command. Unsupported syntax is rejected even inside a constant Boolean
branch. One `check-sat` or `check-sat-assuming` query is accepted; Boolean literal
assumptions are conjoined with the assertions. `get-model` is accepted as a request.
Mixed integer sorts, symbolic or zero division, negative/noninteger/oversized powers,
uninterpreted functions, nonpolynomial operations, quantifiers, objectives, incremental
commands, multiple queries and `get-value` decline explicitly.

The dedicated parser accepts caller `Cancellation`, defaulting to `Never`. It polls
between streamed chunks, commands, evaluator frames and source identity validation,
and before/after rational coefficient folds. Each completed rational addition or
multiplication charges one source folding unit to the caller's meter. Interruption
throws the distinct internal `PolynomialSourceInterrupted` signal without publishing
a partial formula; invocation owners decide cancellation/budget result causality.
Polynomial expansion and decision work remain owned by the nonlinear kernel/theory.

## MPS

The MPS parser retains decimal source metadata, integer markers, bounds and
objective sense. The lowered LP may restate continuous coefficients in binary64.
An unconditional equality defining a free continuous column as an integral affine
sum of bounded source integers gives that column a finite integer representation
when the resulting rows and objective retain their source numbers. The proof uses
exact source coefficients and bounds; fractional definitions and inexact integer
lowerings remain continuous. Source column names, witness values and objective
units are preserved.
`sourceExact` records exact source correspondence; `toleranceDifference` identifies
a lowering difference relevant to tolerance publication. Rounded or oversized
pure-integer rows are rebuilt from source decimals; oversized objectives can use
an auxiliary defining row. Integer values and lower-bound shifts keep exact
authority beyond `2^53`.

Finite default MPS solves can accept a continuous leaf by source tolerance. Rows use

```text
1e-7 * max(1, abs(bound), sum(abs(a(i) * x(i))))
```

and column bounds use `1e-7 * max(1, abs(bound))`. Nonfinite row activity or
magnitude is checked again exactly against this scaled tolerance. The separate LP
gap test uses lowered objective units and accounts for reduced-cost movement, row
residuals and objective rounding. The rendered objective uses source decimal
coefficients at the returned point.

`TOLERANCE_OPTIMUM` differs from `ATTAINED_OPTIMUM`. A tolerance-accepted float
point is not an exact witness. Optimum publication requires no
`toleranceDifference`; infeasibility and unboundedness require `sourceExact`.
`--exact` requires exact certificates and declines a lowering that differs from
the source. Exact source witnesses use exact decimal checks when correspondence
holds, and decimal tolerance otherwise.

Purely continuous MPS sources use the finite LP lane, including fractional objectives.
Source-exact open mixed models use a supported complete theory when available.
Complete theory optimization requires an integral objective without continuous
or Boolean cost and a bound row remaining inside the supported theory. Other
objectives use open local search for incumbents without an optimum claim.

Default open models whose lowering differs from the source also use witness search.
Before publication, their arbitrary-width integer and rational real coordinates are
checked against original decimal rows, indicators and bounds with the source tolerance;
integer integrality remains exact. Their objective is recomputed from source decimals.
Rejected points are withheld and establish no refutation. These routes publish no
optimum, infeasibility or unboundedness claim, even when the lowered model was decided.
The source difference is reported beside the result. `--exact` retains its source
correspondence requirement. Check routing and source qualifications before attributing
a missing result to the simplex.

See [MpsCompiler](../klause/src/commonMain/kotlin/com/eignex/klause/formats/mps/MpsCompiler.kt)
and [MpsMode](../klause-cli/src/commonMain/kotlin/com/eignex/klause/cli/MpsMode.kt).

## Other finite formats

DIMACS CNF supplies Boolean clauses. WCNF supplies weighted partial MaxSAT.
OPB/WBO supplies pseudo-Boolean constraints and objectives, including supported
product and soft-constraint encodings. XCSP3 supplies finite-domain variables,
supported globals and optional objectives. Each frontend retains its source
objective orientation and output names. XCSP3 variable-matrix selection uses a
row-major index equality and a native `Element` factor. The compiler marks the
flattened index, selected result and affine expression outputs as functionally defined. Local search
maintains index-only affine chains from their source inputs; independently constrained
affine outputs remain searched. Eligible unique Element results follow indexes and selected
cells, with inverse repairs into their source coordinates or admissible cells. Competing
and cyclic definitions remain searched. Bounds on each source index enforce the matrix range when
its declared domain extends beyond it, including nonzero axis offsets; repeated
cells and aliases retain their source variable identities. Unsupported constructs
decline through the common solve surface. Boolean-valued XCSP arithmetic terms
use fresh 0/1 integers tied directly to their source literal by a reified equality,
including negated literals; products, sum terms and bin-packing indicators share this encoding.
Direct binary products of a Boolean expression and a declared integer or positive
numeric literal use their declared equality output directly. Literals use shared fixed
integer columns, so existing Product admission maintains the source output during moves.
Either operand and equality orientation and negated Boolean literals retain this encoding;
zero, negative literals and general arithmetic expressions retain ordinary arithmetic lowering.
The compiler marks the fresh binary channels as deterministic outputs. Domain-aware
definition inference maintains those literal values and their unique unary equality
predicates when the predicates have no other Boolean factor occurrences.

WCNF objectives with equal soft weights fold unit costs into the original Boolean
objective.
A negative unit adds its weight when the variable is true; a positive unit adds a
constant and a negative coefficient. Non-unit soft clauses use fresh relaxation
variables and hard disjunctions. Nonuniform weighted objectives retain relaxation
variables for their soft clauses. A unit also retains that encoding if folding its
cost would overflow a coefficient or constant. Empty soft clauses contribute fixed cost.

The benchmark's opt-in XCSP source checker evaluates the final assignment against
the original XML and exact source objective, independently of Klause lowering.
Checked witnesses and reported proof claims remain distinct; supported syntax,
record fields and the saved-output validation command are defined in the
[benchmark validation contract](../klause-bench/README.md#source-validation).

XCSP min/max arithmetic expressions mark deterministic auxiliaries and materialized affine
operands as definition hints. Top-level equalities retain output aliases; hints alone do
not orient unrelated sums or unhinted min/max globals. Domains and independently posted
output constraints remain authoritative during local-search definition maintenance.
