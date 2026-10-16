# Model representation and ownership

A source model describes variables, factors and an objective. Engines build their
own views of that model: finite domains for propagation, assignments for local
search, and arithmetic state for theory or relaxation checks. A view does not
replace the source semantics or authorize a result by itself.

The main ownership rule is to keep source data unchanged while a solve uses it,
give each live operation its own mutable state, and publish immutable assignments.
This page defines those lifetimes and configuration boundaries. [Library API](api.md)
defines public access and mappings; [search](solver/search.md) defines traversal.

## Source declarations and factor data

`Problem` stores source integer declarations as bounds and, where necessary,
`SourceIntDomains` value sets. Finite mutable search domains belong to
`BakedProblem` and the propagation session. A source declaration does not assign
ownership to an engine.

Source `Problem` arrays and `Factor` payloads are borrowed read-only. Their producer
keeps them unchanged while any model, ownership plan, lazy operation or proof uses
them. A rewrite creates a fresh source scope; mutating an existing model is outside
the library contract. Custom factors keep their membership and structural keys stable.
Finite propagation and assignment workspaces are mutable and owned by their session.

`Power` retains a fixed nonnegative integer exponent and typed integer or real
source coordinates. Its integer membership does not require enumerating a source
domain. Finite projections filter signs, inverse bounds and bounded hole sets,
check exact assignments and maintain functional outputs. Polynomial encoders retain
one native power expression; linear encoders enforce exponent zero or one, or a
higher integer power under a captured operand fixing whose power fits a signed word.
Real powers supply native heuristic scores and bounded inverse repairs; their
finite deduction is inert. Neither floating scores nor a product relaxation enforce
the source relation.
Generic `Problem` polynomial composition keeps its [finite integer boundary](theories/polynomial-real.md#scope-and-boundaries);
admitted open/wide SMT powers retain their exact nonlinear source snapshots.

## Distinct integer positions

`AllDifferent` retains integer positions, paired optional-presence literals and
excepted source values. Its source identity does not contain a value-window width.
The span-taking compatibility constructor describes that same relation; the window
hint does not restrict its source values or allocate matching storage.

Finite propagation chooses sparse matching over safely enumerable live domains,
bounds filtering over wider domains and fixed-value deductions when endpoint or
interval-capacity arithmetic would overflow. Excepted values may repeat; optional
positions participate only when present. Complete finite assignments retain exact
clash checks. Local search uses sparse counts over full signed-word values. Its
matching seed needs at most one candidate per free position from each domain;
Hall's condition cannot distinguish a larger row. Repair sampling is bounded on
wide domains, while swaps operate over occupied source values.

Unconditional Hall and assignment cuts apply only to plain mandatory distinctness.
Excepted and optional relations retain finite enforcement without those cuts;
overflowing sum cuts decline. Finite globals compose with arithmetic completion
through their finite owner and pinned source coordinates, as described by
[theory composition](theories.md#shared-composition-contract). This path does not
provide a standalone open-integer distinctness encoder.

## Distinct-value counts

`NValue` retains its counted integer positions, target, comparison mode, optional
presence literals and excluded values. Exclusions are a set of source values;
repeated positions and aliases retain their roles. Remapping preserves exclusions,
and structural identity distinguishes them. Fixed-target reductions to equality or
all-different apply only without exclusions or optional positions.

Finite propagation bounds the count of definitely-present non-excluded values and
checks the exact count at a complete assignment. Matching and kernel filters serve
the mandatory form; optional or excluded positions use conservative count bounds.
Wide domains use fixed-value lower bounds and a position-count upper bound without
enumeration. Local search maintains exact non-excluded counts, move deltas and
count-preserving class merges. Its scores do not establish proof authority.
The optional LP value hull keeps selectors for excluded values while omitting those
values from the count sum. This remains a relaxation, with finite propagation owning
the complete relation. Open arithmetic encoders do not claim NValue coverage.

## Integer division relations

`factor.arithmetic.DivMod` retains a dividend, a literal or variable divisor and both
integer outputs. `DivisionConvention.TRUNCATING` rounds the quotient toward zero;
`EUCLIDEAN` requires a nonnegative remainder. Both require a nonzero divisor and
`dividend = divisor * quotient + remainder`, with remainder magnitude below the
divisor magnitude. Exact arithmetic does not wrap at the signed-word boundary.
Finite output domains can therefore make minimum-word division by minus one infeasible.

The internal Euclidean `WideDivMod` form retains exact affine digit views for the
dividend and outputs. Its selected finite projections and encoders install the
complete affine relation; source factors retain those views without allocating open
scalar coordinates for finite wide values. Native scoring evaluates the exact
quotient/remainder and offers bounded repairs through the equivalent identity.

Role fields and structural keys preserve aliases and convention through remapping.
Frontends share quotient/remainder auxiliaries only for identical operands and
convention, retaining equality rows for distinct declared outputs. Source factors
remain intact during consumer selection. Shared equivalent formulas live in
`lowering.DivModEncoding`; the linear encoder handles a literal divisor or explicitly
requires a variable-divisor fixing. Polynomial encoding enforces the entire relation,
including the nonzero and sign cases, with finite integer choices retaining their
source domains. An open variable-divisor relation has no general linear completion;
a polynomial real encoding alone does not decide open integer division. Difference
encoding is unsupported. No product
relaxation supplies division enforcement. Installed formulas retain the original factor
attribution and condition lifetime under the [theory contract](theories.md).

Finite propagation combines signed bounds with support filtering over at most 4,096
operand pairs per call. It checks repeated roles simultaneously; large domains retain
sound bounds and exact pinned checks. [Local search](solver/local-search.md#definitions-and-repairs)
maintains eligible unique outputs and offers correlated repairs. Domain restrictions,
pins, competing definitions and cycles remain authoritative.

## Exact linear source relations

`factor.arithmetic.ExactLinear` captures rational comparisons over tagged integer
and real coordinates, coalescing aliases before exact denominator clearing. Narrow
and arbitrary-width constants retain the same mathematical relation. Strict real
comparisons retain their open boundary; integer-only strict comparisons clear
denominators and use the equivalent integral threshold. An optional Boolean
activator reifies the whole comparison, including its complement. Remapping and
structural keys retain both strictness and activation identity. Finite integer
projections enforce reification in both directions. Mixed scoring is heuristic
and requires a complete source check. The numerical projection emits representable scaled integer
constants without changing the source relation. Missing numerical rows weaken the
relaxation and cannot certify an omitted source comparison. Representable compact
strict/reified rows retain their exact boundary and live Boolean premise in the
numerical projection. Wider strict or reified rows require the selected exact
linear or polynomial consumer; an omitted numerical row supplies no enforcement
authority.

`LinearIndicator` retains one integer trigger, its zero/one polarity and an exact
enabled row. It is a one-way implication; the disabled branch leaves the row
unrestricted. Private selected encoders retain that Boolean structure. Finite
checks, numerical rows and original-source verification read the same trigger.
Source integer declarations fixed at the trigger expose the enabled row to sound
bound closure before final ownership. Live invocation pins remain scoped premises
and retract with their numerical rows.

## Weighted Boolean relations

`PseudoBoolean` and `ReifiedPseudoBoolean` retain exact integral coefficients,
literal polarity, repetitions, comparison and right-hand side. The reified form
retains its indicator and both directions of equivalence. Both expose their same
source relation as a tagged `LinearRow`; exact theory installation consumes it
without adding source integer coordinates.

Native projections use Long arithmetic only when independent activities, signed
flip sums and residual magnitudes fit it. Other rows use exact coalesced Boolean
sums. Complementary literals contribute a constant shift; repeated occurrences
share one signed move coefficient. Exact propagation reasons contain source Boolean
pins and the active indicator premise. Exact local search retains body checks and
move costs without reading a narrow payload. Numeric constants fitting Long alone
do not imply that their activities or residuals fit.

Presolve lifting, clique/knapsack bounds and LP rows or cuts admit only the arithmetic
they can represent. A declined optional projection leaves the source relation and
its finite enforcement intact. The selected exact theory view retains arbitrary-width
constants and reification. [Finite formats](formats/finite.md#opb-and-wbo-lowering)
defines OPB/WBO objective normalization separately from relation width.

## Affine distinctness

`factor.global.Distinct` retains exact affine operands over tagged integer,
Boolean and real coordinates. Operands capture their input buffers, coalesce
repeated coordinates and normalize complemented Boolean terms without changing
their zero/one values. Coefficients and activities retain arbitrary-width rational
arithmetic. An optional Boolean activator is equivalent to the whole relation;
a false activator requires a collision. Remapping preserves repeated operand roles,
strict coordinate namespaces and activation identity.

Finite propagation checks pinned operands and pins the reifying Boolean only when
all operand coordinates are known. Local search grades collisions exactly over
finite integer/Boolean points; real-coordinate scores describe the supplied
binary64 point and require separate exact source checking. Original-coordinate
witness checks compare affine values exactly, including arbitrary-width integers
and rational reals. Distinctness supplies no unconditional numerical relaxation.

## Published assignments and objectives

`Sample`, exact theory assignments and reconstructed witnesses own immutable
coordinates. Public array getters and destructuring return independent buffers;
scalar reads retain exact integer/rational authority without copying arrays.
`Assignment.snapshot()` transfers newly allocated publication buffers once. An
unchanged sample copy can share its private immutable buffers and certificate;
replacement coordinates are captured and require a fresh witness check.

`Sample.symbolicReals` retains exact analytic expressions with rational/algebraic
leaves for transcendental coordinates. Its approximate view supplies heuristic or
display data. Exact source checks retain domain validity and function identities;
replacing the approximate coordinates discards symbolic authority unless replacement
metadata is explicitly supplied. See [exact analytic real coordinates](arithmetic/real.md).

Lowered and solver linear objectives capture their coefficient arrays at construction.
Their getters, copies and destructuring cannot mutate the captured objective. Solver
consumers borrow those private coefficients read-only inside the module. Generic
incumbent extensions supply immutable assignment/objective payloads, or capture
producer state in their verifier before acceptance. Built-in finite and open witnesses
meet that contract without a copy at every incumbent read.

## Quadratic objective representation

`ir.QuadraticObjectiveSpec` captures an exact rational constant and an ordered list
of `QuadraticObjectiveTerm` values over typed Boolean, integer and real source IDs.
`QuadraticCoefficient` owns reduced source numerator/denominator values with a
positive denominator, independently of numerical engine types.
An affine term denotes `coefficient * first`; a product denotes
`coefficient * first * second`. Repeating the coordinate expresses a square.
Cross coefficients are literal source coefficients, without Hessian scaling.
Boolean coordinates denote zero or one; integer values retain arbitrary width and
real values may be algebraic. Objective direction belongs to the invocation.

Affine linear objectives bridge into this representation with unchanged source IDs
and true-Boolean polarity. Long coefficients and constants become exact integers;
Double real coefficients become their exact binary64 rational values. The bridge
does not negate coefficients for maximization or reinterpret binary64 as decimal
text. Exact source decimal/rational quadratic specifications retain their own authority.

Construction and term getters capture independent lists. Remapping preserves each
namespace and rejects removed objective coordinates; elimination requires an
exact source substitution. Internal evaluation uses the existing exact algebraic
arithmetic and returns an owned scalar. Floating scores supply no objective or
proof authority. The independent [certificate kernel](arithmetic/quadratic.md)
checks rational convex subproblems. The quadratic pipeline overload returns exact
source incumbents and direction-aware bounds, with separate attained-optimum,
unattained-limit, unbounded, infeasible and incomplete result types. Opaque
attestations capture source, objective, direction and invocation assumptions;
they retain no mutable arithmetic or cancellation owner. These attestations are
in-memory evidence and do not implement exported proof files.

## Optional completed-result evidence

Completed satisfaction and minimization results can retain `SourceResultEvidence`
when capture is requested through `SourceResultCapture`. Claims name the original
revision, invocation restrictions, objective identity/direction and declared semantics.
Exact coordinates and objective values remain separate from floating telemetry.
Captured engine attestations do not supply independently checked derivation coverage;
missing traversal, transformation or bound data is explicit. [Derivation checking](solver/evidence.md)
owns the consumer-provided original-source contexts and coverage checks.

## Configuration and resources

`ProblemSettings` carries immutable resource metadata through source rewrites,
counting extensions and finite projections. Its domain-storage threshold, pre-bake
span gate and automatic LP size limits do not alter the declared solution set.
Domains retain their `DomainStorageSettings` through narrowing and restoration.
The IR metadata has no dependency on compilation, propagation or solver engines.

Schema compilation and format convenience entry points resolve `KlauseConfig.current`
once, or accept explicit configuration/settings. Compilation uses semantic choices
such as absent optional-value pinning; the model retains only narrow resource
settings. Raw `Problem` and `IntDomain` constructors use built-in defaults and accept
explicit settings. Root propagation, presolve and LP planning read the captured
model settings. Search effort stays in the existing per-call backend parameters.

## Typed source semantics

`ir.SourceSemantics` describes numeric literal authority, float interpretation,
default undeclared ranges, source feasibility tolerance and absent optional-value
canonicalization. Optional policies state applicability: an absent policy cannot
be introduced by a caller override. `SemanticOverrides` replaces applicable policies
before lowering. `BoundsOverride` replaces either endpoint independently, using
`BoundOverride.Open` to explicitly remove a default endpoint. An omitted endpoint
retains its default. Replacements can widen or narrow ranges; they are not
intersections with format defaults. Bounds on declarations with
an explicit range remain source data.

`FloatSemantics.Grid` retains its approximation window separately from undeclared
source-real bounds. Replacing a grid with continuous semantics removes that window,
while retaining an explicitly supplied source range. Numeric literal authority
stays fixed when tolerance or float interpretation changes. Real default bounds
and tolerance components use engine-neutral `SemanticRational` values, retaining
exact rational metadata without a numerical-engine dependency. `SourceTolerance` declares slack as `max(absolute, relative * scale)`. A row's scale is the maximum
of its finite bound magnitude and the sum of absolute term activities; a variable
bound uses its finite bound magnitude. Zero components require exact checking of
the declared numeric source values. Integrality stays exact independently.

`EffectiveSourceSemantics` retains both defaults and effective policy. Its
`isApproximate` flag identifies a grid or positive tolerance; it supplies no
feasibility, completeness or proof authority. `hasOverrides` records changes to the
interpretation, including range replacements. Results referring to an overridden
source must retain the effective policy rather than claim the original defaults.
DIMACS, WCNF and XCSP3 lowered sources retain their exact format declaration and
reject numeric-range, float, tolerance and optional-value overrides, which are
inapplicable to their supported finite source languages. XCSP3 String and streaming
entries use the same policy and resource capture as its declaration builder.
`SourceSemanticsOwner` exposes that immutable capture on source models.
`Problem` retains it separately from `ProblemSettings`; source rewrites, baking,
presolve views and private source-bound projections preserve it. `ProblemBuilder`
accepts the capture before emitting a model. `SemanticSource<M>` pairs other
immutable or borrowed-read-only source payloads with the same contract. The IR
types select no route, theory, engine or resource policy. `KlauseConfig` carries storage and
preparation resources; schema source interpretation is resolved from its declared
semantics and per-compilation `SemanticOverrides`. Global semantic property/env
keys are rejected before resource capture.

Finite enumeration and sampling streams, and accepted witness certificates, retain
the model's semantic capture through `Sample.semantics`. An unbound sample has no
qualification; changing witness coordinates discards both the old certificate and
its qualification. An unchanged
copy preserves them. This metadata adds no checking, claim authority or independent
proof to the existing witness admission contract.

Satisfaction, sampling, optimization and assumption verdicts expose nullable
`semantics` independently of their assignments. A result without an assignment can
retain its interpretation, including an interrupted operation. Binding preserves
the assignment object, counters, exact values, cores, rays and evidence. An imported
point can remain unbound inside a qualified result; a foreign interpretation cannot
silently replace an existing capture. The interpretation does not establish point
validity or promote the verdict's proof authority.

Open and quadratic optimization execution envelopes capture the original prepared
source owner's interpretation, including early interruption and admission declines.
Qualification preserves the native verdict and the exact accepted-incumbent receipt;
the receipt remains separate from a limit or ray certificate's private witness.
An execution over a source without a semantic owner remains unbound.

These value types define the source carrier contract. FlatZinc captures its
[effective declaration policy](formats/flatzinc.md#declared-semantics-and-caller-ranges)
before lowering; other frontends still use their format-specific parameters.
Frontend migration and the shared result-qualification surface remain incomplete.
Implemented publication rules remain in [input semantics](formats.md). Capturing
the value types alone does not establish witness validity or proof authority.

## Engine projections and factor capabilities

The intended [factor retention and composed reasoning](vision.md#high-level-factors-and-composed-reasoning)
contract separates variable ownership from concurrent factor mechanisms.
The preparation and ownership rules below describe the implemented system.

`Problem.prepareExecutionPlan()` selects immutable model-facing ownership and
completion data before search. Shared source routing attempts sound open-bound closure before final
nonlinear admission. A relation's captured linear and polynomial encoder support,
together with its available finite completion, selects the arithmetic language.
A bounded mixed product can retain finite choices and conditioned linear completion;
its factor class alone does not require polynomial traversal. `PreparedSourcePlan` retains the original source
object, canonical execution model, source declaration counts, coordinate mappings,
component descriptors and objective direction. Its internal structural capture selects a
sealed finite/linear, polynomial, nonlinear-integer, transcendental or declined
payload. Each runnable variant carries its required model or completion directly;
consumers dispatch on that variant. Declined assembly can retain a typed finite
projection or a completed polynomial capture beside its explicit unsupported or
resource cause. A stop after polynomial construction preserves that capture and
its coordinate mappings while the decline still governs routing. The public
kind and nullable projection getters remain compatibility views of that capture.
Its descriptors use extensible
semantic identifiers and retain original factor indices. `ComponentPlan` supplies
the finite/linear projection mechanism; `SharedPolynomialModel` supplies polynomial
lowering and exact completion data under the same preparation owner. Polynomial
requests capture this plan once for complete, LS, ALNS and quadratic execution.
Pure polynomial snapshots retain declaration identities separately from private
lowering auxiliaries. Nonlinear integer snapshots retain their own neutral source
representation, with exact declaration-to-numeric coordinate mappings and no
fabricated factor model or finite projection. The shared polynomial assembler explicitly declines open
integer declarations; preparation invents no finite bounds or operation support.
Affine objectives over continuous coordinates retain the exact polynomial optimization route. `prepareConditionedLinearQuery()` selects an
exact linear completion capable of source-coordinate windows and strict cutoffs
once, preserving finite ownership and original identity. Query state and rollback
belong to each worker. Required nonlinear completion declines this hook; an empty
numeric namespace retains its finite/Boolean checker.

`Problem.componentPlan()` remains the finite/linear projection entry point. It
builds a compact remapped CP projection and theory fragments. Plans belong to the
actual source model object. Fresh rewrites need a new plan or the explicit
objective-round adapter, which checks retained factor identity, bound narrowing
and the appended row's unchanged columns and coefficients. Variables owned by
an open theory never become finite CP domains. Continuous coordinates remain
arithmetic-owned and are not integer branch candidates.

Finite/linear search preparation binds each component descriptor to its canonical
source and opening factory. The registration collection checks source identity and
unique primary factor and coordinate ownership before opening any factory.
Additional factor participation can overlap those primary enforcing paths. Factories retain
read-only source fragments and reusable projections; every opening creates separate
mutable components and an atom registry. A registration can supply several peers,
such as shared clauses and cardinalities. Installation preserves CP, Boolean and
arithmetic order; arithmetic route selection belongs to preparation. Specialized
CP and theory accessors remain available on the resulting planned search.

`PreparedSourcePlan.capabilities` reduces the execution contracts captured on its
components. `admission(strategy, operation)` reports participating arms, procedure
completeness, worker claim authority and explicit unsupported causes. Complete
integer traversal may remain nonterminating; nonlinear integer candidate traversal
retains its incomplete guarantee. LS and ALNS offer checked witnesses only.
A mixed strategy exposes its admitted siblings and their individual declines.
Resource exhaustion belongs to invocation results, rather than build-time admission.

`withObjective` captures linear or exact quadratic operation data without reselecting
source ownership. Each binding has its own objective and direction while sharing the
completed structural capture, mappings, provider selections and polynomial finite
projection. Binding does not repeat encoders or retain their construction token.
A rewrite or provider change still requires new structural preparation; immutable
reuse supplies no invocation state, work allowance or evidence authority.
Unsupported objectives do not erase supported satisfaction.
Finite counting and accurate sampling use complete cells independently of the
sampling backend. Their real coordinates remain existential. Native polynomial and
nonlinear requests have no enumeration, count or sampling surface solely because a
finite projection exists. A relaxation supplies no omitted source check or operation.

Built-in factor execution capabilities are declared by
`solver/FactorExecutionCapabilities.kt`, outside `ir`. The declarations distinguish
unsupported routes, deliberate inertness, filtering strength, complete assignment
checks, heuristic scores and sound relaxation families. Engines own their projection
factories and check the declared CP and local-search roles when allocating them.
Capability declarations do not certify a result or select a source theory.

Optional-factor data stores presence literals without reading engine state. The
built-in JVM facades expose those literals without the mixin's state-reading presence
helpers; presence evaluation belongs to the engine projections.
Propagation projections interpret pinned literals conservatively; local-search
projections read complete assignments. Table group no-op caches and MDD transition
indexes belong to a propagation projection, keyed by shared immutable relation
arrays. Local-search projections prepare table column maps, AllDifferent occurrence counts
and circuit scoring. Each engine interprets presence literals in its own state.
Source lowering shares relation data without allocating engine preparation.

## Public strategy construction

Source strategy factories retain the original Problem and captured schema metadata.
They consume the shared prepared route and arrange its required arithmetic completion
before publishing original-coordinate witnesses. Finite preparation retains that same
PreparedSourcePlan. Exact optimization results remain the existing native result types;
source construction does not issue certificates or normalize proof payloads.
[Library API](api.md#source-aware-strategy-entry-points) defines the supported controls
and distinguishes the retained finite facades.

Explicit registered arithmetic collections retain one declared numeric coordinate
owner and guard each participant's contributions against that declaration. Their
agreement participant reads the existing immutable assembly and checks peer source
conditions at those values. See [coordinate agreement](solver/search.md#arithmetic-coordinate-agreement)
for admission, incompleteness and proof scope; typed witness decoding remains the
publication mechanism described below.

## Search state ownership

Shared search exchanges Boolean literals, semantic integer bounds and typed opaque
theory decisions. Components can maintain private residual state through
`SearchBrancher`, but traversal and decision levels belong to the shared session.
`PropagationSession` remains finite-domain state. Source-keyed `SearchBoolValue`,
`SearchExactIntValue`, `SearchExactRealValue`, `SearchExactAlgebraicRealValue` and
`SearchExactRealExpressionValue`
assignments let each component contribute only the values it owns. Legacy untyped
keys remain compatible. The open pipeline assembles an owned exact coordinate snapshot
against its retained source mapping, preserving arbitrary-width integers, rational,
algebraic and symbolic reals. Missing or multiply represented coordinates withhold
publication. [Typed contributions](api.md#typed-contributions-and-migration) define
the decoding and ownership boundary; assembly adds no independent proof authority.

## Factor projection selection

`ArrayMinMax` and integer `Element` retain their native source payloads through
compilation. Their selected linear, difference and polynomial encoders share
equivalent relation construction in `lowering`. An extremum bounds every operand
and equals at least one of them. An element constrains the index range and guards
each selected equality by its exact index value. This linear-size encoding retains
ordered entries, offsets, aliases and full-width integer constants. Installation
uses exact affine interpolation for two-entry constant elements in the linear
and polynomial targets; the integer index range makes that relation complete.
The difference target keeps guarded equalities. Installation
retains source attribution and uses the receiving cancellation/work allowance;
an interrupted relation is discarded. Native finite projections remain available
alongside arithmetic participation.

`RealArrayMinMax` retains ordered continuous operands and a result in the real
namespace. Its implied inequalities form a sound relaxation; complete selected
linear or polynomial encoders also require equality to an operand. Native scores
are heuristic and exact source-coordinate checking remains authoritative. Source
preparation requires complete arithmetic enforcement; the bounds-only relaxation
cannot authorize finite completion.

`GridArrayMinMax` retains per-coordinate grid steps, origins and the component's
scaling policy. Its finite projection filters envelope rows and remaining
operand equalities with exact integer activity arithmetic; finite checking and
selected arithmetic encoders use the same signed coefficient and offset rounding. Lazy propagation explanations
retain the prior bounds for every operand, including the bounds that exclude
other equality alternatives. This relation does not establish exact physical source
arithmetic outside the declared grid policy.

Polynomial source snapshots retain numeric conditionals as neutral selection
relations in their existing formula, preserving the result identity, guard and
ordered expression branches. Remapping and scope validation visit every role.
Arithmetic normalization owns equivalent guarded expansion; exact source checking
reads the retained selected branch. Conditioning builds a private view and does
not replace the captured relation.

`RealElement` retains an integer index and ordered binary64 constants or real-column
entries. Its finite propagation restricts the index range; exact arithmetic checks
certify the selected real equality. A fixed index emits that equality into the LP
leaf. Its complete linear and polynomial encoders preserve the same index and
continuous coordinates, including repeated entries and result aliases. Real local
search scores and repairs are heuristic and require exact source certification.

`FactorProjectionProviders.prepare(problem)` captures immutable selected propagation,
scoring, relaxation and theory encoder descriptors against one canonical `Problem`. The descriptors
couple declared capabilities to their factories or emitters; null selects an unsupported
role, while an explicit inert descriptor records deliberate absence. Source factors
remain engine-neutral and borrowed read-only. `forProblem` retains the capture only
for the identical model; a rewritten or remapped model selects against its own factors.

Encoder selection names the consumer language separately from numerical execution.
Direct and equivalent encoders enforce the whole factor; conditioned encoders require
captured source pins. Relaxation-only and unsupported descriptors confer no source
ownership. Finite/linear planning reads those selected relations for fragment admission and
shared component installation. Variable ownership is separate: finite declarations
and their choices remain in finite state, while open integers and reals remain
arithmetic-owned. `factorOwner` names the primary enforcing path; `factorEncoded`
records additional arithmetic participation, including factors with propagators.
Only relations needed by the arithmetic component are installed. An exact coefficient
or one continuous relation cannot promote unrelated finite factors or choices. The prepared linear relations
retain original factor attribution and typed coordinates. A compound installation can
produce several conjunctions and disjunctions attributed to the same source factor.
Private affine temporaries and Boolean formula names have exactly one acyclic definition
in their receiving scope; exact expansion removes them before solving and retains their
reconstruction definitions in the attribution. Private existential real coordinates have their own allocation and attribution.
Their assertions, disjunction decisions and disequality splits remain inside the exact
component. Publication strips them, and source-coordinate agreement never names them.
Source declaration counts remain unchanged; component openings own their
combined graph, assertion scopes, LP state and resources. Conditioned finite completion
captures the fixed discrete point without changing source declarations. Its pins do
not authorize a refutation outside that scope.

Polynomial preparation installs required relations and connected, completely encodable
finite relations through the selected encoders into one combined formula. A finite
relation without a propagator requires a complete encoder; relaxation-only support
cannot fill that gap. Optional arithmetic participation retains its finite propagator
and finite coordinate owner. Unrelated finite coordinates receive no polynomial embedding or reserved numeric
slot. Required encoder-only finite relations can select the polynomial route from
capabilities even without a built-in product factor. A mandatory installed polynomial
peer can complete source checking for an opaque finite filter; using that finite
projection alone retains its original checking obligation. Integer embeddings and private Boolean-choice
auxiliaries retain typed source correspondence and factor attribution. Their defining
guards are conjoined with the relation; source publication excludes private auxiliaries.
Generic linear-to-polynomial equivalence belongs to `lowering`. Exact source checks
remain separate from a numerical execution view and from relaxation-provider emission.


`PropagationProblem(problem, selection)` and `LocalSearchProblem(problem, selection,
domains)` open mechanisms from that capture. Factories allocate private mutable
mechanisms or stateless projections whose payloads belong to engine states. Scoring
uses the actual move domains when choosing exact wide-linear sums. Built-in selectors
retain their existing algorithms, relation indexes and linear-row caches.

`problem.bake(providers)` binds these selectors to the finite engine projection for
one invocation. Root propagation, later assumption checks, appended objective
overlays, conditioned roots, local scoring and model-facing LP emission retain that
selection. The neutral `Problem` has no engine provider field. Custom scoring
mechanisms are prepared separately for portfolio workers; only built-in stateless
scoring projections enter the shared preparation pool.

Absent or partial selected deduction does not authorize arbitrary assignments.
Original finite factors are checked at complete points when a built-in complete
check exists; otherwise execution remains unknown. Real completion uses original
source rows independently of a selected bounding relaxation, and published exact
coordinates must satisfy their source relation. These checks create private state
and retain the invocation's existing work currencies and cancellation lifetime.

Coordinate projection retains the selected descriptors and binds their factories
to remapped immutable factor data. Each factory or emitter receives that engine-view
factor, so source variable ids cannot leak into compact CP storage. Source rewrites
select fresh descriptors; objective overlays retain prior selections and select only
the appended factor. `bake(selection)` keeps the captured choice through finite
root construction instead of invoking selectors again.

`prepareExecutionPlan(providers, cancellation = ..., ...)` captures immutable projection descriptors on
its canonical model. Component ownership and admission read that capture, including
shared clauses whose heuristic score belongs to the finite invocation. Complete
traversal requires an exact propagation or installed source check; scoring availability
is a separate heuristic requirement. Inert finite deduction can use the original exact
point checker, while missing checks remain declined. Objective and conditioned plans
retain the capture. Compact CP and polynomial finite views bind those descriptors to
their own factor coordinates; source factor rewrites select against rewritten data.

## Absolute-value relations

`factor.arithmetic.Abs` retains the exact integer relation `result = |operand|`.
Schema expressions, FlatZinc `int_abs`, representable SMT-LIB integer terms and
XCSP3 `abs`/`dist` expressions produce this factor. Coordinate remapping retains
both roles even when they alias. Signed-word finite execution excludes
`Long.MIN_VALUE` operands because their magnitude has no representable result.

Selected linear consumers receive nonnegativity and the disjunction of positive
and negative equalities. Polynomial consumers receive the equivalent formula.
The separately declared inequalities are a relaxation and cannot enforce the
source relation. Finite propagation keeps sign-aware bounds and filters holes
when each visited domain has at most 1024 values. Larger domains retain bounds
filtering and exact complete-point checks. Local scoring checks the full relation,
repairs either role and maintains acyclic outputs through the definition network.

Wide SMT absolute values retain `AffineAbs` over exact integer affine operand and
result payloads. Finite wide quantities keep their digit columns and source ranges;
open quantities keep symbolic integer coordinates. Native finite consumers evaluate
and filter the full relation with arbitrary-precision arithmetic. Selected arithmetic
consumers install the nonnegative equality alternatives, with the same original factor
attribution. Rational source checks evaluate the affine magnitudes exactly, including
coordinates beyond the signed-word range. The implied inequalities remain a relaxation.

`RealAbs` retains exact absolute value over source real coordinates. Exact FlatZinc
`float_abs` produces this factor; its selected linear and polynomial consumers
install the complete nonnegative equality alternatives. Its implied inequalities
remain a relaxation. Finite propagation is inert for real coordinates, and floating
local scores and repairs require exact source completion before publication.
Rational source witness verification compares the operand magnitude and result exactly.
Finite satisfaction and objective-free local-search completion fix the discrete
coordinates and install the selected complete linear relations into a private exact
search component. This component shares the containing cancellation and native work
accounting; its guard choices and real coordinates stay within that completion.
A residual LP relaxation alone cannot certify the source relation.
Default bucketed `float_abs` retains `GridAbs` with its immutable bucket metadata
and scalar image. Selected finite and LP consumers build private tuple projections;
selected arithmetic consumers install a valid-input cover and guarded image
relations. The finite relation preserves the existing nearest-bucket policy.
Original float witness checks and grid result qualifications remain required.

## Finite real product choices

`ContinuousProduct` retains an exact real product and may also capture a declared
finite operand domain with source Boolean literals equivalent to each operand
value. The immutable metadata uses the source real and Boolean namespaces;
remapping preserves aliases and literal polarity. Frontends retain the finite
domain cover, while product expansion belongs to selected consumers.

The selected linear encoder installs an exact value cover, guarded product rows
and complete guard equivalences. The polynomial encoder enforces the same domain,
guards and product equality. Encodings charge the containing work/cancellation
allowance, and each invocation owns its rows. A guarded LP projection supplies
sound rows under live source literal premises; finite completion is admitted only
when every required aliased coefficient is exactly representable. Exact original
witness checks include the product, finite domain and guard equivalences. Rays
also keep the finite operand fixed and verify the product polynomial along the
whole direction. Unguarded or unrestricted products receive no LP completion
authority from numerical scoring.

## Grid arithmetic relations

Internal `GridProduct` and `GridPower` retain an operation and each integer index's
finite binary64 range and bucket count. Source fields preserve repeated operand
and result roles through remapping. The declared nearest-output-bucket relation is
an approximation of continuous arithmetic; its exact finite membership does not
establish a continuous-source witness or proof. Frontend component provenance and
original-source checks retain that distinction. Native projections do not allocate
frontend tuple tables. Each filtering pass handles at most 4096 input combinations;
private linear/polynomial encodings admit at most 4096 combinations and charge the
containing cancellation/work allowance. Larger encoding requests decline while
native finite filtering and scoring remain available.

## Finite execution options

`ExecutionOptions` captures immutable typed settings independently of source models,
workers and invocation allowances. Library callers bind `ExecutionOption` keys to
values directly. Axis settings capture their input lists. Frontends parse remaining
finite `key=value` parameters once after consuming invocation controls. Duplicate
keys retain the last value and the original key ordering for usage diagnostics.
Malformed values retain their diagnostic until the selected route consumes them;
annotation and dry-run rules retain their existing precedence.

Finite requests and planning carry the same captured options. Resolution owns a
fresh consumption view per plan, checks model-dependent catalog and annotation
restrictions, and produces worker factories. Every worker constructs its own mutable
selectors and strategy state; options never contain those instances. Node allowances,
cancellation and lane counts remain invocation arguments, shared or partitioned by
the existing [resource](solver/resources.md) and [portfolio](solver/portfolio.md) owners.
