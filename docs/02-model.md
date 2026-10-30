# 2. Model representation and ownership

A source model is an engine-neutral set of declarations, factors and an objective.
Preparation builds finite, heuristic and arithmetic views of it. Source data stays
unchanged while a solve uses it; live operations own mutable state and publish
immutable assignments. A view alone authorizes no result.

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

Completed satisfaction and minimization results can retain optional
`SourceResultEvidence`. It binds exact claims to the original revision, restrictions,
objective and semantics; [derivation checking](13-evidence.md) owns proof coverage.

## Source relations

Factors retain source relations through remapping. Propagation, scoring and
arithmetic consumers build separate projections; a relaxation never enforces a
factor. The relations below describe their semantic distinctions. Consumer mechanics
belong to [propagation](08-search.md#finite-propagation),
[local search](10-local-search.md) and [theory encoding](14-theories.md).

### Source definition hints

`ir.DefinitionHint` records an integer or Boolean output orientation and its source
relation data, with operand order, constants, multiplicity, strictness and
polarity. Reference hints retain annotations whose functional form is unavailable.
Source factors remain the authority for all constraints. `DefinitionHint.remap`
renumbers every role; a removed required coordinate declines the hint rather than
inventing a value, unless [presolve](07-presolve.md#guarantees-and-source-mappings)
rebuilt it exactly from retained columns. [FlatZinc](04-finite-formats.md#source-definitions)
and [XCSP3](04-finite-formats.md#expressions-and-selection) produce these records, and
[local search](10-local-search.md#source-definition-materialization) materializes its
private view only when requested.

### Distinct integer positions

`AllDifferent` retains integer positions, optional-presence literals and excepted
values. Excepted values may repeat; optional positions participate only when present.
Finite propagation uses matching, bounds or fixed-value deductions according to the
domain representation. Linear and polynomial encoders preserve all three forms.
Hall and assignment cuts require plain mandatory distinctness.

`AllDifferentTuples` retains equal-width ordered rows, including repeated integer
coordinates. Rows must differ in at least one position; values may repeat within a
row. Its representation and engine preparation use space proportional to the source
coordinates, without tuple-pair or domain-product enumeration. Finite propagation
rejects identical variable rows and hashes fully fixed rows, explaining a clash
from the two rows' bounds. This gives an exact assignment check without partial-row
filtering. Local search maintains tuple multiplicities and grades the excess above
one per tuple. A move updates every row reading its coordinate once; zero-count
entries are removed, so state does not retain the walk's history. Full tuple values
resolve hash collisions. Factor preparation and propagation visits remain atomic
linear scans between the engine's cancellation polls.

### Distinct-value counts

`NValue` retains counted positions, target, comparison mode, optional presence and
excluded values. For mandatory positions without exclusions, an exact fixed target
of one reduces to equality and a target at the list arity to `AllDifferent`,
independently of domain span. Finite consumers maintain exact counts; the LP value
hull is a relaxation, and open arithmetic encoders claim no NValue coverage.

### Integer division relations

`DivMod` retains a dividend, literal or variable divisor, quotient and remainder.
`TRUNCATING` rounds the quotient toward zero and gives a nonzero remainder the
dividend's sign; `EUCLIDEAN` requires a nonnegative remainder. Both require a
nonzero divisor, the exact equation
`dividend = divisor * quotient + remainder`, and remainder magnitude smaller than
the divisor's. `WideDivMod` keeps exact affine digit views beyond signed-word range.

Frontends share outputs only for identical operands and convention. Schema,
FlatZinc and XCSP3 compilation use a private `lowering.DivModRelationIndex`, keyed
by dividend, divisor coordinate or constant, and convention. Additional outputs get
equality aliases; the index is discarded before solving.
`lowering.DivModEncoding` owns the formulas: linear encoding requires a literal or
fixed divisor, polynomial encoding retains every sign case, and difference encoding
is unsupported. [Local search](10-local-search.md#definitions-and-repairs) maintains
eligible outputs and correlated repairs.

### Absolute-value relations

`Abs` retains `result = |operand|`. Linear encoding requires nonnegativity and the
disjunction of positive and negative equalities; polynomial encoding retains the
same formula. `AffineAbs` supports wide exact affine operands, `RealAbs` exact
real coordinates, and `GridAbs` the declared bucket relation. Aliases and remapping
preserve operand and result roles.

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

`factor.arithmetic.Linear` canonicalizes integral greater-equal rows to less-equal
by exact negation. A bound or coalesced coefficient at `Long.MIN_VALUE` promotes
the constants to arbitrary precision before negation; representable negations
retain word-sized constants. Canonical counterparts share a structural key.

`factor.arithmetic.ExactLinear` captures rational comparisons over integer and real
coordinates, coalescing aliases before exact denominator clearing. Strict real
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
Unguarded unit integer operands `x + c` use original-coordinate matching/Hall
filtering with exact integer offsets and historical source bound/hole reasons.
Other finite operands and reified relations retain pinned checking. Local search grades collisions exactly
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

`FloatSemantics.Grid` permits a private finite or heuristic projection without
selecting one. A requesting consumer binds that view to its prepared invocation;
[FlatZinc](04-finite-formats.md#flatzinc-floats) defines its automatic fallback.
Original declarations, relations and objective remain on the attachment, and private
helpers stay distinct. Unsupported factors or stops decline only the view.

Publication reconstructs engine coordinates into the private grid, then into
original real coordinates. Source-qualified consumers check the original model;
FlatZinc consumers publish under their declared grid semantics instead. Grid
exhaustion and optimization prove only grid claims, kept in
`FiniteSolveResult.nativeGridOutcome`. FlatZinc also reports these through
`outcome`, with `SourceFloatProjection.declaredGridSemantics` recording the scope.
See [source checking](07-presolve.md#source-witness-validation) and
[reconstruction](07-presolve.md#guarantees-and-source-mappings).

## Engine projections and factor capabilities

Preparation selects the primary owner of every coordinate and factor before opening
engine state. Participation can overlap; primary ownership cannot.
`SourceAttachment` retains the original model and semantics;
[source setup](19-api.md#source-setup) binds the operation and prepares execution.
Interrupted preparation exports no partial plan.

### The prepared plan

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

### Opening components

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
deliberate absence. A rewritten model selects against its own factors; a certified
deletion may retain built-in descriptors through the checked
[retained-factor mapping](07-presolve.md#source-routing-and-encoder-selection).

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

Shared search exchanges Boolean literals, exact integer bounds and typed theory
decisions. Each component contributes only its owned source coordinates; missing or
multiply represented coordinates withhold publication.
[Typed contributions](19-api.md#typed-contributions) define the assembly boundary,
and [coordinate agreement](09-learning.md#arithmetic-coordinate-agreement) defines
checks between registered arithmetic peers.

### Finite execution options

`ExecutionOptions` captures immutable typed settings independently of models,
workers and allowances. Library callers bind `ExecutionOption` keys directly;
frontends parse remaining `key=value` parameters once, the last duplicate winning.
Malformed values keep their diagnostic until a route consumes them. Each plan gets
a fresh consumption view and produces worker factories, and every worker builds
its own mutable selectors. Node allowances, cancellation and lane counts remain
invocation arguments owned by [resources](06-sessions.md#resource-allowances) and
[portfolios](12-portfolio.md).

When preparation selects a finite route, `open-linear-incremental` stays at the
source boundary and is omitted from finite engine settings.

---

Previous: [1. Introduction](01-introduction.md) · [Contents](README.md) · Next: [3. Input semantics and the schema DSL](03-input-semantics.md)
