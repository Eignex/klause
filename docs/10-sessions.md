# 10. Sessions and streams

Part II described the source model and the frontends that produce it. This part
follows a solve of that model from the caller's request to a published result,
starting with scope: which assumptions apply, which operation is live, and which
state survives between calls.

A *session* groups operations under caller assumptions. A *stream* or *resumable
handle* keeps one operation alive across consumption or across time slices. The
session owns those scopes and prevents overlapping operations from mutating active
state. The caller opens a session, establishes assumptions, starts an operation,
consumes or resumes it, and closes its owner before changing scope. Published
results remain readable after the live operation ends.

This chapter defines capture, exclusion and cleanup. The work an operation may
spend is attached to these scopes in [chapter 11](11-resources.md); the traversal
and heuristic state an operation retains is described with
[search](14-search.md) and [local search](15-local-search.md).

## Assumptions and conditioned queries

`Solver.session()` opens a single-threaded scope owner. Every operation captures the
assumption stack when called, including lazy sequences. Later pushes override earlier
pins and per-call parameters; popping restores the previous scope. The source model is
unchanged. Solve, sample, enumerate and minimize require parameters that enforce
assumptions; unsupported parameters reject non-empty pins or deductions explicitly. The
default methods of custom sessions reject scoped operations they cannot implement.

Finite source compatibility callers capture integer-definition orientations and
Boolean-fold roles in the same attachment as the source. Their optional native
views are requested only after actual heuristic admission and presolve, using the
same containing cancellation and work meter. An interrupted construction publishes
no partial view and cannot renew that allowance for search. Reusing an attachment
in a fresh invocation retains its owned metadata and creates a fresh execution scope.

## Streams and exclusive ownership

`openSamples`, `openEnumerate`, `openExactCount` and `openImprovements` return
`SearchStream` cursors. A cursor has one consumer and releases its retained state on
completion, failure or explicit close. Use `use` when stopping early. A terminal value
can remain buffered after resources release; explicit close discards buffered values.
Legacy `Sequence` APIs capture the same scope but require sequential consumption;
built-in search paths release native LP solvers before each legacy yield.

An open stream or resumable handle owns its session. Other operations, push/pop and
local-search reset throw while that owner is active. Closing the session closes its
active owner, clears scopes and retained local-search weights, and rejects later
operations. Closing is idempotent. Terminal resumable verdicts remain readable without
further work, including after close or subsequent scope changes; a closed pending handle
rejects further slices. Counters and preparation progress remain owned by the delegated
backend handle.

Finite solve, sample and optimization results retain the model's captured source
interpretation, including cancellation and refutation without an assignment. Raw
stream points and resumable result envelopes retain the same policy after their
owner closes. Qualifying a verdict preserves its assignment object; an imported
incumbent can remain an unbound point inside a qualified result. This metadata adds
no proof authority.

