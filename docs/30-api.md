# 30. Library API

Library callers construct a source model, choose an operation and consume owned
results. Extensions can add factors, search components, objectives or theories,
provided they uphold the same source semantics and publication checks.

Part VI turns from how Klause solves to how it is used. This chapter describes the
library surface built on the previous parts: which contracts are supported, how a
caller moves from a model to a result, which roles extensions can fill, and who owns
the values that cross the boundary. The underlying lifetimes come from
[model representation](03-model.md) and [sessions](10-sessions.md); mechanism-specific
solving rules stay in their chapters, and the [next chapter](31-cli.md) describes the
command-line frontend built on this API.

## Supported library and extension surfaces

| Surface | Contract |
|---|---|
| `model`, `schema`, `compile` | Model expressions, schema handles, compilation, captured source-name metadata, typed source locations and decoding |
| `config`, `ir` model declarations | Configuration capture, source bounds/value sets, `Problem`, literals and structural factor descriptions |
| `solver` entry points and results | `Solver`, `Optimizer`, `SolverParams`, `Session`, `SearchStream`, their result/value types and resumable operation contracts |
| Built-in solver facades | Engine construction, parameters, presets and counting/sampling entry points; source coverage differs from the shared pipeline as described below |
| `solver.objective` | Linear objective construction/evaluation; `Objective` and `IncrementalObjective` scoring extensions |
| `ir.Factor`, factor data and remapping | Stable membership/structural keys, affine/remapping refusal contracts and immutable borrowed factor payloads |
| `solver.search` component protocols | Decisions, explanations, context, component/brancher/blocker interfaces, contributed model keys and immutable assembled reads |
| `theory` protocols | Batch `Theory`, its context/fragment-check contract and authoritative exact assignment values; incremental participants use `SearchComponent` |
| `lp` projection protocols | `RelaxationBuilder`, factor relaxation/hull interfaces and typed source/CP/LP mapping handles |
| Format entry points | Public parsing, compilation and solution-writing facades; source/result meaning follows [input semantics](04-input-semantics.md) |
| Exported numerical contracts | `BigInt`, `BigFraction`, `AlgebraicReal`, `Cancellation` and `WorkMeter` retain their JVM/native ownership and representation contracts in `klause` |

