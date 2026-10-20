# 3. Model representation and ownership

The previous chapter followed a problem from source to result. This chapter
describes the data that travels along that path. A source model is the
engine-neutral description of a problem: its variable declarations, its factors
(the constraint relations over those variables) and its objective. Engines never
search the source model directly. Each builds its own view: finite domains for
propagation, assignments for local search, arithmetic state for theory or
relaxation checks. A view never authorizes a result by itself.

One ownership rule runs through the chapter: source data stays unchanged while any
solve uses it, each live operation owns its own mutable state, and results are
published as immutable assignments. [Section 3.1](03.1-source-relations.md) goes
through the factor relations the solver understands, and
[section 3.2](03.2-engine-projections.md) describes how preparation turns a source
model into engine views.

## Source declarations and factor data

`Problem` stores source integer declarations as bounds and, where necessary,
`SourceIntDomains` value sets. Mutable search domains belong to `BakedProblem` and
the propagation session; a declaration assigns no engine ownership.

`Problem` arrays and `Factor` payloads are borrowed read-only: their producer keeps
them unchanged while any model, plan, lazy operation or proof uses them. A rewrite
creates a fresh source scope. Custom factors keep their membership and structural
keys stable.

Real coordinates are declared the same way. `RealFiniteDomain` retains an immutable
finite set of exact binary64 values for one real coordinate, and a selected finite
grid never rounds a declared value onto the grid. `Power` retains a fixed
nonnegative integer exponent over integer or real coordinates; real powers are
enforced by a selected arithmetic encoder.

## Typed source semantics

A source model records how its syntax must be read. `ir.SourceSemantics` describes
numeric literal authority, float interpretation, default undeclared ranges, source
feasibility tolerance and absent optional values. A format declares only the
policies that apply to it, and a caller cannot introduce an absent one.

`SemanticOverrides` replaces applicable policies before lowering. `BoundsOverride`
replaces either default endpoint independently (`BoundOverride.Open` removes one);
declarations with an explicit range keep it (see
[MPS semantics](08-mps.md#declared-policy)). `SourceTolerance` declares slack as
`max(absolute, relative * scale)`, where a row's scale is the larger of its finite
bound magnitude and the sum of absolute term activities. Zero components require
exact checking, and integrality is always exact.

`EffectiveSourceSemantics` keeps both the defaults and the effective policy.
`isApproximate` marks a grid or positive tolerance and `hasOverrides` records any
change; neither supplies proof authority. Source rewrites, baking, presolve views
and private projections preserve this capture, and it selects no route or engine.

Every result carries the interpretation it was produced under: samples, witness
certificates and verdicts expose nullable `semantics`, even when interrupted
without an assignment. Changing witness coordinates discards the old certificate,
and the qualification never promotes a verdict's proof authority.

## Configuration and resources

`ProblemSettings` carries immutable resource metadata, such as the domain-storage
threshold, pre-bake span gate and automatic LP size limits, through source
rewrites and projections. None of it alters the declared solution set. Schema
compilation and format entry points resolve `KlauseConfig.current` once or accept
explicit settings; search effort stays in per-call backend parameters.

## Published assignments and objectives

`Sample`, exact theory assignments and reconstructed witnesses own immutable
coordinates. Array getters return independent buffers, and scalar reads retain
exact integer and rational authority. Replacing coordinates requires a fresh
witness check. `Sample.symbolicReals` retains exact analytic expressions for
transcendental coordinates (see
[exact analytic real coordinates](28-exact-arithmetic.md#exact-analytic-real-coordinates)).

Linear objectives capture their coefficient arrays at construction, so no getter
or copy can mutate them. Incumbent extensions supply immutable assignment and
objective payloads, or capture producer state in their verifier before acceptance.

## Quadratic objective representation

`ir.QuadraticObjectiveSpec` captures an exact rational constant and ordered
`QuadraticObjectiveTerm` values over typed source IDs. An affine term denotes
`coefficient * first`; a product denotes `coefficient * first * second`, with a
repeated coordinate expressing a square. Coefficients are literal reduced
rationals, and direction belongs to the invocation. Linear objectives bridge into
this representation with unchanged IDs. The
[certificate kernel](28-exact-arithmetic.md#exact-quadratic-certificates) checks
rational convex subproblems, and [polynomial real arithmetic](24-polynomial-real.md)
owns quadratic optimization results.

## Optional completed-result evidence

Completed satisfaction and minimization results can retain `SourceResultEvidence`
when requested through `SourceResultCapture`. Claims name the original revision,
invocation restrictions, objective and declared semantics, with exact values kept
apart from floating telemetry. Missing traversal or bound data is explicit;
[derivation checking](17-evidence.md) owns coverage checks.

---

Previous: [2. System architecture](02-architecture.md) · [Contents](README.md) · Next: [3.1 Source relations](03.1-source-relations.md)