Session ownership wrappers preserve the underlying handle's cumulative scheduling
instructions. ALNS scheduling allowances remain distinct from its observed inner
local-search moves; bypassing bootstrap does not erase the offered instruction
charge. See [ALNS acquisition](15.4-lifetime.md#alns-incumbent-acquisition).

Resumable handles retain cancellation bound through `withLifetimeCancellation`.
Ordinary parameter cancellation bounds construction; each slice supplies its invocation
token and deadline, retaining the separate lifetime allowance.
See [resource allowances](11-resources.md) for work units and nested scopes.

## Retained state and repair

Fresh calls build fresh traversals. Resumable slices retain their trail or assignment,
RNG and restart progress. Local-search sessions retain learned weights between fresh
calls, capturing them at every published sample or incumbent and at completion. Early
stream close preserves the most recent capture. Open local-search streams and handles
also exclude searches through other sessions of the same solver, because strategy and
restart state belong to that solver.

Repair handles are a separate internal contract. Their cutoff must be monotone
non-increasing because retained objective-bound clauses survive fragment reseeding.
General session push/pop starts a fresh search and does not reuse repair machinery.

## Counting and sampling

Counting builds an isolated conditioned model. Integer domains carry the scope's pins,
holes and bounds; Boolean root pins become source unit clauses so hash extensions and
rebuilds preserve them. Exact, approximate and hybrid counting use that same model.
Accurate sampling also conditions on both call parameters and session scopes. Counting
and accurate sampling use complete finite-model machinery independently of the session's
sampling backend; local-search enumeration remains sampling with replacement.

Count bounds retain the captured source interpretation through exact, approximate
and scoped counting. The source policy is separate from the algorithm's confidence
and exactness: an exact count of a bounded interpretation does not count an
unrestricted source. Combining bounds requires matching captured policies when
both inputs are bound; an unbound input cannot establish source qualification.

Approximate counting and accurate sampling project only Boolean and finite integer
values. Real columns remain existential feasibility constraints: neither a real
interval nor multiple real completions multiply the projected count. Integer channels,
conditioned models and XOR extensions retain real declarations, resource settings and
implied/symmetry metadata. Decoded representatives retain all source coordinates,
including approximate and certified rational real values and any exact integer payload;
an augmented model's witness certificate does not certify the source projection.
An empty declared real interval proves a cell empty before witness completion.
Cell exhaustion requires complete feasibility checks, and an indeterminate real leaf
cannot establish an exact count. Polynomial algebraic assignments belong to the
[polynomial pipeline](24-polynomial-real.md), whose opaque source snapshots are outside these
`Solver` counting and sampling entry points.

## Exact count bounds

Anytime exact counting reports proven projected assignments as its lower bound and
adds all unchecked or undecided subtree mass to its upper bound. A feasibility check
replaces only the checked child's mass, so exhausting `maxChecks` partway through an
expansion retains its unchecked siblings and the deeper frontier. An `Unknown` check
retains its entire subtree in the upper bound and prevents an exact result.

Subtree products and frontier updates use exact integer arithmetic, including integer
domain widths and holes. Integer values are visited lazily through domain successors,
so a bounded check budget does not materialize a whole domain or require an `Int`-sized
value count. Published lower bounds rise and upper bounds fall.
`Count.upper == Long.MAX_VALUE` is an unbounded sentinel when the bound exceeds the
public `Long` range; saturation does not establish exactness. Exactness requires all
projected branches to be decided and a representable count.
Hash-scaled approximate estimates saturate at that same sentinel. Intersecting an
approximate interval with proven bounds retains the fallback's confidence and cannot
establish exactness merely because the interval collapses to one value.

## Theory invocation scopes

Arithmetic theories (Part IV) follow the same ownership rule as finite search:
immutable source snapshots are shared, while each solve owns a fresh invocation.
Source-strategy satisfaction and affine or quadratic optimization use the captured
source execution entry. The native result and the reconstructed source outcome are
retained in that call's completed envelope; another call cannot replace its
interruption reason or coordinates. Published exact scalars outlive the invocation;
its mutable traversal, query results and work ledger remain private. Conditioned
queries capture their pins without changing the source model, and the containing
owner closes the invocation after its operation finishes.

A mutable `SearchComponentSet` has one session construction attempt, consumed even
when attachment fails. Prepared search openings instead support independent
openings with fresh components and atom registries, and own an invocation resource
registry. Factories register scratch resources as soon as construction succeeds,
and every installed closeable component joins the same registry, deduplicated by
identity. Partial construction failure, normal completion and explicit close all
release registrations in reverse order, attempting every owner once. A construction
failure stays primary with cleanup failures suppressed onto it; ownership clears
before callbacks, so reentrant close cannot dispose an owner twice. Finite
invocations register their component peers and native resource owners the same way;
callers using resource-owning extensions must use a closeable stream when abandoning
consumption.

Conditioned linear queries retain one private prepared search and native work
ledger. Each query captures Boolean pins, exact rational coordinate bounds and a
strict source-objective cutoff; assertions retract after success, refutation,
decline or cancellation, together with their deadlines and meters. Conditioned
traversal retains its opening decision level through restarts and learned
backjumps, so its exhaustion refutes only the active condition, never the whole
source. Private auxiliary and guard names belong to one encoder installation and
are never appended to a published source assignment. [Search](14-search.md#shared-component-search)
defines the traversal floor; [resources](11-resources.md#native-arithmetic-allowances)
defines cumulative charging. Polynomial and nonlinear integer invocations follow
these rules; their specifics are in [polynomial invocation ownership](24-polynomial-real.md#invocation-ownership)
and [nonlinear integer arithmetic](23-nonlinear-integer.md).

---

Previous: [9. SMT-LIB source semantics](09-smtlib.md) · [Contents](README.md) · Next: [11. Resource allowances](11-resources.md)
