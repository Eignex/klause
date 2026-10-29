# 19. Library API

Library callers construct a source, choose an operation and consume owned results.
Extensions supply factors, components, objectives or theories under the same semantic
and publication contracts. [Model ownership](02-model.md) and [sessions](06-sessions.md)
define lifetimes; this chapter identifies supported entry points and extension seams.

## Supported library and extension surfaces

| Surface | Contract |
|---|---|
| `model`, `schema`, `compile` | Model expressions, their compact JSON serializers, schema handles, compilation, captured source-name metadata, typed source locations and decoding |
| `config`, `ir` model declarations | Configuration capture, source bounds/value sets, `Problem`, literals and structural factor descriptions |
| `solver` entry points and results | `Solver`, `Optimizer`, `SolverParams`, `Session`, `SearchStream`, their result/value types and resumable operation contracts |
| Built-in solver facades | Engine construction, parameters, presets and counting/sampling entry points |
| `solver.objective` | Linear objective construction/evaluation; `Objective` and `IncrementalObjective` scoring extensions |
| `ir.Factor`, factor data and remapping | Stable membership/structural keys, affine/remapping refusal contracts and immutable borrowed factor payloads |
| `solver.search` component protocols | Decisions, explanations, context, component/brancher/blocker interfaces, contributed model keys and immutable assembled reads |
| `theory` protocols | Batch `Theory`, its fragment-check contract and authoritative exact assignment values; incremental participants use `SearchComponent` |
| `lp` projection protocols | `RelaxationBuilder`, factor relaxation/hull interfaces and typed source/CP/LP mapping handles |
| Format entry points | Public parsing, compilation and solution-writing facades; meaning follows [input semantics](03-input-semantics.md) |
| `formats.json.ProblemJson`, `factor.codec` | Problem documents, `FactorCodec` registration and `MissingFactorCodecException`; see [problem JSON](03-input-semantics.md#problem-json) |
| Exported numerical contracts | `BigInt`, `BigFraction`, `AlgebraicReal`, `Cancellation` and `WorkMeter` |

Supported extensions supply the behavior their interfaces document. A custom factor
keeps borrowed arrays and structural keys stable, and registers a `FactorCodec` to be
written as problem JSON; a custom component supplies complete
checks and sound explanations; a custom objective agrees at feasible assignments.
The type system does not certify these obligations. The
[custom factor publication check](08-search.md#custom-factor-publication-checks)
pins every candidate coordinate when a propagation-only factor has no local checker.

## Model construction and solving

Schemas and direct IR construction share source declarations and factor semantics;
[the DSL chapter](03-input-semantics.md#schema-lowering) gives a decoding example.

### Source-aware strategy entry points

`BacktrackSolver.source(...)` and `LocalSearchSolver.source(...)` accept a problem,
compiled schema or schema and return a `SourceStrategy` that arranges shared
preparation and required arithmetic completion. `solve` returns `OpenTheoryResult`
with exact source-coordinate assignments, including algebraic reals and
arbitrary-width integers. Finite globals stay part of the same source.

Explicit `FiniteEngine` overloads of `solve` and `optimize` select FIXED, BACKTRACK,
LOCAL_SEARCH, MIXED or ALNS per call and bind `ExecutionOptions`, lane count,
timeout, cancellation and a containing `NodeBudget` through `SourceExecutionRequest`.
Inapplicable options and conflicting node limits reject. Typed engine parameters
each carry their own work currency; a node allowance reaches every selected executor
through the same source binding. Controls a route cannot honor decline explicitly:
deduction scopes and CP tolerance acceptance are refused because source execution
requires exact coordinates.

`optimize` returns `QuadraticOptimizationExecution` for affine and quadratic
objectives; `optimizeOpen` returns `OpenTheoryExecution` for finite and open-integer
linear sources. Objective values are evaluated exactly from reconstructed source
coordinates in the caller's direction; rounded finite scores never become exact
claims. The baked `BacktrackSolver` and `LocalSearchSolver` keep their finite
sessions, streams, enumeration and resumable APIs, which source strategies do not
offer for continuous or open models.

`BruteForceSolver` walks finite assignments under `maxSteps` and `maxInstructions`.
A capped walk without a witness returns `Unknown`; infeasibility requires exhausting
the assignment space. Optimization certifies the optimum when the entire space is
visited, including when its size exactly equals the assignment budget.

### Source setup

Source setup is the one dispatcher every frontend and the CLI use. Attach a
canonical `Problem` with `SourceAttachment(problem, definitionHints, searchHints)`
and call `SourceSetup.prepare(SourceSetupRequest(...))`; the objective uses original
direction and units. Setup prefers exact arithmetic for pure-real models and for
linear integer objectives whose range exceeds binary64's exact integers; other finite
models keep finite execution. Native snapshots (`PolynomialRealProblem`,
`NonlinearIntegerProblem`, `TranscendentalProblem`, `LinearSourceProblem`) attach
with explicit `ProblemSettings` and keep their exact formula and objective. Hints are
described under [source definition materialization](10-local-search.md#source-definition-materialization).

`PreparedSourceInvocation` retains the selected route, source plan and operation
admission. When preparation selects a private float grid, `FiniteSolveResult.outcome`
reports source-qualified points and claims unless the consumer declares grid
semantics. FlatZinc declares that scope, recorded by
`SourceFloatProjection.declaredGridSemantics`, and reports grid witnesses, optima
and refutations. `nativeGridOutcome` keeps the grid's own outcome in either case.
An interrupted preparation returns `Declined` with its stop reason.

`SourceSetup.execute(SourceExecutionRequest(invocation, ...))` dispatches the
captured operation. `ExecutionOptions` resolves a seed and a `NodeBudget` (`auto`,
`unlimited` or a cap); `ExecutionOption.dryRunPresolve(true)` returns a prepared-only
result. `SourceExecutionResult` keeps the complete native execution, including
values, bounds, rays and telemetry; captured interpretation never upgrades a native
claim beyond its proof scope. Source arrays stay borrowed read-only, and fresh
controls cannot resume a stopped invocation.

Linear source outcomes record their terminal policy in
`LinearSourceOutcome.numericalClaims`: positive source tolerance permits numerical
optimality and infeasibility statuses; zero tolerance requires exact source
certification. Incumbents retain checked original coordinates and objective units.

### Objectives and native arithmetic sources

`OpenTheoryPipeline.minimize` accepts a finite `LinearObjective` or an exact
`QuadraticObjectiveSpec`; `optimize` accepts affine objectives with continuous costs.
Complete traversal can prove optimality, nonattainment, unboundedness or
infeasibility; local search and ALNS keep checked incumbents without global claims.
`NonlinearIntegerProblem.pipelineRequest()` and SMT-LIB's `SmtLibProblem.source`
expose the native arithmetic sources through the same verdict and witness surface.
Coverage is defined by [nonlinear integer](16-nonlinear-arithmetic.md),
[polynomial composition](16-nonlinear-arithmetic.md#shared-model-composition) and
[quadratic representation](02-model.md#quadratic-objective-representation).

## Compatibility and implementation surfaces

The JVM and Kotlin/Native API dumps record published descriptors, but publication
alone does not make a type an extension contract. Unlisted mechanisms in the packages
above are implementation APIs.

`FiniteSolveRequest` and the portfolio plan requests accept immutable
`ExecutionOptions`, for example `ExecutionOptions(ExecutionOption.seed(7L))`; the
`List<String>` constructors remain as compatibility adapters.
[Finite execution options](02-model.md#finite-execution-options)
define capture and worker lifetimes. Telemetry snapshots supply no result authority.

`PreparedSourcePlan`, component and strategy admission, CP projection planning,
search session construction and concrete theory adapters are experimental
composition surfaces: source association and proof meaning are mandatory, but
recipes can evolve. Local-search strategies, restart policies and LP bounding policy
are experimental tuning surfaces. Propagation workspaces, trails, presolve passes,
numerical bases, `lp.lattice` and portfolio coordination are implementation
mechanisms; the engine's dependency list is in
[architecture](01-introduction.md#package-boundaries).

## Theory and search extension roles

Implement `Theory<A>` for a batch fragment-checking service and use
`TheorySearchComponent<A>` to join the shared search lifecycle. Implement
`SearchComponent` directly for incremental finite or arithmetic state, adding
brancher, blocker or conflict capabilities as needed. Supply `SearchBranchProposal`
when an extension only proposes splits;
[lazy branch selection](08-search.md#lazy-branch-selection) defines categories
and exhaustive-alternative obligations.

A participating service can remain incomplete: an unresolved answer reports
indeterminate checking without a witness or refutation, and decided answers apply
only to the checked fragment. See
[theory roles](14-theories.md#extension-roles-and-completeness).

## Typed contributions

`TypedSearchValueKey<V>` associates a key with its immutable value type; its
capture function freezes borrowed producer state once during assembly. Equal keys
must agree on value type, and only one component may contribute a given key. Use
`model.contribute(key, value)` and `assembled.valueOf(key)`.

`SearchExactIntValue` carries `BigInt`, `SearchExactRealValue` `BigFraction`,
`SearchExactAlgebraicRealValue` an `AlgebraicReal`, and
`SearchExactRealExpressionValue` an `ExactRealValue`; `SearchBoolValue` carries
`Boolean`. A source real has one authoritative representation key. Other theory
symbols use extension-defined keys. Complete component assignments use explicit
`assignmentKey` values; batch adapters receive a typed assignment key at construction.
`SearchIntValue` and `SearchRealValue` identify locations in source-bound predicates.

The assembled model is captured, so a retained sink cannot add values after
publication. The open executor decodes source scalar keys through a source-bound
witness assembler: every declared coordinate must be present, and foreign mappings,
unmapped ids or multiple real representations withhold publication. Assembly
establishes no independent feasibility or proof authority; source checks and
[evidence admission](13-evidence.md) keep their scope.

## Identities at mapping boundaries

`SourceBoolId`, `SourceIntId` and `SourceRealId` name separate source namespaces.
`CpProblemProjection` maps `SourceIntId` to `CpIntId` (null for a theory-owned
integer), and `RelaxationBuilder` maps source locations to `LpColumnId`. LP layouts
contain auxiliaries and split columns, so a column alone does not imply a source
coordinate. These value types exist only at mapping seams, not in domain or tableau
storage. A location is valid only with its owning model, projection or layout.

## Snapshot and buffer ownership

Source structures stay borrowed read-only; samples and exact assignments own their
coordinates. Changing coordinates discards acceptance and requires a fresh check.
[Model ownership](02-model.md#published-assignments-and-objectives) defines the full
buffer, objective and lifetime contract.

## Exact assignment values

An assignment can carry finite values, arbitrary-precision integers, rational reals,
algebraic reals or symbolic real expressions. Read the authoritative payload for
checks and exact rendering; an approximate coordinate is display or heuristic data.

- `ExactLraAssignment` owns Boolean and rational real values; `ExactLiraAssignment`
  adds arbitrary-precision integers. Difference checks contribute their potentials.
- `ExactNraAssignment.algebraicRealValue(id)` returns the typed algebraic scalar;
  `realValue(id)` renders rationals as reduced text and others as
  `root(index;[c0,c1,...])` with ascending coefficients and a one-based root index.
  `Sample.algebraicReals` carries the same coordinates.
- `TranscendentalAssignment.exactRealValue(id)` and `symbolicRealValue(id)` expose
  [exact analytic real](18-exact-arithmetic.md#exact-analytic-real-coordinates)
  expressions, also captured by `Sample.symbolicReals`.

Open optimization observers receive `SourceIncumbentPublication` with an owned
`Sample`, its exact original-direction value and invocation version;
`OpenTheoryExecution.Optimize.acceptedIncumbent` keeps the latest one for rendering.

## Projection provider extensions

Engines turn each factor into a mechanism through a selector, and callers can replace
those selectors. `FactorProjectionProviders` groups `PropagationProjectionProvider`,
`ScoringProjectionProvider`, `RelaxationProjectionProvider` and `EncoderProvider`. A
provider returns a descriptor pairing declared participation and check authority with
a factory, and may delegate unmatched factors to the built-in selector. Null refuses
the role; an inert descriptor needs an explicit reason and cannot claim an exact check.

Selection happens once per source preparation: `providers.prepare(problem)` returns
`PreparedFactorProjections`, accepted by engine constructors, `problem.bake(providers)`
and `SourceStrategy.withProjectionProviders(providers)`. Descriptors hold no mutable
workspace; factories return private state for each live search or worker.

Encoders install a factor into a theory. `encoder.select(factor, target)` returns an
`Encoder` whose support is direct, equivalent, conditioned, relaxation-only or
unsupported. Direct and equivalent support enforce the unconditioned relation;
conditioned support also requires its captured source pins. `TheoryEncoding` stages
the relation and its private auxiliaries and commits only on success; a decline
discards everything staged.
Extensions must preserve every source solution, guard, strictness and reconstruction;
installing an encoding supplies no proof certificate by itself.

---

Previous: [18. Exact arithmetic](18-exact-arithmetic.md) · [Contents](README.md) · Next: [20. Command line](20-cli.md)
