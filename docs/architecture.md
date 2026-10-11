# System architecture

## Repository modules

| Location | Responsibility |
|---|---|
| `klause-numerics` | Exact big integers and rational values, cancellation and work-meter contracts |
| `klause-lattice` | Exact integer matrix reductions, unimodular transforms and triangular bounds |
| `klause` | Kotlin Multiplatform model, DSL, compilation, formats and solving |
| `klause-cli` | Shared command-line driver, format dispatch, output protocols and platform seams |
| `klause-bench` | JVM catalog, corpus management, campaign runner and offline measurement tools |
| `klause-mzn-lib` | MiniZinc solver configurations, FlatZinc wrappers, predicate declarations and smoke models |

The Gradle settings include `:klause-numerics`, `:klause-lattice`, `:klause`,
`:klause-cli` and `:klause-bench`.
`klause-mzn-lib` is integration data and scripts, rather than a Gradle module.

## Package boundaries

Packages below retain the `com.eignex.klause` namespace across modules.
`klause-numerics` owns `BigInt`, `BigFraction`, `Cancellation` and `WorkMeter`;
`klause-lattice` owns `lp.lattice`. The remaining packages and exact-simplex
consumer contracts live in [klause commonMain](../klause/src/commonMain/kotlin/com/eignex/klause/).

| Package | Owns | Dependency rule |
|---|---|---|
| `ir` | Literals, variable descriptions, remapping, structural keys and `Problem` | Engine-neutral; no engines, compilation or input formats |
| `model`, `schema`, `compile` | Model expressions, schema DSL and compilation | Use shared IR and lowering |
| `lowering` | Generic Boolean and constraint encodings | Independent of schema compiler and format callers |
| `formats` | Parsing and lowering source syntax | Uses shared lowering; no compiler or concrete theory dependency |
| `factor` | Constraint semantics and factor operations | Supplies mechanisms used by propagation, relaxations and search |
| `propagation` | Finite domains, explanations and incremental propagators | May use immutable arithmetic representations |
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
consumer behavior remain the responsibility of the JVM/native gates. The checker
also restricts numerical module package ownership and Gradle project dependencies.
[LpDeclineDisciplineTest](../klause/src/jvmTest/kotlin/com/eignex/klause/lp/LpDeclineDisciplineTest.kt)
checks proof-decline behavior at engine consumers.

The exact LP projection of `QfLraRelaxation` belongs to the theory adapter, beside
its source declarations. SMT syntax folding does not read theory or search state.

## Numerical module interfaces

`klause-numerics` has no Klause project dependency. It owns the JVM `BigInteger`
and Native bignum actuals behind the unchanged `BigInt` contract, canonical rational
values and cooperative work controls. `klause` exports it with `api`, so existing
library and CLI consumers keep the same numeric names and platform types.

`klause-lattice` depends only on `klause-numerics`; it cannot read model or solver
state. Its cross-module seam comprises sparse input rows, echelon/Hermite results,
read-only unimodular transforms and triangular bounds. These public declarations
require `LatticeApi` opt-in and are implementation interfaces versioned with Klause.
`klause` uses an `implementation` dependency and opts in within its own compilations.
Mutable column indexes, transform mutations and double-bounded reduction internals
remain `internal` to the lattice module. Result constructors remain internal;
`SparseIntRow` borrows caller arrays for the duration of a reduction, and result
arrays are borrowed read-only by consumers. No hot-loop buffer copies are added.

Exact simplex continuation, rational basis workspaces and LP certificates stay
with their current solver owner. Module extraction does not widen `lp.engine`'s
closed list. Leaf tests run in the owning module; existing model/LP/theory tests
and runnable CLI distribution/executable smoke checks validate consumption across
JVM and Native module boundaries.
No build-time or solving-speed improvement is implied by this module graph.

The [library API contract](api.md) classifies supported, experimental and implementation
surfaces and defines typed mapping and migration boundaries.

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
