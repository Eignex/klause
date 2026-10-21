# 2. Model representation and ownership

The previous chapter followed a problem from source to result. This chapter
describes the data that travels along that path. A source model is the
engine-neutral description of a problem: its variable declarations, its factors
(the constraint relations over those variables) and its objective. Engines never
search the source model directly. Each builds its own view: finite domains for
propagation, assignments for local search, arithmetic state for theory or
relaxation checks. A view never authorizes a result by itself.

One ownership rule runs through the chapter: source data stays unchanged while any
solve uses it, each live operation owns its own mutable state, and results are
published as immutable assignments. The chapter covers declarations and
results first, then the [source relations](#source-relations) factors can hold,
and finally the [engine projections](#engine-projections) preparation builds
from them.

## Source declarations and factor data

`Problem` stores source integer declarations as bounds and, where necessary,
`SourceIntDomains` value sets. Mutable search domains belong to `BakedProblem` and
the propagation session; a declaration assigns no engine ownership.

`Problem` arrays and `Factor` payloads are borrowed read-only: their producer keeps
them unchanged while any model, plan, lazy operation or proof uses them. A rewrite
creates a fresh source scope. Custom factors keep their membership and structural
keys stable.

Real coordinates are declared the same way. `RealFiniteDomain` retains an immutable
finite set of exact binary64 values for one real coordinate, and a selected finite
grid never rounds a declared value onto the grid. `Power` retains a fixed
nonnegative integer exponent over integer or real coordinates; real powers are
enforced by a selected arithmetic encoder.

## Typed source semantics

A source model records how its syntax must be read. `ir.SourceSemantics` describes
numeric literal authority, float interpretation, default undeclared ranges, source
feasibility tolerance and absent optional values. A format declares only the
policies that apply to it, and a caller cannot introduce an absent one.

`SemanticOverrides` replaces applicable policies before lowering. `BoundsOverride`
replaces either default endpoint independently (`BoundOverride.Open` removes one);
declarations with an explicit range keep it (see
[MPS semantics](05-arithmetic-formats.md#declared-policy)). `SourceTolerance` declares slack as
`max(absolute, relative * scale)`, where a row's scale is the larger of its finite
bound magnitude and the sum of absolute term activities. Zero components require
exact checking, and integrality is always exact.

`EffectiveSourceSemantics` keeps both the defaults and the effective policy.
`isApproximate` marks a grid or positive tolerance and `hasOverrides` records any
change; neither supplies proof authority. Source rewrites, baking, presolve views
and private projections preserve this capture, and it selects no route or engine.

Every result carries the interpretation it was produced under: samples, witness
certificates and verdicts expose nullable `semantics`, even when interrupted
without an assignment. Changing witness coordinates discards the old certificate,
and the qualification never promotes a verdict's proof authority.

## Configuration and resources

`ProblemSettings` carries immutable resource metadata, such as the domain-storage
threshold, pre-bake span gate and automatic LP size limits, through source
rewrites and projections. None of it alters the declared solution set. Schema
compilation and format entry points resolve `KlauseConfig.current` once or accept
explicit settings; search effort stays in per-call backend parameters.

## Published assignments and objectives

`Sample`, exact theory assignments and reconstructed witnesses own immutable
coordinates. Array getters return independent buffers, and scalar reads retain
exact integer and rational authority. Replacing coordinates requires a fresh
witness check. `Sample.symbolicReals` retains exact analytic expressions for
transcendental coordinates (see
[exact analytic real coordinates](18-exact-arithmetic.md#exact-analytic-real-coordinates)).

Linear objectives capture their coefficient arrays at construction, so no getter
or copy can mutate them. Incumbent extensions supply immutable assignment and
objective payloads, or capture producer state in their verifier before acceptance.

## Quadratic objective representation

`ir.QuadraticObjectiveSpec` captures an exact rational constant and ordered
`QuadraticObjectiveTerm` values over typed source IDs. An affine term denotes
`coefficient * first`; a product denotes `coefficient * first * second`, with a
repeated coordinate expressing a square. Coefficients are literal reduced
rationals, and direction belongs to the invocation. Linear objectives bridge into
this representation with unchanged IDs. The
[certificate kernel](18-exact-arithmetic.md#exact-quadratic-certificates) checks
rational convex subproblems, and [polynomial real arithmetic](16-nonlinear-arithmetic.md#polynomial-real-arithmetic)
owns quadratic optimization results.

## Optional completed-result evidence

Completed satisfaction and minimization results can retain `SourceResultEvidence`
when requested through `SourceResultCapture`. Claims name the original revision,
invocation restrictions, objective and declared semantics, with exact values kept
apart from floating telemetry. Missing traversal or bound data is explicit;
[derivation checking](13-evidence.md) owns coverage checks.

## Source relations

Factors retain the relation the source stated, not an encoding chosen for one
engine. Each relation below follows the same pattern: the factor keeps its exact
source payload through remapping, finite propagation and local search build their
own projections of it, and selected arithmetic encoders install an equivalent
formula when a theory needs the relation. A relaxation derived from a factor never
enforces it.

### Source definition hints

`ir.DefinitionHint` records an integer or Boolean output orientation and its source
relation data, with operand order, constants, multiplicity, strictness and
polarity. Reference hints retain annotations whose functional form is unavailable.
Source factors remain the authority for all constraints. `DefinitionHint.remap`
renumbers every role; a removed required coordinate declines the hint rather than
inventing a value. [FlatZinc](04-finite-formats.md#source-definitions) produces these
records, and [local search](10-local-search.md#source-definition-materialization)
materializes its private view only when requested.

### Distinct integer positions

`AllDifferent` retains integer positions, optional-presence literals and excepted
values; excepted values may repeat and optional positions participate only when
present. Finite propagation chooses sparse matching over enumerable domains,
bounds filtering over wider ones, and fixed-value deductions where interval
arithmetic would overflow. Local search uses sparse value counts and bounded repair
sampling on wide domains.

Hall and assignment cuts apply only to plain mandatory distinctness. Selected
linear and polynomial encoders preserve the mandatory, optional and excepted forms
with exact source values, and open integer declarations stay open. Finite globals
compose with arithmetic completion through their finite owner, as described by
[theory composition](14-theories.md#shared-composition-contract).

### Distinct-value counts

`NValue` retains its counted positions, target, comparison mode, optional presence
literals and excluded values. Reductions to equality or all-different apply only
without exclusions or optional positions. Finite propagation bounds the count of
definitely-present non-excluded values, using matching and kernel filters for the
mandatory form, and checks the exact count at a complete assignment. Local search
maintains exact counts and move deltas. The optional LP value hull is a relaxation;
open arithmetic encoders do not claim NValue coverage.

### Integer division relations

`factor.arithmetic.DivMod` retains a dividend, a literal or variable divisor and
both outputs. `DivisionConvention.TRUNCATING` rounds the quotient toward zero;
`EUCLIDEAN` requires a nonnegative remainder. Both require a nonzero divisor and
`dividend = divisor * quotient + remainder` with the remainder smaller in magnitude
than the divisor. Arithmetic is exact and never wraps. The internal `WideDivMod`
form retains exact affine digit views for values past the signed word.

Frontends share quotient and remainder auxiliaries only for identical operands and
convention. Shared formulas live in `lowering.DivModEncoding`: the linear encoder
handles a literal divisor or requires a variable-divisor fixing, and the
polynomial encoder enforces the whole relation including the sign cases. An open
variable-divisor relation has no general linear completion, and difference
encoding is unsupported. Finite propagation combines signed bounds with support
filtering over at most 4,096 operand pairs per call;
[local search](10-local-search.md#definitions-and-repairs) maintains eligible
outputs and correlated repairs.

### Absolute-value relations

`factor.arithmetic.Abs` retains the exact relation `result = |operand|`. Schema
expressions, FlatZinc `int_abs`, SMT-LIB integer terms and XCSP3 `abs`/`dist`
produce it. Linear consumers receive nonnegativity and the disjunction of the
positive and negative equalities; polynomial consumers receive the equivalent
formula. Finite propagation keeps sign-aware bounds and filters holes on domains of
at most 1,024 values. Local search checks the full relation and repairs either role.

Two variants share this contract. `AffineAbs` retains wide SMT absolute values over
exact affine payloads. `RealAbs`, from exact FlatZinc `float_abs`, holds the
relation over real coordinates and is enforced by selected linear alternatives.
Bucketed `float_abs` instead retains `GridAbs` under the
[grid policy](#grid-arithmetic-relations).

### Extrema and element relations

`ArrayMinMax` and integer `Element` keep their native payloads, and their linear,
difference and polynomial encoders share relation construction in `lowering`. An
extremum bounds every operand and equals at least one of them; an element guards
each selected equality by its exact index value. Native finite projections remain
available alongside arithmetic participation.

`RealArrayMinMax` and `RealElement` are the real-valued counterparts: their implied
inequalities form a relaxation, and complete encoders also require the selected
equality. `GridArrayMinMax` filters with exact integer activities and establishes
nothing outside the declared grid policy.

### Exact linear source relations

`factor.arithmetic.ExactLinear` captures rational comparisons over integer, Boolean
and real coordinates. Boolean terms contribute their literal's zero/one truth value;
complemented literals contribute an exact constant offset before aliases coalesce
and denominators clear. Strict real
comparisons keep their open boundary; integer-only strict comparisons use the
equivalent integral threshold. An optional Boolean activator reifies the whole
comparison in both directions. The numerical projection emits representable scaled
rows; a row it cannot represent weakens the relaxation and leaves enforcement to
the selected exact linear or polynomial consumer.

`LinearIndicator` retains one integer trigger, its polarity and an exact enabled
row. It is a one-way implication: the disabled branch leaves the row unrestricted.
Finite checks, numerical rows and source verification all read the same trigger,
and live pins remain scoped premises.

### Weighted Boolean relations

`Clause` retains a disjunction of source literals. Its polynomial encoder emits
that Boolean structure directly, without private numeric indicator coordinates.

`PseudoBoolean` and `ReifiedPseudoBoolean` retain exact integral coefficients,
literal polarity, repetitions, comparison and right-hand side; the reified form
keeps both directions of equivalence. Both expose the relation as a tagged
`LinearRow` for exact theory installation. Native projections use `Long` arithmetic
only when activities and residuals fit it, and exact coalesced sums otherwise.
Presolve lifting, clique and knapsack bounds and LP rows admit only what they can
represent; a declined projection leaves finite enforcement intact.
[Finite formats](04-finite-formats.md#opb-and-wbo-lowering) defines OPB/WBO
objective normalization.

### Affine distinctness

`factor.global.Distinct` retains exact affine operands over integer, Boolean and
real coordinates with arbitrary-width rational coefficients. An optional activator
is equivalent to the whole relation, so a false activator requires a collision.
Finite propagation checks pinned operands; local search grades collisions exactly
over finite points, while real-coordinate scores are binary64 heuristics.
Distinctness supplies no numerical relaxation. Linear encoders expand it into exact
pairwise comparisons, and polynomial encoders defer the expansion to query
normalization. Finite sources with complete native support stay with their finite
owner.

### Finite real product choices

`ContinuousProduct` retains an exact real product and may capture a declared finite
operand domain with Boolean literals equivalent to each operand value. The linear
encoder installs an exact value cover, guarded product rows and guard equivalences;
the polynomial encoder enforces the same domain, guards and product. Witness checks
include the product, domain and guards, and rays keep the finite operand fixed.
Unguarded products receive no LP completion authority.

### Grid arithmetic relations

`GridProduct` and `GridPower` retain an operation and each index's binary64 range
and bucket count. The nearest-output-bucket relation approximates continuous
arithmetic: exact membership in it does not establish a continuous-source witness.
Filtering passes and private encodings admit at most 4,096 input combinations;
larger encodings decline while native filtering and scoring remain.

### Private float projections

An explicitly selected finite or heuristic consumer may request a private integer
grid under the captured `FloatSemantics.Grid` permission; ordinary source setup
never materializes it. Original real declarations, relations and objective remain
on the attachment, and private helpers stay distinct from original coordinates.
Unsupported factors or resource stops decline only this view.

Publication reconstructs engine coordinates into the private grid, then into
original real coordinates. Source-qualified consumers check the original model;
FlatZinc consumers publish under their declared grid semantics instead. Grid
exhaustion and optimization prove only grid claims, kept in
`FiniteSolveResult.nativeGridOutcome`. FlatZinc also reports these through
`outcome`, with `SourceFloatProjection.declaredGridSemantics` recording the scope.
See [source checking](07-presolve.md#source-witness-validation) and
[reconstruction](07-presolve.md#guarantees-and-source-mappings).

## Engine projections

The source model and its relations are never searched directly. Before a solve,
preparation decides which engine owns each variable and factor, and each engine
builds its own projection of the factors it receives. This section describes the
execution plan, how factor capabilities are selected, and how engine state and
options stay separate from the source.

### Engine projections and factor capabilities

#### Binding a source to its execution

`solver.pipeline.SourceAttachment` retains the original `Problem` or native source
snapshot (polynomial, integer polynomial or real-function) with its captured
semantics. `SourceSetup.prepare` binds the operation, objective, finite engine
choice, providers and preparation token; `SourceSetup.execute` dispatches the
finite or exact arithmetic operation. A bounded integer/Boolean source with
supported native propagation selects finite ownership before any arithmetic
installation, while real coordinates and exact-objective preferences keep
arithmetic preparation. Every file frontend uses this policy, and interrupted
preparation publishes no partial plan.

#### The prepared plan

`Problem.prepareExecutionPlan()` selects immutable ownership and completion data
before search, attempting sound open-bound closure before final nonlinear
admission. A relation's encoder support and available finite completion select the
arithmetic language; a factor class alone does not require polynomial traversal.

`PreparedSourcePlan` retains the original source, canonical execution model,
coordinate mappings, component descriptors and objective direction. Its sealed
payload is finite/linear (`ComponentPlan`), polynomial (`SharedPolynomialModel`),
nonlinear-integer (see [its chapter](16-nonlinear-arithmetic.md#solving-and-composition)),
transcendental or declined. Preparation invents no finite bounds for open integer
declarations: variables owned by an open theory never become finite CP domains,
and continuous coordinates are never integer branch candidates.

`LinearSourceProblem` retains the original rational terms, bounds, row sides,
indicator pins and affine objective before normalization. Plans belong to the
actual source object, so a rewrite needs a new plan or the objective-round adapter,
which checks that only the appended bound row changed.

#### Opening components

Search preparation binds each component descriptor to its source and opening
factory, checking unique primary ownership of factors and coordinates first.
Factories retain read-only source fragments; every opening creates separate
mutable components and an atom registry.

`PreparedSourcePlan.capabilities` reduces the components' execution contracts, and
`admission(strategy, operation)` reports participating arms, procedure
completeness, claim authority and explicit unsupported causes. LS and ALNS offer
checked witnesses only. Resource exhaustion belongs to invocations, not admission.

Built-in factor capabilities are declared in `solver/FactorExecutionCapabilities.kt`.
They distinguish unsupported routes, deliberate inertness, filtering strength,
complete checks, heuristic scores and relaxation families, and certify nothing.
Engine-specific caches belong to the projection that built them.

### Factor projection selection

`FactorProjectionProviders.prepare(problem)` captures immutable propagation,
scoring, relaxation and theory encoder descriptors against one canonical `Problem`.
A null descriptor marks an unsupported role and an explicit inert descriptor a
deliberate absence. A rewritten model selects against its own factors.

Direct and equivalent encoders enforce the whole factor; conditioned encoders
require captured source pins; relaxation-only descriptors confer no ownership.
Acceptance is eligibility only: installation and complete-fragment checks still
decide which consumer enforces the source. `factorOwner` names the primary
enforcing path and `factorEncoded` records additional arithmetic participation, so
one continuous relation cannot promote unrelated finite factors. Private
temporaries have one acyclic definition and are stripped before publication.

`PropagationProblem(problem, selection)` and `LocalSearchProblem(problem, selection,
domains)` open mechanisms from that capture, and `problem.bake(providers)` binds
them to the finite projection for one invocation. Missing deduction never
authorizes an assignment: original factors are checked at complete points where a
complete check exists, and execution stays unknown otherwise.

### Search state ownership

Shared search exchanges Boolean literals, integer bounds and typed opaque theory
decisions; traversal and decision levels belong to the shared session.
Source-keyed values (`SearchBoolValue`, `SearchExactIntValue`,
`SearchExactRealValue` and the algebraic and symbolic real forms) let each
component contribute only the coordinates it owns. The open pipeline assembles
them into an exact snapshot; missing or multiply represented coordinates withhold
publication. [Typed contributions](19-api.md#typed-contributions-and-migration)
define the decoding boundary.

### Public strategy construction

Source strategy factories retain the original `Problem` and schema metadata,
consume the shared prepared route and arrange arithmetic completion before
publishing original-coordinate witnesses. See the
[library API](19-api.md#source-aware-strategy-entry-points). Registered arithmetic
collections declare one owner per numeric coordinate; see
[coordinate agreement](09-learning.md#arithmetic-coordinate-agreement).

### Finite execution options

`ExecutionOptions` captures immutable typed settings independently of models,
workers and allowances. Library callers bind `ExecutionOption` keys directly;
frontends parse remaining `key=value` parameters once, the last duplicate winning.
Malformed values keep their diagnostic until a route consumes them. Each plan gets
a fresh consumption view and produces worker factories, and every worker builds
its own mutable selectors. Node allowances, cancellation and lane counts remain
invocation arguments owned by [resources](06-sessions.md#resource-allowances) and
[portfolios](12-portfolio.md).

---

Previous: [1. Introduction](01-introduction.md) · [Contents](README.md) · Next: [3. Input semantics and the schema DSL](03-input-semantics.md)
