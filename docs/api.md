# Library API and ownership

The JVM and Kotlin/Native API dumps record published compatibility descriptors.
Publication alone does not make a solver implementation type an extension contract.
The classifications below apply to the entire published surface; unlisted mechanisms
in those packages are implementation APIs. Existing descriptors remain available
when a supported replacement is introduced, with warning-level deprecations for
migration rather than an immediate visibility or signature break.

## Supported library and extension surfaces

| Surface | Contract |
|---|---|
| `model`, `schema`, `compile` | Model expressions, schema handles, compilation, captured source-name metadata, typed source locations and decoding |
| `config`, `ir` model declarations | Configuration capture, source bounds/value sets, `Problem`, literals and structural factor descriptions |
| `solver` entry points and results | `Solver`, `Optimizer`, `SolverParams`, `Session`, `SearchStream`, their result/value types and resumable operation contracts |
| Built-in solver facades | Backtrack, local-search, brute-force and portfolio construction, documented parameters, presets and counting/sampling entry points |
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

Construction and term getters capture independent lists. Remapping preserves each
namespace and rejects removed objective coordinates; elimination requires an
exact source substitution. Internal evaluation uses the existing exact algebraic
arithmetic and returns an owned scalar. Floating scores supply no objective or
proof authority. The independent [certificate kernel](arithmetic/polynomial.md#quadratic-objective-certificates)
checks rational convex subproblems. Pipeline quadratic optimization is not exposed
by this representation alone.

## Continuous product representation

`factor.arithmetic.ContinuousProduct(a, b, result)` describes the exact real equality
`result = a * b`. All three IDs belong to the real namespace. Repeated operands
express squares; results can feed other products. Membership deduplicates real IDs,
while fields and structural keys retain operand multiplicity and the result role.
Keys treat operand order as commutative. Remapping applies the real mapping to every
role and preserves aliases, including aliases introduced by a noninjective map.
`Product` and `RealProduct` retain their integer and mixed semantics.

`ContinuousRealExpr` stores affine terms and product trees in the model AST.
The schema DSL supports `(x * x * y) eq z` for `FloatTerm` operands and
continuous expression chains. Scalar `FloatTerm * Double` retains its affine DSL
semantics; multiplying a continuous expression by a constant retains a product node.
[Source lowering](formats.md#schema-continuous-products) defines exact constants,
auxiliary columns and refused contexts.

Continuous products have no finite propagator, local-search score or finite
assignment checker. Direct finite execution projections reject them, and an LP
completion cannot certify their nonlinear equalities. Model solver entry points
route them through [shared polynomial completion](solver/qfnra.md#shared-model-composition)
beside finite factors across backtracking, local search, ALNS and mixed portfolios.
Exact rational/algebraic witnesses retain their source coordinates. This route
supports finite integer domains and finite-column objectives; open integer domains
and continuous objectives remain unsupported.

## Experimental and implementation surfaces

Experimental composition includes `ComponentPlan`, CP projection planning, shared
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

The `arithmetic.polynomial` algorithms and contexts are internal implementation
mechanisms. `AlgebraicReal` exports only owned exact scalar metadata, as defined in
[exact polynomial arithmetic](arithmetic/polynomial.md#rational-roots-and-scalar-identity).

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

`SmtLibAdmission.parse` returns a linear model, opaque `PolynomialRealProblem`, or
interrupted admission. The polynomial snapshot owns its source-name maps and returns
independent metadata views. Its `pipelineRequest()` and `OpenTheoryPipeline.execute`
extension provide complete polynomial satisfaction through the existing
`OpenTheoryResult`. `PolynomialTheoryExecution` retains native counters/resource
cause beside that common result; it does not reinterpret legacy open-work units.
`PolynomialTheoryParams` captures one invocation's work, allocation and stop controls.
The source snapshot can be reused for fresh invocations; no traversal state escapes.

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

[Model ownership](architecture.md#model-and-ownership) distinguishes borrowed source
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

`Sample.algebraicReals` carries immutable exact polynomial coordinates independently
of its approximate real buffer. Approximate values remain display or heuristic
metadata and carry no polynomial proof authority. An unchanged sample copy retains
its source witness certificate; changing any coordinate or exact scalar list discards
it. Polynomial pipeline strategy controls expose finite backtracking, local-search
and ALNS effort separately from the native arithmetic allowance.

`Problem.pipelineRequest()` composes shared finite factors with exact continuous
completion. `OpenTheoryPipeline.minimize` uses a finite objective in every strategy;
`PolynomialOptimizationExecution` preserves exact algebraic incumbents and native
work counters. Only complete traversal admits an optimality or infeasibility proof.
