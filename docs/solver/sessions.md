# Solver sessions and streams

A session groups operations under caller assumptions. A stream or resumable handle
keeps one operation alive across consumption or slices. The session owns those
scopes and prevents overlapping operations from mutating active state.

The caller opens a session, establishes assumptions, starts an operation, consumes
or resumes it, and closes its owner before changing scope. Published results remain
readable independently of the live operation. This page defines capture, exclusion
and cleanup; backend state follows [search](search.md) and [local search](local-search.md).

## Assumptions and conditioned queries

`Solver.session()` opens a single-threaded scope owner. Every operation captures the
assumption stack when called, including lazy sequences. Later pushes override earlier
pins and per-call parameters; popping restores the previous scope. The source model is
unchanged. Solve, sample, enumerate and minimize require parameters that enforce
assumptions; unsupported parameters reject non-empty pins or deductions explicitly. The
default methods of custom sessions reject scoped operations they cannot implement.

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

## Counting and sampling

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
[polynomial pipeline](../theories/polynomial-real.md), whose opaque source snapshots are outside these
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

## Theory invocation scopes

Theory services can retain immutable source snapshots while each solve owns a fresh
invocation. Published exact scalars outlive that invocation; its mutable traversal,
query results and work ledger remain private. Conditioned queries capture their pins
without changing the source model. The containing owner closes the invocation after
its operation finishes.

Prepared search openings own an invocation resource registry. Factories register
scratch resources as soon as construction succeeds, and every installed closeable
component joins the same registry. Ownership is deduplicated by identity. Partial
construction or installation failure releases successful registrations in reverse
order; normal completion and explicit close use that order too. Cleanup attempts
every owner once, including after a cleanup failure. A construction or initialization
failure stays primary, with distinct cleanup failures suppressed onto it; without a
primary failure, the first cleanup failure is thrown. Ownership clears before
callbacks, so repeated and reentrant close cannot dispose an owner twice.

Finite satisfaction and optimization invocations also register their additional
component peers and native resource owners. Closeable streams release them on
completion, failure and explicit early close. Legacy sequence yields retain their
existing native LP release points; callers using resource-owning extensions must use
a closeable stream when abandoning consumption. Factories passed through the legacy
finite component callback must clean up allocations they cannot return. Prepared
factories instead register partial allocations through their opening owner.
Published models and exact coordinates remain owned snapshots after cleanup.

Internal conditioned linear query owners retain one private prepared search and
native work ledger. Canonical CP projection, its immutable propagator indexes and
theory fragments and completed exact-linear source factories remain prepared
when an interrupted installation needs fresh mutable components. Every fresh
component set owns separate propagation, clause, arithmetic and atom-registry state.
Each query captures Boolean pins, exact rational coordinate
bounds and a strict source-objective cutoff. Assertions retract after success,
refutation, representation decline or cancellation; nested descriptor scopes add
conditions without changing the source. Invocation deadlines and meters retract
with their conditions. Check and decision allowances belong to the containing
owner, and a sibling query cannot renew them. Closing disposes the invocation registry;
published witness coordinates remain owned by their samples.

Conditioned traversal retains its opening decision level through restarts and
learned backjumps. Its exhaustion refutes only the active condition. The source
decomposition is supplied by preparation, and query owners issue no whole-source
proof. [Search](search.md#shared-component-search) defines that traversal floor;
[resources](resources.md#native-arithmetic-allowances) defines cumulative charging.

[Polynomial invocation ownership](../theories/polynomial-real.md#invocation-ownership) defines that adapter's
fresh-call and conditioned-query lifetimes. [Resource allowances](resources.md)
owns cumulative scope composition.

[Nonlinear integer requests](../theories/nonlinear-integer.md) retain immutable
source formulas and create fresh execution state for each call. Original Boolean
pins and exact integer windows are captured without changing that source. Refutations
cover the actual supplied scope; a neighborhood failure cannot refute its parent.
