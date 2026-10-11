# Solver sessions and streams

The public session owns caller scopes and active operations. Backend state follows the
[search](search.md) and [local-search](local-search.md) contracts.

## Assumptions and conditioned queries

`Solver.session()` opens a single-threaded scope owner. Every operation captures the
assumption stack when called, including lazy sequences. Later pushes override earlier
pins and per-call parameters; popping restores the previous scope. The source model is
unchanged. Solve, sample, enumerate and minimize require parameters that enforce
assumptions; unsupported parameters reject non-empty pins or deductions explicitly. The
default methods of custom sessions reject scoped operations they cannot implement.

Counting builds an isolated conditioned model. Integer domains carry the scope's pins,
holes and bounds; Boolean root pins become source unit clauses so hash extensions and
rebuilds preserve them. Exact, approximate and hybrid counting use that same model.
Accurate sampling also conditions on both call parameters and session scopes. Counting
and accurate sampling use complete finite-model machinery independently of the session's
sampling backend; local-search enumeration remains sampling with replacement.

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
[polynomial pipeline](qfnra.md), whose opaque source snapshots are outside these
`Solver` counting and sampling entry points.

## Exact count bounds

Anytime exact counting reports proven projected assignments as its lower bound and
adds all unchecked or undecided subtree mass to its upper bound. A feasibility check
replaces only the checked child's mass, so exhausting `maxChecks` partway through an
expansion retains its unchecked siblings and the deeper frontier. An `Unknown` check
retains its entire subtree in the upper bound and prevents an exact result.

Subtree products and frontier updates use exact integer arithmetic, including integer
domain widths and holes. Published lower bounds rise and upper bounds fall.
`Count.upper == Long.MAX_VALUE` is an unbounded sentinel when the bound exceeds the
public `Long` range; saturation does not establish exactness. Exactness requires all
projected branches to be decided and a representable count.

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

Resumable handles retain cancellation bound through `withLifetimeCancellation`.
Ordinary parameter cancellation bounds construction; each slice supplies its invocation
token and deadline, retaining the separate lifetime allowance.
See [resource allowances](resources.md) for work units and nested scopes.

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

## Polynomial invocations

A polynomial pipeline request retains an immutable source snapshot and can be reused
for fresh calls. Each execution constructs its own arithmetic context, Boolean
search and CAD traversal. It exposes no resumable handle, scoped mutation or retained
refinement state. Complete exact scalar metadata remains readable after subsequent
executions or cancellation; published witnesses own only original source coordinates.
See [polynomial theory](qfnra.md) and [resource allowances](resources.md).
