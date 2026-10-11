# System architecture

## Solver identity and support policy

Klause's required model surface is shared across search, local search,
large-neighbourhood search and portfolios. Every supported problem must have both
a runnable complete-search arm and a runnable local-search arm, and callers must
always be able to compose the two into a portfolio over that problem. Both arms
retain the same source constraints, variable meanings and objective. This applies
to finite, open, continuous and mixed models, including pure theory problems.
Single-arm execution remains an explicit caller choice.

Strategies can use native projections or composed theory services. Each arm must
execute meaningful search over the admitted model: dropping constraints, rejecting
the model or immediately returning unsupported UNKNOWN is a coverage gap, not
engine parity. Shared breadth does not require equal filtering, scoring,
performance or proof authority. The implemented exceptions below are work to close;
they do not define permanent exclusions from the portfolio requirement.

The complete-search arm is the portfolio's source of certified refutations, bounds
and optimum claims. That role is distinct from mathematical completeness: an
incomplete arithmetic procedure can produce sound proofs for individual instances,
but no algorithm can guarantee a terminating exact decision for every input in an
undecidable family. Any guarantee of eventual decision must name the fragment and
procedure. Local search contributes checked witnesses and incumbents; unsuccessful
local search is never a refutation.

Support requires defined source semantics and sound result publication.
Mathematical decidability is not an admission requirement. An incomplete procedure
can publish a checked witness or a completed sound refutation; failure to find a
witness does not establish infeasibility. Completeness belongs to a procedure and
its documented fragment, not to a factor, input format or engine name. Even a
complete procedure can stop without a verdict under time, work or representation
limits. Unsupported syntax or execution remains distinct from resource exhaustion.

| Claim | Required authority |
|---|---|
| SAT or feasible incumbent | A complete assignment checked against the source under its declared semantics |
| UNSAT or infeasible | A completed sound refutation covering the source scope |
| Optimal | A checked feasible assignment and a matching bound or exhaustive proof establishing an attained optimum |
| Unbounded | A source-valid proof of unlimited objective improvement; a feasible point alone is insufficient |
| Best found | A checked feasible incumbent without an optimum proof |
| UNKNOWN | No definitive claim; retain the unsupported, cancellation or resource cause |