Supported extensions supply the behavior their interfaces document. A custom factor
keeps borrowed arrays and structural keys stable; a custom component supplies complete
checks and sound explanations; a custom objective agrees at feasible assignments.
The type system does not certify any of those semantic obligations.
Engine-specific custom factor roles do not establish portable constraint coverage.
The [custom factor publication check](14.2-propagation.md#custom-factor-publication-checks)
pins every candidate coordinate when a propagation-only factor has no local checker.

## Model construction and solving

A schema declares variables and constraints, compilation produces a `Problem`, and
solver entry points return assignments or optimization results for decoding. Direct
model construction uses the same source declarations and factor semantics.
[Schema lowering](05-schema.md) defines representation choices and refused
contexts; [input semantics](04-input-semantics.md) defines route coverage.

### Source-aware strategy entry points

`BacktrackSolver.source(problem/compiled/schema)` and
`LocalSearchSolver.source(problem/compiled/schema)` return a `SourceStrategy` that
arranges shared preparation and required arithmetic completion. Schema construction
captures the compilation once; `compiledSchema` retains names and typed source
locations. `solve` returns `OpenTheoryResult` and exact source-coordinate assignments,
including algebraic reals and arbitrary-width open integers. Finite globals remain
part of the same source rather than becoming an unconstrained finite projection.

Explicit `FiniteEngine` overloads of `solve` and both affine and quadratic `optimize`
accept FIXED, BACKTRACK, LOCAL_SEARCH, MIXED and ALNS per call. They bind common
`ExecutionOptions`, lane count, timeout, cancellation and a containing `NodeBudget`
through `SourceExecutionRequest`. The factory-selected `engine` remains the default
for typed compatibility overloads. FIXED rejects multiple lanes; inapplicable
options and conflicting node limits reject through the common entry.
`SourceExecutionPolicy` captures explicit LP ceilings, zero-objective pricing,
application portfolio defaults and an explicit presolve emphasis, and validates
applicability before invoking a finite factory. Typed presolve `ExecutionOption`
settings apply to finite or linear preparation and reject on other routes.

Finite `SourceStrategy.solve` and affine `optimizeOpen` dispatch through
`SourceSetup.execute` and `FinitePipeline.solve`, retaining the full typed CP or LS
parameters, including assumptions, hooks, starting points and the node ledger. Each
completed envelope keeps its native result privately beside the reconstructed
source outcome, preserving the original stop reason.

Typed `BacktrackParams`, `LocalSearchParams`, `TheoryParams` (open linear) and the
native arithmetic parameter types each carry their own work currency. A node
allowance supplied by any of them reaches every selected executor through the same
source binding; different ledgers reject before preparation. Required arithmetic
work keeps its own currency. Controls the selected route cannot honor decline
explicitly: deduction scopes and CP tolerance acceptance are refused because source
execution requires exact coordinates, and extra CP components or finite-only hooks
return `Unknown(Unsupported)`.

`optimize` returns `QuadraticOptimizationExecution` for affine and quadratic
objectives and requires the native completion certificate; `optimizeOpen` exposes
`OpenTheoryExecution` for finite and supported open-integer linear sources.
Objective values are evaluated exactly from reconstructed source coordinates in the
caller's direction, and neither entry point converts rounded finite scores into
exact source claims. Compiled helper objectives retain exact original-coordinate
pairings, exposed through `CompiledSchema.sourceObjective`; `optimizeOpen` declines
coefficients without a lossless linear mapping. A presolve refutation alone does not
fabricate an engine certificate.

The baked `BacktrackSolver` and `LocalSearchSolver` classes retain their finite
sessions, streams, enumeration and resumable APIs. Source strategies do not
advertise those finite enumeration, counting or retained-stream operations for
continuous or open models. Source support follows [input coverage](04-input-semantics.md)
and [theory contracts](18-theories.md); resource stops and incomplete procedures
remain honest.

### Source setup

Source setup is the one dispatcher every frontend and the CLI use. Attach a
canonical `Problem` with `SourceAttachment(problem, definitionHints, searchHints)`
and call `SourceSetup.prepare(SourceSetupRequest(...))`; the request's `objective`
uses original source direction and units, and `maximize` records its sense. Setup
prefers exact arithmetic for pure-real models and for admitted linear integer
objectives whose declared range exceeds binary64's exact integral range; other
finite models keep finite execution. The `Problem.pipelineRoute` overloads delegate
here. Native source snapshots (`PolynomialRealProblem`, `NonlinearIntegerProblem`,
`TranscendentalProblem` and `LinearSourceProblem`) attach through
`SourceAttachment(source, settings)` with explicit `ProblemSettings`, keeping their
exact formula, namespaces and embedded objective; no finite model is substituted.
Hints and their move policies are described under
[source definition materialization](15.2-preparation.md#source-definition-materialization).

`PreparedSourceInvocation` retains the selected route, source plan and operation
admission; `finiteShape()` hands the captured engine plan to `FiniteSolveRequest`
without routing again. When preparation selects a private float grid,
`floatProjection` describes both namespaces, `FiniteSolveResult.outcome` reports
only source-qualified points and claims, and `nativeGridOutcome` keeps the grid's
own outcome. `PreparedSourcePlan.workingCoordinate` maps a typed original
coordinate into the working namespace. An interrupted preparation exports no plan
and returns `Declined` with its stop reason.

`SourceSetup.execute(SourceExecutionRequest(invocation, ...))` dispatches the
captured operation. Controls for a different family reject before execution.
`ExecutionOptions` resolves common controls once: a seed (default zero) and a
`NodeBudget` where `auto` selects a native default, `unlimited` removes the cap and
a nonnegative amount caps native units. `ExecutionOption.dryRunPresolve(true)`
returns a prepared-only result without running workers.

`SourceExecutionResult` keeps the complete native execution of the selected family,
including values, bounds, rays and resource telemetry. Captured interpretation never
upgrades a native claim beyond its producing model and proof scope, and
`completeSearchAdmitted` describes the resolved strategy without conferring any
authority. For a `LinearSourceProblem`, `linearSource` adds an original-unit point
and conclusion. These setup types are part of the experimental composition surface:
source arrays stay borrowed read-only, setup cancellation stays bound through later
execution, and fresh controls cannot resume a stopped invocation.

### Objectives and native arithmetic sources

`Problem.pipelineRequest()` composes shared finite factors with exact continuous
completion where supported. `OpenTheoryPipeline.minimize` accepts a finite
`LinearObjective` or an exact source `QuadraticObjectiveSpec`, and
`OpenTheoryPipeline.optimize` accepts affine objectives with continuous costs.
`LinearObjective.toQuadraticObjectiveSpec()` captures affine terms exactly: integer
weights keep full width and real weights the exact value of their binary64
coefficient. Complete traversal can prove optimality, nonattainment, unboundedness
or infeasibility; local search and ALNS retain checked incumbents whose
neighborhood verdicts establish no global claim.

`NonlinearIntegerProblem.pipelineRequest()` exposes exact polynomial integer and
mixed sources through `NonlinearIntegerTheoryRequest` with
`NonlinearIntegerTheoryParams`. SMT-LIB admission retains its content through
`SmtLibProblem.source` with objective metadata in original coordinates. Every
engine returns the common verdict and witness surface. The theory chapters define
coverage: [nonlinear integer](23-nonlinear-integer.md),
[polynomial composition](24-polynomial-real.md#shared-model-composition) and
[quadratic representation](03-model.md#quadratic-objective-representation).

## Compatibility and implementation surfaces

The JVM and Kotlin/Native API dumps record published compatibility descriptors.
Publication alone does not make a solver implementation type an extension contract.
The classifications above apply to the entire published surface; unlisted mechanisms
in those packages are implementation APIs. Existing descriptors remain available
when a supported replacement is introduced, with warning-level deprecations for
migration rather than an immediate visibility or signature break.

`FiniteSolveRequest`, `PortfolioPlanRequest` and `FixedBacktrackPlanRequest` accept
immutable `ExecutionOptions`. For example, `ExecutionOptions(ExecutionOption.seed(7L),
ExecutionOption.variableSelector(VarSelectorKind.VSIDS))` configures values without
frontend syntax. The original `List<String>` constructors, their default argument
descriptors and `engineParams` getters remain available as compatibility adapters.
Directly configured options expose an empty legacy parameter list; parsed options
retain the captured frontend parameters. Resolution uses typed settings directly.
`EngineParams` and its consuming helper functions retain their
original signatures and behavior. [Model ownership](03.2-engine-projections.md#finite-execution-options)
defines capture and worker lifetimes.

Telemetry snapshots such as `OpenHintStats` and `PolynomialTheoryStats` retain their
original constructors, copy and destructuring descriptors; additional observations
are optional immutable fields that legacy construction leaves absent. Telemetry
supplies no source-result authority.

`PreparedSourcePlan` retains its published getters, objective-binding overloads and
coordinate methods as a facade over internal typed execution payloads. Linear and
quadratic rebinding preserve structural source ownership while capturing independent
operation data; a bound facade does not authorize reuse of objective evidence.

Experimental composition includes `PreparedSourcePlan` and its inspectable component,
operation and strategy admission contracts, `ComponentPlan`, CP projection planning, shared
search session/runner construction, direct pipeline engines and concrete theory
adapters. Their source association and proof meaning remain mandatory, but composition
recipes can evolve. Concrete local-search strategies, move selectors, restart policies,
LP bounding policy and diagnostic controls are experimental tuning surfaces.

Propagation workspaces, trails, projection caches, presolve pass machinery, source
rebuild records, numerical bases/tableaux and private portfolio coordination are
implementation mechanisms. Published helper collections and lowered compiler/parser
state are implementation APIs unless the supported table names their contract.
Consumers retain compatibility descriptors while migrating to a supported entry point;
implementation visibility is not a promise of stable lifecycle or extension behavior.

Numerical algorithms and mutable contexts are implementation mechanisms. Exported
exact scalar values retain the representation and ownership contracts defined in
[exact polynomial arithmetic](28-exact-arithmetic.md#rational-roots-and-scalar-identity).

The `lp.lattice` package is an internal implementation mechanism within `klause`.
Its reductions borrow caller-owned input rows and expose read-only result buffers
to solver consumers.
The engine's closed dependency list remains in [architecture](02-architecture.md#package-boundaries).

## Theory and search extension roles

Implement `Theory<A>` for a batch fragment-checking service and use
`TheorySearchComponent<A>` to join the shared search lifecycle. Implement
`SearchComponent` directly for incremental finite or arithmetic state, adding optional
brancher, blocker or conflict capabilities as needed. `TheoryComponent` retains its
published descriptor as a deprecated marker; it adds neither behavior nor completeness.

Supply `SearchBranchProposal` separately when an extension only proposes splits.
`SearchSession.openRun` accepts an independent `SearchBranchSelection`; the existing
overload preserves Boolean-first selection. Legacy `SearchBrancher` signatures stay
available. [Lazy branch selection](14.1-traversal.md#lazy-branch-selection) defines
categories, proposal ordering and exhaustive-alternative obligations.

A participating service can remain incomplete. Its unresolved batch answer uses the
legacy `TheoryCheck.Cancelled` surface and the bridge reports indeterminate checking,
without a witness or refutation. Decided answers apply only to the checked fragment
and active conditions. See [theory roles](18-theories.md#extension-roles-and-completeness)
for admission and proof scope.

## Typed contributions and migration

`TypedSearchValueKey<V>` associates a key with its immutable value type. Extensions may
implement the interface or keep an identity-based `SearchModelKey<V>` instance. Its
`snapshot`/capture function freezes borrowed producer state once during assembly.
Runtime type checks cover legacy writes; generic argument validity and deep immutability
remain the extension's obligation. Equal keys must agree on the value type and ownership
contract. Only one component may contribute a given key.

Use `model.contribute(key, value)` and `assembled.valueOf(key)` to infer the same value
type at publication and consumption. `SearchExactIntValue` always carries `BigInt`,
including finite CP coordinates; `SearchExactRealValue` carries `BigFraction`; `SearchExactAlgebraicRealValue` carries
an owned `AlgebraicReal`. `SearchExactRealExpressionValue`
carries an immutable `ExactRealValue` for algebraic or symbolic coordinates. A source
real has one authoritative representation key; contributing multiple representation keys is unavailable
at source witness assembly.
`SearchBoolValue` carries `Boolean`. Arrays, functions and other theory symbols use
extension-defined keys without adding cases to the shared engine.

Legacy `SearchModel.put(Any, Any)` and `valueOf(Any)` remain available with deprecation
warnings. Existing custom model sinks still implement the same interface. Legacy
`SearchValueKey`, component-object keys, `SearchIntValue` and `SearchRealValue` retain
their identity/equality and producer-specific representations. These compatibility
values are borrowed read-only; a typed key is the migration path for checked ownership.
Built-in scalar contributions publish both representations during migration.

The assembled model captures its map so a retained assembly sink cannot add values to
an already published model. Captured typed values can be read repeatedly without copies.
A model key's identity belongs to its source scope, rather than a global symbol registry.

The open executor decodes source scalar keys through a source-bound witness assembler,
without inspecting a component identity or native assignment type. Every declared
coordinate must be present. Foreign reconstruction mappings, unmapped numeric ids,
and multiple real representations withhold publication. Existing reconstruction steps
recover original Boolean and integer coordinates at exact width. Native assignment
wrappers remain compatible, but the executor emits an owned neutral coordinate witness.

Assembly uses the retained reconstruction operations without treating optional universal
transformation guarantees as an independent proof of the published point.
Assembly follows all required component checks; it establishes no independent source
feasibility, agreement between fragments or proof authority. Exact affine certificate
consumers require rational real coordinates. Algebraic and symbolic coordinates retain
their representation and decline unsupported certificate conversion. Source checks,
invocation restrictions and [evidence admission](17-evidence.md) retain their scope.

## Identities at mapping boundaries

`SourceBoolId`, `SourceIntId` and `SourceRealId` name separate source namespaces.
`CompiledSchema.sourceId`/`sourceRealId` expose original locations, including real
locations for floats with a private compatibility bucket. `VarRemap` owns its primitive tables and returns typed source
locations or null for an absent target. Internal symmetry refinement borrows its owner's
mutable tables through an explicit workspace view, confined to that synchronous operation.

`CpProblemProjection` maps `SourceIntId` to `CpIntId`, returning null for a theory-owned
integer, and maps the finite id back to the source. `RelaxationBuilder` maps finite
integer, shared-source Boolean and source-real locations to `LpColumnId`, returning null
when its primitive column method declines. LP layouts can contain auxiliaries, slacks
and split real columns; a column handle alone does not imply a source coordinate.

These small value types distinguish namespaces at mapping seams. They do not allocate
per-variable objects in domain, factor or tableau storage. Primitive overloads and
sentinels remain available for compatible kernel consumers. A location is valid only
with its owning model/projection/layout; typed wrappers do not replace source identity
checks or authorize proof reuse across a rewrite.

## Snapshot and buffer ownership

[Model ownership](03-model.md) distinguishes borrowed source
arrays from owned publications. `Problem`/`Factor` structures remain unchanged for the
lifetime of every operation, plan or proof referring to them. Finite domains, assignments,
LP scratch and packed restart anchors belong to their active owners.

`Sample`, exact assignments and rebuilt witnesses own immutable coordinates. Public
array getters return independent buffers; scalar accessors avoid exporting or copying
storage. Replacing coordinates in a sample copy drops incompatible exact authority and
requires a fresh witness check. Unchanged copies retain authority and can share private
immutable storage. Exact integer and rational values survive configuration capture,
source reconstruction and incumbent publication without a finite or floating stand-in.

Linear objectives own their coefficients. Compiled source-name maps own their containers
and return independent metadata views; decoding uses private lookups. Generic incumbent
protocols require immutable accepted assignment/objective payloads; custom verifiers
capture producer state before acceptance. Built-in carriers can be shared through
publication and repeated incumbent reads without copying in the search loop.

## Exact assignment values

`TranscendentalAssignment.exactRealValue(id)` and `symbolicRealValue(id)` expose
immutable exact expression metadata for transcendental witnesses, including through
reconstruction wrappers. Rational and algebraic leaves retain their existing exact
metadata. `Sample.symbolicReals` captures the same authority. An expression describes
a coordinate; its publication still requires an original-source check. See
[exact analytic real coordinates](28-exact-arithmetic.md#exact-analytic-real-coordinates) and the
[transcendental service contract](25-transcendental.md).

An assignment can carry finite values, arbitrary-precision integers, rational reals
or algebraic reals. Read the authoritative payload for checks and exact rendering;
an approximate coordinate is display or heuristic data.

Difference checks contribute their integer or rational potential coordinates.
`ExactLraAssignment` owns Boolean and rational real values. `ExactLiraAssignment`
owns Boolean values, arbitrary-precision integers and rational reals for integer or
mixed linear sources. Their source association and publication checks follow the
[theory contracts](18-theories.md). The following accessors preserve the algebraic
representation used by polynomial completion.

`ExactNraAssignment` captures Boolean and exact algebraic real coordinates, and
`algebraicRealValue(id)` returns the typed scalar through reconstruction wrappers;
other routes return null there. `realValue(id)` renders point-identified values as
reduced rational text and other algebraic values as `root(index;[c0,c1,...])`, with
ascending exact coefficients and a one-based index among the defining polynomial's
distinct real roots. `Sample.algebraicReals` carries the same coordinates
independently of the approximate buffer. Changing any coordinate of a sample copy
discards its source witness certificate. Numerical equality requires exact
arithmetic comparison rather than object identity.

Open optimization observer overloads publish `SourceIncumbentPublication` with an
owned `Sample`, its exact original-direction `SourceValue` and invocation version.
`OpenTheoryExecution.Optimize.acceptedIncumbent` retains the latest publication for
final rendering, including incomplete outcomes; a terminal's private witness is
separate from it. Affine mixed results retain typed optimality, limits and rays in
`OpenTheoryOptimum.linearResult`.

## Projection provider extensions

Engines turn each factor into a mechanism through a selector, and callers can
replace those selectors without editing central dispatch. `FactorProjectionProviders`
groups the four of them: `PropagationProjectionProvider`, `ScoringProjectionProvider`,
`RelaxationProjectionProvider` and `EncoderProvider`. A provider returns a
descriptor that pairs declared participation and check authority with a factory or
emitter, and may delegate unmatched factors to the built-in selector. Null refuses
propagation or scoring and contributes no relaxation. An inert descriptor needs an
explicit reason, cannot claim an exact check, and leaves its source check to an
enforcing peer or separate certification.

Selection happens once per source preparation. `providers.prepare(problem)` returns
`PreparedFactorProjections`, which engine constructors, `problem.bake(providers)`,
`Problem.prepareExecutionPlan(providers, ...)` and
`SourceStrategy.withProjectionProviders(providers)` all accept. The capture survives
objective rebinding and conditioned preparation; coordinate projections remap it,
and a source rewrite selects anew. Ordinary `bake()` keeps the built-in defaults.
Descriptors hold no mutable workspace: factories receive the immutable factor in the
engine's coordinate namespace and return private state for each live search or
worker. Open heuristic models bind a capture to their move domains through
`LocalSearchModel.open(problem, selection, domains)`.

Encoders install a factor into a theory. `encoder.select(factor, target)` returns a
prepared `Encoder` whose support is direct, conditioned on required integer fixings
or Boolean pins, relaxation-only or unsupported; only the first two may install.
The receiving `TheoryEncoding` stages the full relation, conjoins repeated `linear`
emissions, accepts compound `linearFormula` structure, and allocates private
auxiliaries, guards and real existentials through a per-scope `EncodingNamespace`.
It commits only after a successful installation; a decline, including an explicit
`EncoderDecline` for resources or missing conditions, discards everything staged.
`encodingTargets` adds consumer languages beside the built-in linear, difference and
polynomial targets. Polynomial encoders receive neutral `PolynomialExpression` and
`PolynomialRealFormula` syntax; see [polynomial real arithmetic](24-polynomial-real.md).

`ComponentPlan.factorOwner` records the primary enforcing path and `factorEncoded`
records arithmetic participation, so a bounded linear relation can keep propagation
and its encoder at once. Extensions remain responsible for source membership,
soundness, exact checks and scoring delta consistency, and must preserve every
source solution, guard, strictness and reconstruction. Installing an encoding
supplies no proof certificate by itself.

---

Previous: [29. Numerical dependencies](29-numerical-dependencies.md) · [Contents](README.md) · Next: [31. Command-line interface](31-cli.md)
