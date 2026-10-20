# 3. Model representation and ownership

The previous chapter followed a problem from source to result. This chapter
describes the data that travels along that path. A source model is the
engine-neutral description of a problem: its variable declarations, its factors
(the constraint relations over those variables) and its objective. Engines never
search the source model directly. Each one builds its own view of it: finite
domains for propagation, assignments for local search, and arithmetic state for
theory or relaxation checks. A view does not replace the source semantics or
authorize a result by itself.

One ownership rule runs through the whole chapter. Source data stays unchanged
while any solve uses it, each live operation owns its own mutable state, and
results are published as immutable assignments. This chapter describes the source
model, what it records about its own interpretation, and the results published
from it. [Section 3.1](03.1-source-relations.md) goes through the factor relations
the solver understands, and [section 3.2](03.2-engine-projections.md) describes how
preparation turns a source model into engine views. The next chapter shows how
input formats produce these models.

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

Real coordinates are declared the same way. `RealFiniteDomain` retains one original
real coordinate and an immutable finite set of exact binary64-authoritative values;
every declared membership remains in source scope, and a selected finite grid cannot
round a declared value onto the grid. `Power` retains a fixed nonnegative integer
exponent over typed integer or real coordinates. Finite projections filter its
bounds and check exact assignments; real powers have heuristic scores only, so
the source relation is enforced by a selected arithmetic encoder. Generic `Problem`
polynomial composition keeps its [finite integer boundary](24-polynomial-real.md#scope-and-boundaries).

## Typed source semantics

A source model records how its syntax must be read. `ir.SourceSemantics` describes
numeric literal authority, float interpretation, default undeclared ranges, source
feasibility tolerance and absent optional-value canonicalization. A format declares
only the policies that apply to it; a caller cannot introduce an absent one, and
DIMACS, WCNF and XCSP3 reject numeric, float, tolerance and optional-value overrides
outright.

`SemanticOverrides` replaces applicable policies before lowering. `BoundsOverride`
replaces either default endpoint independently, with `BoundOverride.Open` removing
one; replacements can widen or narrow a range, and declarations with an explicit
range keep it. MPS, for example, applies declaration defaults only to wholly
unranged columns (see [MPS semantics](08-mps.md#declared-policy)).
`FloatSemantics.Grid` keeps its approximation window separately from undeclared
real bounds, so replacing a grid with continuous semantics removes the window but
keeps an explicit range.

`SourceTolerance` declares slack as `max(absolute, relative * scale)`. A row's scale
is the maximum of its finite bound magnitude and the sum of absolute term
activities; a variable bound uses its finite bound magnitude. Zero components
require exact checking, and integrality is always exact. Tolerances and real
default bounds use engine-neutral `SemanticRational` values.

`EffectiveSourceSemantics` keeps both the defaults and the effective policy.
`isApproximate` marks a grid or positive tolerance and `hasOverrides` records any
change, including range replacements; neither supplies feasibility, completeness or
proof authority. `Problem` keeps this capture separately from `ProblemSettings`,
and source rewrites, baking, presolve views and private projections preserve it.
The IR types select no route, theory, engine or resource policy.

Every result carries the interpretation it was produced under. Samples, witness
certificates and satisfaction, sampling, optimization and assumption verdicts
expose nullable `semantics` independently of their assignments, so even an
interrupted result without an assignment keeps its interpretation. Changing witness
coordinates discards the old certificate and its qualification, and the
qualification never promotes a verdict's proof authority. File frontends resolve
the [common CLI source controls](31-cli.md#flags) against their declared policies
before lowering; publication authority remains governed by
[input semantics](04-input-semantics.md) and checked source evidence.

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

## Published assignments and objectives

`Sample`, exact theory assignments and reconstructed witnesses own immutable
coordinates. Public array getters and destructuring return independent buffers;
scalar reads retain exact integer/rational authority without copying arrays.
`Assignment.snapshot()` transfers newly allocated publication buffers once. An
unchanged sample copy can share its private immutable buffers and certificate;
replacement coordinates are captured and require a fresh witness check.
`Sample.symbolicReals` retains exact analytic expressions for transcendental
coordinates; its approximate view is display data only. See
[exact analytic real coordinates](28-exact-arithmetic.md#exact-analytic-real-coordinates).

Lowered and solver linear objectives capture their coefficient arrays at construction.
Their getters, copies and destructuring cannot mutate the captured objective. Solver
consumers borrow those private coefficients read-only inside the module. Generic
incumbent extensions supply immutable assignment/objective payloads, or capture
producer state in their verifier before acceptance. Built-in finite and open witnesses
meet that contract without a copy at every incumbent read.

## Quadratic objective representation

`ir.QuadraticObjectiveSpec` captures an exact rational constant and an ordered list
of `QuadraticObjectiveTerm` values over typed Boolean, integer and real source IDs.
An affine term denotes `coefficient * first`; a product denotes
`coefficient * first * second`, and repeating the coordinate expresses a square.
Coefficients are literal reduced rationals, without Hessian scaling. Objective
direction belongs to the invocation. Affine linear objectives bridge into this
representation with unchanged source IDs; binary64 coefficients become their exact
rational values. Remapping rejects removed objective coordinates. Floating scores
supply no objective or proof authority; the [certificate kernel](28-exact-arithmetic.md#exact-quadratic-certificates)
checks rational convex subproblems, and [polynomial real arithmetic](24-polynomial-real.md)
owns quadratic optimization results.

## Optional completed-result evidence

Completed satisfaction and minimization results can retain `SourceResultEvidence`
when capture is requested through `SourceResultCapture`. Claims name the original
revision, invocation restrictions, objective identity/direction and declared semantics.
Exact coordinates and objective values remain separate from floating telemetry.
Captured engine attestations do not supply independently checked derivation coverage;
missing traversal, transformation or bound data is explicit. [Derivation checking](17-evidence.md)
owns the consumer-provided original-source contexts and coverage checks.

---

Previous: [2. System architecture](02-architecture.md) · [Contents](README.md) · Next: [3.1 Source relations](03.1-source-relations.md)
