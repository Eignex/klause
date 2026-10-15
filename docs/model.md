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
Neither floating scores nor a product relaxation enforce the source relation.
Generic `Problem` polynomial composition keeps its [finite integer boundary](theories/polynomial-real.md#scope-and-boundaries);
admitted open/wide SMT powers retain their exact nonlinear source snapshots.

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

## Engine projections and factor capabilities

The intended [factor retention and composed reasoning](vision.md#high-level-factors-and-composed-reasoning)
contract separates variable ownership from concurrent factor mechanisms.
The preparation and ownership rules below describe the implemented system.

`Problem.prepareExecutionPlan()` selects immutable model-facing ownership and
completion data before search. `PreparedSourcePlan` retains the original source
object, canonical execution model, source declaration counts, coordinate mappings,
component descriptors and objective direction. Its descriptors use extensible
semantic identifiers and retain original factor indices. `ComponentPlan` supplies
the finite/linear projection mechanism; `SharedPolynomialModel` supplies polynomial
lowering and exact completion data under the same preparation owner. Polynomial
requests capture this plan once for complete, LS, ALNS and quadratic execution.
Pure polynomial snapshots retain declaration identities separately from private
lowering auxiliaries. Nonlinear integer snapshots retain their own neutral source
representation, with exact declaration-to-numeric coordinate mappings and no
fabricated factor model or finite projection. The shared polynomial assembler explicitly declines open
integer declarations and continuous linear objectives; preparation invents no
finite bounds or operation support. `prepareConditionedLinearQuery()` selects an
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
source ownership. Unsupported objectives do not erase supported satisfaction.
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
retains source attribution and uses the receiving cancellation/work allowance;
an interrupted relation is discarded. Native finite projections remain available
alongside arithmetic participation.

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