Exact publication cannot be justified by a zero heuristic score, a relaxation
candidate or satisfaction of an approximated model. Approximation and configured
tolerance have the separate source contracts in [input semantics](formats.md).
The [portfolio contract](solver/portfolio.md#witnesses-and-proof-scope) owns witness
and proof admission; [resource allowances](solver/resources.md) own interruption.

Finite propagation owns Boolean and finite integer domains only. Real values and
open integer state belong to arithmetic components. Shared search owns traversal,
decisions, retraction and model assembly across those owners. Continuous support
does not place real domains in CP. See [shared component search](solver/search.md#shared-component-search).

### Independently checkable proof files

The vision includes optional proof files that let a separate checker verify
Klause's published claims without trusting the search implementation. The target
is proof coverage across the supported model surface: feasible assignments,
refutations, objective bounds, attained optima and unboundedness evidence under
the declared source semantics. Proof production does not require a terminating
decision procedure for every admitted family. A timed-out run can retain a checked
incumbent and a certified bound without claiming optimality or infeasibility.

Proofs must connect back to the original source model and invocation assumptions.
Lowering, presolve, reconstruction, propagation, learned clauses and imported
bounds must have checkable justifications wherever they contribute to the claim.
Checking an exported encoding alone leaves its translation trusted; that trust
boundary must be explicit. Exact and tolerance-based claims remain distinct.

Proof production composes with the required complete-search/local-search
portfolio. Local search supplies checkable solutions; the proof-producing arm
supplies justified deductions, bounds and refutations. Heuristic moves need not be
logged. Shared incumbents and deductions used in a proof must retain their
justification and source scope regardless of which worker produced them.

One user-facing verification contract need not imply one proof format. Finite
Boolean/integer models can use pseudo-Boolean encodings and deduction proofs;
linear arithmetic needs exact numerical certificates and justified search steps;
polynomial real arithmetic needs checkable algebraic evidence and exhaustive case
coverage. Proof export must report unsupported coverage or incomplete evidence
explicitly rather than claim independent verification.

This is a target capability, not implemented proof-file support. The current
[portfolio certificates](solver/portfolio.md#witnesses-and-proof-scope) attest to
engine checks in memory, and [LP certification](lp/certification.md) performs exact
numerical checks internally. Neither provides an independently checkable proof of
the entire source solve.

### Implemented arithmetic routes

This table is a coverage snapshot, not a permanent limit on Klause's model surface.
Shared polynomial models use exact conditioned completion beside finite propagation.
Continuous products, finite globals and finite objectives compose through the
[polynomial service](solver/qfnra.md#shared-model-composition). Coverage across all
model routes is tracked in [#2624](https://github.com/Eignex/klause/issues/2624).

| Source model or factor | Solving mechanism | Coverage boundary |
|---|---|---|
| Boolean and finite integer factors | Finite CP backtracking with Boolean learning; local-search projections and portfolio strategies | Complete finite proofs require completed checks and traversal |
| Finite integer `Product` | Integer propagation and finite search | Int × Int → Int; nonlinear SMT admission is tracked in [#2625](https://github.com/Eignex/klause/issues/2625) |
| Linear open integer or mixed integer/real arithmetic | Shared search with exact linear arithmetic and integer branching; graph reasoning for difference fragments | Open arithmetic state remains theory-owned |
| Linear real arithmetic | Exact LRA theory or certified LP completion within a mixed model | Source strictness, Boolean structure and bounds remain authoritative |
| Finite integer `RealProduct` | Finite search with LP relaxations and exact continuous completion | Int × Real → Real; fixing the integer operand makes the product linear |
| Pure Real/Bool polynomial satisfaction | Conditioned exact CAD service with backtracking, local search, ALNS and mixed portfolios | Heuristic arms publish exact witnesses; only complete traversal supplies refutations |
| Real × Real products in `Problem` | `ContinuousProduct` with shared finite strategies and exact polynomial completion | Finite integer domains and finite objectives are supported; continuous objective optimization remains unsupported |
| Continuous quadratic objectives | No built-in certified quadratic optimization route | Tracked in [#2626](https://github.com/Eignex/klause/issues/2626); finite integer product objectives and linear routes already exist |
| `exp`, `log`, `sin` and other transcendental constraints | No built-in source/theory route | Tracked in [#2627](https://github.com/Eignex/klause/issues/2627); polynomial theory does not assign these functions semantics |

[Factor capabilities](../klause/src/commonMain/kotlin/com/eignex/klause/solver/FactorExecutionCapabilities.kt),
[input semantics](formats.md) and [polynomial theory](solver/qfnra.md) define these
boundaries. A relaxation is a deduction mechanism; it does not add exact support
for an omitted source constraint.

## Repository modules

| Location | Responsibility |
|---|---|
| `klause` | Kotlin Multiplatform model, DSL, compilation, formats and solving |
| `klause-cli` | Shared command-line driver, format dispatch, output protocols and platform seams |
| `klause-bench` | JVM catalog, corpus management, campaign runner and offline measurement tools |
| `klause-mzn-lib` | MiniZinc solver configurations, FlatZinc wrappers, predicate declarations and smoke models |

The Gradle settings include `:klause`, `:klause-cli` and `:klause-bench`.
`klause-mzn-lib` is integration data and scripts, rather than a Gradle module.

## Package boundaries

Packages below are under `com.eignex.klause` in
[commonMain](../klause/src/commonMain/kotlin/com/eignex/klause/).

| Package | Owns | Dependency rule |
|---|---|---|
| `ir` | Literals, variable descriptions, remapping, structural keys, polynomial source expressions/formulas and `Problem` | Engine-neutral; no engines, compilation or input formats |
| `model`, `schema`, `compile` | Model expressions, schema DSL and compilation | Use shared IR and lowering |
| `lowering` | Generic Boolean and constraint encodings | Independent of schema compiler and format callers |
| `formats` | Parsing and lowering source syntax | Uses shared lowering; no compiler or concrete theory dependency |
| `factor` | Constraint semantics and factor operations | Supplies mechanisms used by propagation, relaxations and search |
| `propagation` | Finite domains, explanations and incremental propagators | May use immutable arithmetic representations |
| `arithmetic.polynomial` | Exact sparse polynomials, elimination determinants and rational real-root isolation | Only `arithmetic.polynomial`, `simplex.exact` and `util`; no model or solver state |
| `arithmetic.difference` | Difference constraints, extraction and non-incremental graph operations | No propagation or search state |
| `simplex.exact` | Rational arithmetic and exact feasibility primitives | Reusable numerical mechanism |
| `simplex.basis` | Sparse floating and rational basis operations | Numerical types, `util` and koblas; no search policy |
| `lp.lattice` | Exact integer echelon/Hermite reduction, transforms and triangular bounds | Leaf depending only on `util` |
| `lp.engine` | LP solving, basis state and exact certificates | Only `util`, `simplex.exact`, `lp.lattice` and `simplex.basis` among Klause packages |
| Remaining `lp` packages | Factor relaxations, source reasoning, cuts and bounding policy | Depend on the engine; no reverse dependency |
| `bound` | Combinatorial bounds over global constraints | Factors, IR, objectives, propagation and relaxation views; no `lp.engine` |
| `backtrack` | Finite CP search policy and DFS integration | Uses propagation and shared search |
| `localsearch`, `portfolio` | Local-search policy and engine orchestration | Select and schedule solver mechanisms |
| `meta` | Adaptive large-neighbourhood search and core-guided optimization | Composes solver mechanisms |
| `count` | Exact/approximate counting and near-uniform sampling | Uses compiled finite models and bounded solver cells |
| `theory.difference`, `theory.lia`, `theory.qflra` | Open arithmetic fragments | Must not materialize finite CP domains |
| `theory.qfnra` | Complete polynomial-real decision with exact CAD and Boolean search | Reads neutral polynomial formulas and the exact kernel; no finite domains or linear-engine state |
| `solver.pipeline` | Source routing and one verdict surface | Selects theory once; frontends render assignments |
| `solver.search` | Component protocol, decisions, traversal, retraction, learning and complete checks | Owns shared lifecycle across CP and theory components |

Dependencies point from policy to mechanism. In particular, enlarging the engine's
closed dependency list couples reusable numerical work to model policy.
[checkArchitecture](../scripts/check_architecture.py) checks production sources in every
platform source set without compiling Kotlin. It enforces the closed numerical and
IR lists, caller-independent lowering, frontend/compiler/theory separation, the
pipeline's neutral metadata and the prohibition on finite-domain state in open
theories. Imports, aliases, wildcard imports and qualified executable references
are checked; comments and literal text are excluded. Compiler resolution and
consumer behavior remain the responsibility of the JVM/native gates.
[LpDeclineDisciplineTest](../klause/src/jvmTest/kotlin/com/eignex/klause/lp/LpDeclineDisciplineTest.kt)
checks proof-decline behavior at engine consumers.

The exact LP projection of `QfLraRelaxation` belongs to the theory adapter, beside
its source declarations. SMT syntax folding does not read theory or search state.
`formats.smtlib.SmtLibAdmission` owns linear-first parsing, lexical polynomial helper admission and
polynomial fallback.
Its opaque `ir.PolynomialRealProblem` retains neutral formula/declaration metadata.
`solver.pipeline` selects and invokes the complete polynomial theory from that
snapshot, and publishes the shared verdict and original exact coordinates. It has
no dependency on formats; frontend rendering consumes typed algebraic values.

## Numerical package ownership

Exact integers, rational values, cancellation and work-meter contracts are compiled
in `klause`. The `lp.lattice` package owns integer reductions and triangular bounds
with internal input/result interfaces; it reads only neutral `util` primitives.
Package dependency checks enforce its independence from model and solver state.
Numerical tests run with the solver's JVM and Native tests.

`arithmetic.polynomial` owns sparse exact polynomials, elimination determinants,
rational and algebraic real roots, and specialization over earlier irrational
coordinates. Its public `AlgebraicReal` metadata owns a rational defining polynomial,
distinct ordered root index and exact isolating bounds. Algorithms remain internal,
with caller-owned cancellation/work accounting and explicit allocation/representation
declines. [Exact polynomial arithmetic](arithmetic/polynomial.md) defines these
contracts and the configurable resource limits.

The [library API contract](api.md) classifies supported, experimental and implementation
surfaces and defines typed mapping and migration boundaries.

The [polynomial real theory](solver/qfnra.md) decides neutral polynomial-real formulas
with complete Collins projection, exact lifting and correlated Boolean search.
It preserves declared dimensions and owns traversal, source-atom truth, complete
witness capture and verdict publication. Mathematical arithmetic remains in the
kernel. One invocation scope covers normalization through publication.

## Model and ownership

`Problem` stores source integer declarations as bounds and, where necessary,
`SourceIntDomains` value sets. Finite mutable search domains belong to
`BakedProblem` and the propagation session. A source declaration does not assign
ownership to an engine.

Source `Problem` arrays and `Factor` payloads are borrowed read-only. Their producer
keeps them unchanged while any model, ownership plan, lazy operation or proof uses
them. A rewrite creates a fresh source scope; mutating an existing model is outside
the library contract. Custom factors keep their membership and structural keys stable.
Finite propagation and assignment workspaces are mutable and owned by their session.

`Sample`, exact theory assignments and reconstructed witnesses own immutable
coordinates. Public array getters and destructuring return independent buffers;
scalar reads retain exact integer/rational authority without copying arrays.
`Assignment.snapshot()` transfers newly allocated publication buffers once. An
unchanged sample copy can share its private immutable buffers and certificate;
replacement coordinates are captured and require a fresh witness check.

Lowered and solver linear objectives capture their coefficient arrays at construction.
Their getters, copies and destructuring cannot mutate the captured objective. Solver
consumers borrow those private coefficients read-only inside the module. Generic
incumbent extensions supply immutable assignment/objective payloads, or capture
producer state in their verifier before acceptance. Built-in finite and open witnesses
meet that contract without a copy at every incumbent read.

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

`Problem.componentPlan()` selects an immutable ownership plan before search. It
builds a compact remapped CP projection and theory fragments. Plans belong to the
actual source model object. Fresh rewrites need a new plan or the explicit
objective-round adapter, which checks retained factor identity, bound narrowing
and the appended row's unchanged columns and coefficients. Variables owned by
an open theory never become finite CP domains. Continuous variables are LP columns
and are not integer branch candidates.

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

Shared search exchanges Boolean literals, semantic integer bounds and typed opaque
theory decisions. Components can maintain private residual state through
`SearchBrancher`, but traversal and decision levels belong to the shared session.
`PropagationSession` remains finite-domain state. Source-keyed `SearchBoolValue`,
`SearchIntValue` and `SearchRealValue` assignments let each component contribute
only the values it owns.

Frontend-owned execution adapters translate search annotations into `ir.SearchHints`
and retain source rendering and protocol behavior. Float representation provenance
is shared lowering data; source planners choose each connected component
independently. See [input semantics](formats.md#execution-metadata-and-schema-floats).

Frontends call the [pipeline](../klause/src/commonMain/kotlin/com/eignex/klause/solver/pipeline/)
and render its result. They do not invoke concrete theories directly. Open routing
can prove missing bounds before selecting the finite lane; it retains the source
semantics if that proof declines. See [input semantics](formats.md).

SMT-LIB and MPS can use an open local-search route when the selected
theory cannot decide an objective or fragment. That route searches for witnesses
without turning failure into refutation. Exact FlatZinc declines an unsupported
open fragment. Route-specific guarantees belong to the shared pipeline and source
publication checks.

## Solving

Finite backtracking combines Boolean learning, explainable CP propagators,
combinatorial global bounds and optional LP relaxations. A Boolean-only path
handles SAT and pseudo-Boolean structure. Local search exposes composable move,
score and acceptance policies. Portfolio engines select arms based on model
structure, objectives and requested search settings.

Problem-level presolve and inprocessing are shared mechanisms. LP adapters
assemble relaxations and reconstruct source results; the engine does not implement
a second problem presolver. Source maps, fixed substitutions, scoped premises and
objective units must survive preparation and reconstruction.

[Search and propagation](solver/search.md) defines shared traversal and reasons.
[Sessions](solver/sessions.md) define caller scopes and stream ownership;
[local search](solver/local-search.md) defines assignment state and moves;
[portfolios](solver/portfolio.md) define scheduling and result admission.
[Presolve](solver/presolve.md) defines source reconstruction and phase accounting.
[LP architecture](lp/architecture.md) defines numerical ownership and consumers.
