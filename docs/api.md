# Library API and ownership

Library callers construct a source model, choose an operation and consume owned
results. Extensions can add factors, search components, objectives or theories,
provided they uphold the same source semantics and publication checks.

This page identifies supported contracts and defines public mappings and value
ownership. [Model representation](model.md) owns source and engine lifetimes;
[sessions](solver/sessions.md) owns operation scopes. Mechanism-specific solving
rules remain in their subsystem pages.

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
| `theory` protocols | `Theory`, its context/check contract and authoritative exact assignment values; custom theories retain their native representations |
| `lp` projection protocols | `RelaxationBuilder`, factor relaxation/hull interfaces and typed source/CP/LP mapping handles |
| Format entry points | Public parsing, compilation and solution-writing facades; source/result meaning follows [input semantics](formats.md) |
| Exported numerical contracts | `BigInt`, `BigFraction`, `AlgebraicReal`, `Cancellation` and `WorkMeter` retain their JVM/native ownership and representation contracts in `klause` |

Supported extensions supply the behavior their interfaces document. A custom factor
keeps borrowed arrays and structural keys stable; a custom component supplies complete
checks and sound explanations; a custom objective agrees at feasible assignments.
The type system does not certify any of those semantic obligations.
Engine-specific custom factor roles do not establish portable constraint coverage.
The [custom factor publication check](solver/search.md#custom-factor-publication-checks)
pins every candidate coordinate when a propagation-only factor has no local checker.

## Model construction and solving

A schema declares variables and constraints, compilation produces a `Problem`, and
solver entry points return assignments or optimization results for decoding. Direct
model construction uses the same source declarations and factor semantics.
[Schema lowering](formats/schema.md) defines representation choices and refused
contexts; [input semantics](formats.md) defines route coverage.

### Source-aware strategy entry points

`BacktrackSolver.source(problem/compiled/schema)` and
`LocalSearchSolver.source(problem/compiled/schema)` return a `SourceStrategy` that
arranges shared preparation and required arithmetic completion. Schema construction
captures the compilation once; `compiledSchema` retains names and typed source
locations. `solve` returns `OpenTheoryResult` and exact source-coordinate assignments,
including algebraic reals and arbitrary-width open integers. Finite globals remain
part of the same source rather than becoming an unconstrained finite projection.

Typed `BacktrackParams` and `LocalSearchParams` overloads retain their finite and
polynomial controls. `PolynomialTheoryParams` supplies native polynomial limits and
common pins; `TheoryParams` supplies open-linear limits. These are distinct work
currencies. A source operation owns fresh private state; shared node allowances retain
their identity. Deduction scopes and CP tolerance acceptance explicitly decline because
source execution requires exact coordinates and cannot discard restrictions. Extra CP
components, open pins and finite-only open hooks return `Unknown(Unsupported)`.
Exact polynomial LS also declines finite seeding, incumbent exchange, custom scoring
and event hooks that its native neighborhood cannot consume.

`optimize` preserves the existing `QuadraticOptimizationExecution` surface through
the shared exact affine or quadratic entry point. It supports bounded integer
sources, continuous products and open real coordinates, with exact objective values,
direction-aware bounds, attainment and incomplete results. `optimizeOpen` exposes
the existing native `OpenTheoryExecution` for supported open-integer linear sources;
its values retain native minimization sense. Neither entry point converts rounded
finite scores into exact source claims.

The baked `BacktrackSolver` and `LocalSearchSolver` classes retain their finite
sessions, streams, enumeration and resumable APIs. Their compiled/schema constructors
remain compatible with warning-level finite-only migration messages. Source strategies
do not advertise those finite enumeration/counting/retained-stream operations for
continuous or open models. Source support follows [input coverage](formats.md) and
[theory contracts](theories.md); resource stops and incomplete procedures remain honest.
Source execution consumes `PreparedSourcePlan.capabilities` for finite admission and
binds exact optimization objectives on that same preparation. Missing source checks or
unsupported objective coordinates decline before constructing an executor.

### Objective admission

`Problem.pipelineRequest()` composes shared finite factors with exact continuous
completion where supported. `OpenTheoryPipeline.minimize` accepts a finite
`LinearObjective` or an exact source `QuadraticObjectiveSpec`. `OpenTheoryPipeline.optimize`
accepts an affine `LinearObjective` with continuous costs and explicit source direction,
using the same exact quadratic executor. The quadratic overload and affine `optimize`
returns `QuadraticOptimizationExecution` with exactly evaluated incumbents,
source-direction bounds and separate native counters. Complete traversal can prove
optimality, nonattainment, unboundedness or infeasibility. Local search and ALNS
retain checked incumbents and independently checked global box bounds; their
neighborhood verdicts do not establish global claims.
See [polynomial composition](theories/polynomial-real.md#shared-model-composition)
for source coverage and [quadratic representation](model.md#quadratic-objective-representation)
for exact coefficient and coordinate ownership.

`LinearObjective.toQuadraticObjectiveSpec()` and the equivalent lowered objective
extension capture affine terms in the exact source representation. Integer weights
and constants retain their full width; real weights retain the exact rational value
of the declared binary64 coefficient. Nonfinite weights reject. Exact source rational
or decimal objectives use `QuadraticObjectiveSpec` directly without a binary64 conversion.

The polynomial finite-result `minimize(LinearObjective)` overload rejects nonzero real
costs; callers requiring continuous optimization use `optimize`. Source routing
prepares ownership independently of the objective, then binds the original affine
objective and direction to the captured plan. Continuous products and mixed finite/real
products use their existing polynomial completion for affine and quadratic objectives.
Each affine operation retains its original source snapshot and caller objective on
its bound request; changing the objective does not rebind an earlier request.

### Nonlinear integer sources

`NonlinearIntegerProblem.pipelineRequest()` exposes exact polynomial integer/mixed
sources through `NonlinearIntegerTheoryRequest`. `NonlinearIntegerTheoryParams`
combines polynomial controls, candidate/move limits and an optional shared node
budget. Every engine returns the common verdict/witness surface. Rebuilt assignments
retain arbitrary-width original integers and exact algebraic original reals;
numeric conditional auxiliaries remain private. See [nonlinear integer semantics](theories/nonlinear-integer.md).
The quadratic `minimize` overload uses shared exact integer branching and returns
the same quadratic incumbent/bound result surface for open and arbitrary-width
integer coordinates. Heuristic engines retain checked incumbents; complete
traversal must cover every remaining integer branch before a global claim.
SMT-LIB quadratic admission retains the same existing source snapshots under
`SmtLibInput.Quadratic`, with exact original coordinate terms and objective sense.
The CLI forwards this captured request and renders its exact optimization result.


## Compatibility and implementation surfaces

The JVM and Kotlin/Native API dumps record published compatibility descriptors.
Publication alone does not make a solver implementation type an extension contract.
The classifications above apply to the entire published surface; unlisted mechanisms
in those packages are implementation APIs. Existing descriptors remain available
when a supported replacement is introduced, with warning-level deprecations for
migration rather than an immediate visibility or signature break.

Experimental composition includes `PreparedSourcePlan` and its inspectable component,
operation and strategy admission contracts, `ComponentPlan`, CP projection planning, shared
search session/runner construction, direct pipeline engines and concrete theory
adapters. Their source association and proof meaning remain mandatory, but composition
recipes can evolve. Concrete local-search strategies, move selectors, restart policies,
LP bounding policy and diagnostic/replay controls are experimental tuning surfaces.

Propagation workspaces, trails, projection caches, presolve pass machinery, source
rebuild records, numerical bases/tableaux and private portfolio coordination are
implementation mechanisms. Published helper collections and lowered compiler/parser
state are implementation APIs unless the supported table names their contract.
Consumers retain compatibility descriptors while migrating to a supported entry point;
implementation visibility is not a promise of stable lifecycle or extension behavior.

Numerical algorithms and mutable contexts are implementation mechanisms. Exported
exact scalar values retain the representation and ownership contracts defined in
[exact polynomial arithmetic](arithmetic/polynomial.md#rational-roots-and-scalar-identity).

The `lp.lattice` package is an internal implementation mechanism within `klause`.
Its reductions borrow caller-owned input rows and expose read-only result buffers
to solver consumers.
The engine's closed dependency list remains in [architecture](architecture.md#package-boundaries).

## Typed contributions and migration

`TypedSearchValueKey<V>` associates a key with its immutable value type. Extensions may
implement the interface or keep an identity-based `SearchModelKey<V>` instance. Its
`snapshot`/capture function freezes borrowed producer state once during assembly.
Runtime type checks cover legacy writes; generic argument validity and deep immutability
remain the extension's obligation. Equal keys must agree on the value type and ownership
contract. Only one component may contribute a given key.

Use `model.contribute(key, value)` and `assembled.valueOf(key)` to infer the same value
type at publication and consumption. `SearchExactIntValue` always carries `BigInt`,
including finite CP coordinates; `SearchExactRealValue` carries `BigFraction`.
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

## Identities at mapping boundaries

`SourceBoolId`, `SourceIntId` and `SourceRealId` name separate source namespaces.
`CompiledSchema.sourceId`/`sourceRealId` expose those locations; a bucketed float has no
continuous source location. `VarRemap` owns its primitive tables and returns typed source
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

[Model ownership](model.md) distinguishes borrowed source
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
[exact analytic real coordinates](arithmetic/real.md) and the
[transcendental service contract](theories/transcendental.md).

An assignment can carry finite values, arbitrary-precision integers, rational reals
or algebraic reals. Read the authoritative payload for checks and exact rendering;
an approximate coordinate is display or heuristic data.

Difference checks contribute their integer or rational potential coordinates.
`ExactLraAssignment` owns Boolean and rational real values. `ExactLiraAssignment`
owns Boolean values, arbitrary-precision integers and rational reals for integer or
mixed linear sources. Their source association and publication checks follow the
[theory contracts](theories.md). The following accessors preserve the algebraic
representation used by polynomial completion.

`ExactNraAssignment` captures Boolean and exact algebraic real coordinates. Its real
values include rational points and selected irrational roots; reading a coordinate
does not refine it or require its producing arithmetic context. The common
`OpenTheoryAssignment.ExactNra` witness preserves this representation.
`algebraicRealValue(id)` returns its typed scalar through reconstruction wrappers.
Other routes retain their existing rational text and return null from that accessor.
Point-identified rational values use reduced rational text. Other algebraic values
use `root(index;[c0,c1,...])` from `realValue(id)`: coefficients
are exact rationals in ascending exponent order, and the index is one-based among
the defining polynomial's distinct real roots in increasing order. This text carries
an exact value; callers rendering another format use the typed metadata.
Numerical equality requires exact arithmetic comparison rather than object identity.

`Sample.algebraicReals` carries immutable exact polynomial coordinates independently
of its approximate real buffer. Approximate values remain display or heuristic
metadata and carry no polynomial proof authority. An unchanged sample copy retains
its source witness certificate; changing any coordinate or exact scalar list discards
it. Polynomial pipeline strategy controls expose finite backtracking, local-search
and ALNS effort separately from the native arithmetic allowance.

Open optimization observer overloads publish `SourceIncumbentPublication` with an owned
`Sample`, already evaluated exact original-direction `SourceValue` and invocation version.
`OpenTheoryExecution.Optimize.acceptedIncumbent` retains the latest publication for final
rendering, including incomplete outcomes. A terminal's private witness is separate from
this accepted point. Captured affine objectives use their original exact coefficients and
constant. The heuristic-only maximization overload receives a minimized execution objective
and restores the source direction for publication. Observer exceptions propagate after
invocation cleanup. Affine mixed results retain typed optimality, limits and integer/real
rays in `OpenTheoryOptimum.linearResult`; incomplete execution retains independently
checked global root bounds and the accepted source point. These fields remain separate
from scalar telemetry and from a ray's private base.
