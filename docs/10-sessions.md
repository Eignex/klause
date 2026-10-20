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
pins and per-call parameters; popping restores the previous scope. The source model
is unchanged. Parameters that cannot enforce assumptions reject non-empty pins
explicitly, as do custom sessions for scoped operations they cannot implement.

Optional native views, such as definition orientations, are requested only after
heuristic admission and presolve, under the same cancellation and work meter. An
interrupted construction publishes no partial view, and reusing a source attachment
in a fresh invocation creates a fresh execution scope.

## Streams and exclusive ownership

`openSamples`, `openEnumerate`, `openExactCount` and `openImprovements` return
`SearchStream` cursors. A cursor has one consumer and releases its state on
completion, failure or explicit close; use `use` when stopping early. Legacy
`Sequence` APIs capture the same scope but require sequential consumption.

An open stream or resumable handle owns its session: other operations, push/pop and
local-search reset throw while it is active. Closing the session closes its active
owner, clears scopes and retained local-search weights, and rejects later operations.
Terminal resumable verdicts stay readable after close; a closed pending handle
rejects further slices.

Finite solve, sample and optimization results keep the model's captured source
interpretation, including cancellation and refutation without an assignment. This
metadata adds no proof authority. Resumable handles keep cancellation bound through
`withLifetimeCancellation`, while each slice supplies its own invocation token; see
[resource allowances](11-resources.md) and
[ALNS acquisition](15.4-lifetime.md#alns-incumbent-acquisition) for how wrapped
handles keep their instruction charges.

## Retained state and repair

Fresh calls build fresh traversals. Resumable slices keep their trail or assignment,
RNG and restart progress. Local-search sessions keep learned weights between fresh
calls, capturing them at each published sample or incumbent. Open local-search
streams also exclude searches through other sessions of the same solver, because
strategy and restart state belong to the solver.

Repair handles are a separate internal contract. Their cutoff must be monotone
non-increasing because retained objective-bound clauses survive fragment reseeding;
session push/pop always starts a fresh search.

## Counting and sampling

Counting builds an isolated conditioned model: integer domains carry the scope's
pins, holes and bounds, and Boolean pins become unit clauses so hash extensions and
rebuilds preserve them. Exact, approximate and hybrid counting and accurate sampling
all use complete finite-model machinery on that model, independently of the
session's sampling backend.

Count bounds keep the captured source interpretation, separately from the
algorithm's confidence: an exact count of a bounded interpretation does not count an
unrestricted source, and combining bounds requires matching policies.

Approximate counting and accurate sampling project only Boolean and finite integer
values. Real columns stay existential: neither a real interval nor multiple real
completions multiply the count. Cell exhaustion requires complete feasibility
checks, so an indeterminate real leaf cannot give an exact count. Polynomial
algebraic assignments belong to the [polynomial pipeline](24-polynomial-real.md),
outside these entry points.

## Exact count bounds

Anytime exact counting reports proven projected assignments as its lower bound and
adds all unchecked or undecided subtree mass to its upper bound. Exhausting
`maxChecks` partway through an expansion keeps the unchecked siblings in the upper
bound, and an `Unknown` check keeps its whole subtree there. Subtree arithmetic is
exact, and integer values are visited lazily, so a bounded budget never materializes
a whole domain. `Count.upper == Long.MAX_VALUE` is an unbounded sentinel, not an
exact count; exactness requires every projected branch decided and a representable
count.

## Theory invocation scopes

Arithmetic theories (Part IV) follow the same ownership rule as finite search:
immutable source snapshots are shared, while each solve owns a fresh invocation. The
native result and reconstructed source outcome stay in that call's completed
envelope. Published exact scalars outlive the invocation; its traversal, query
results and work ledger stay private. Conditioned queries capture their pins without
changing the source model.

A mutable `SearchComponentSet` has one session construction attempt. Prepared search
openings instead support independent openings with fresh components and their own
resource registry. Every closeable component and scratch resource joins that
registry, and completion, close or partial failure release registrations once in
reverse order, with cleanup failures suppressed onto a construction failure. Callers
using resource-owning extensions must close abandoned streams.

Conditioned linear queries keep one private prepared search and work ledger. Each
query captures Boolean pins, exact coordinate bounds and a strict objective cutoff,
retracted afterwards with its deadline and meter. The traversal keeps its opening
decision level through restarts and backjumps, so exhaustion refutes only the active
condition, never the source; see
[search](14-search.md#shared-component-search) and
[resources](11-resources.md#native-arithmetic-allowances). Private auxiliary names
never reach a published assignment. Polynomial and nonlinear integer invocations
follow these rules; see
[polynomial invocation ownership](24-polynomial-real.md#invocation-ownership) and
[nonlinear integer arithmetic](23-nonlinear-integer.md).

---

Previous: [9. SMT-LIB source semantics](09-smtlib.md) · [Contents](README.md) · Next: [11. Resource allowances](11-resources.md)
