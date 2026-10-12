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

## Published assignments and objectives

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
proof authority. The independent [certificate kernel](arithmetic/quadratic.md)
checks rational convex subproblems. The quadratic pipeline overload returns exact
source incumbents and direction-aware bounds, with separate attained-optimum,
unattained-limit, unbounded, infeasible and incomplete result types. Opaque
attestations capture source, objective, direction and invocation assumptions;
they retain no mutable arithmetic or cancellation owner. These attestations are
in-memory evidence and do not implement exported proof files.

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

`Problem.componentPlan()` selects an immutable ownership plan before search. It
builds a compact remapped CP projection and theory fragments. Plans belong to the
actual source model object. Fresh rewrites need a new plan or the explicit
objective-round adapter, which checks retained factor identity, bound narrowing
and the appended row's unchanged columns and coefficients. Variables owned by
an open theory never become finite CP domains. Continuous coordinates remain
arithmetic-owned and are not integer branch candidates.

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

## Search state ownership

Shared search exchanges Boolean literals, semantic integer bounds and typed opaque
theory decisions. Components can maintain private residual state through
`SearchBrancher`, but traversal and decision levels belong to the shared session.
`PropagationSession` remains finite-domain state. Source-keyed `SearchBoolValue`,
`SearchIntValue` and `SearchRealValue` assignments let each component contribute
only the values it owns.
