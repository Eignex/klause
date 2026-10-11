# Search and propagation

Shared traversal and finite propagation implement the ownership boundaries in [system
architecture](../architecture.md). Caller scopes belong to [sessions](sessions.md);
local-search state and portfolio policy are defined in [local search](local-search.md)
and [portfolios](portfolio.md).

## Shared component search

[solver.search](../../klause/src/commonMain/kotlin/com/eignex/klause/solver/search/)
owns decisions, levels, branching, retraction, complete checks, restarts and model
assembly. CP and theory components contribute residual state and reasons through the
same protocol. The session exchanges Boolean literals, semantic integer bounds and
opaque theory decisions rather than native CP domains.

Fractional source integers split at exact floor/ceiling thresholds. CP decisions use
Long values; open theories use arbitrary-precision thresholds. Stable complementary
atoms must exist before first-UIP learning. Boolean atom names belong to one immutable
source root; changing root meaning requires a fresh session. Unnameable conflicts remain
chronological and unexplained.

Complete assignments use source-keyed values from each owner. CP-learned Boolean clauses
remain natively owned by CP while their shared consequences reach theory peers. The
shared store does not duplicate the CP pin.

Open linear arithmetic prepares immutable source row addresses, forms and Boolean
dependencies once per component. A resolved comparison remains asserted through
its LP scope; deeper checks reuse it until retraction removes that scope. Root
checks can substitute newly fixed columns. Disequalities re-evaluate their justified
direction against active bounds, and complete witnesses still check source rows.

## Finite propagation

Fresh presolve rebuilds retain the originating cancellation token and accounting
destination. See [presolve cancellation and accounting](presolve.md) for ownership,
probe-count scope and cooperative deadline limits.

[PropagationSession](../../klause/src/commonMain/kotlin/com/eignex/klause/propagation/PropagationSession.kt)
owns the finite-domain trail and fixpoint. Sessions over one immutable model can share
its propagation projection and occurrence indexes. Incremental payloads, scratch arrays,
watchers and failure contexts belong to each state. Failure clauses and premise-variable
sets use separate state storage, leaving reversible payloads intact; each fire clears
its previous failure, and undo discards pending failure context. The native SAT lane
keeps its own state and leaves general CP failure storage unallocated.

`FactorExecutionCapabilities` inventories each built-in family's execution routes.
`SOUND_FILTERING`, `BOUNDS_FILTERING` and `DOMAIN_FILTERING` describe deduction
mechanisms; they do not promise full consistency at every size or resource limit.
Complete finite checks pin the full assignment and run a completed CP fixpoint.
Local-search invariant satisfaction is a separate check from its graded score.
Continuous arithmetic and floating objective scores require the existing source
publication checks; a zero heuristic score is not an exact certificate.

Gaussian XOR and difference-system helpers deliberately omit local-search scoring: their
retained source rows enforce the assignment. Symmetry helpers also omit scoring because
source witnesses need not satisfy a chosen CP representative. The finite difference
system is posted after presolve only when its fragment has a guarded edge and an edge
between two unfixed root integer columns. Edges to zero or root-fixed columns act as
unary bounds and retain their original propagators. An unconditional edge between
unfixed columns can still join unary guarded bounds. Objective-bound overlays
deliberately omit CP propagation. Every inert built-in role carries a reason. A sound LP
family can decline emission under feature, resource or domain gates, and omitted rows
weaken the relaxation.

Custom factors retain the `Propagator` and `Invariant` extension paths. A custom
`Propagator` without an `Invariant` explicitly takes the propagation-only path; a custom
`Invariant` supplies local checking and scoring through its interface contract.
Structural-only custom factors refuse CP and local-search execution with route-specific
errors. Custom implied linear rows remain available to LP relaxation independently of
either execution interface.

Shared table preparation transfers no-op verdicts only under matching contiguous column
domains. Each private propagation projection retains one preparation context across its
chunks. MDD preparation sharing includes the initial and accepting states; value
relabeling prepares an index over the relabeled transition array. Reversible live
tuples, diagram reachability and all assignment-dependent payloads remain state-owned.

### Branching and global deductions

Finite branching receives the native conflict's full variable and factor attribution,
including implication-graph variables for learned conflicts. VSIDS bumps each implicated
variable once per conflict, including a failed decision already in that set. Conflicts
cascading from a native learned assertion also update activity and failure counters;
they have no fresh branch decision to blame. Successful CP fixpoints deliver newly
reported deductions to propagation-aware selectors in both satisfaction and optimization
search. Phase saving captures determined values before native trail
retraction, retaining propagated polarities as well as decision polarities across
backjumps and restarts.

Boolean-only models journal each pin's variable id and tag without integer-state padding.
Mixed finite models retain aligned integer-state columns for domain retraction.

Dom/wdeg initializes from the session's prepared occurrence indexes. Failure weights and
its heap belong to the solve, retain conflict bumps across restarts, and apply bumps
received before the first selection once during initialization. Fresh selectors isolate
solves that share a projection.

Cumulative edge-finding runs in both time directions over fixed durations, demands and
capacity. Reversed deductions tighten latest starts and cite the historical integer
bounds and definitely-present task premises. Arithmetic ranges that cannot be
represented safely decline the reversed pass.

### Explanation lifetime

Propagators supply sound source reasons for deductions and conflicts. An assignment's
undo lifetime can be deeper than the effective level of its reason: Boolean pins use the
deeper of that level and the current decision depth so an asserting backjump retains its
consequence. Reified arithmetic skips reason construction when a settled relation agrees
with its assigned indicator. New indicator pins and opposing assignments retain the pin
and conflict explanation protocol. Single-sided linear indicator deductions record the
existing lazy linear payload during trailed search. Shared Boolean premise export
resolves this payload into historical literals before traversing its proof graph.
Conflict analysis resolves it against the bounds at the pin, rather than later domains;
untrailed and two-sided deductions retain eager explanations. Linear bound propagation
constructs a reason only when the proposed bound tightens the live domain; unchanged
bounds skip eager explanations and lazy payload allocation. Single-variable equality and
disequality indicators over root domains contained in `{0, 1}` propagate as direct
channels: an indicator fixes its integer value, and a fixed integer pins the indicator.
Wider domains retain the general linear propagator.

Factor cores traverse the full reason graph with session-owned dense-id scratch. The
traversal clears visited ids between extractions and reads learned native clause
variables directly, retaining the same factor-core membership across restarts and
learned-clause compaction.

The native CP analyzer and shared first-UIP analyzer have distinct counters and
ownership. A shared conflict with usable reasons can assert and backjump. Sound
resolvents that cannot assert retain chronological fallback. Root refutations, unusable
explanations and native CP analysis are outside the shared asserting/non-asserting
counters.

### Table explanations

Table explanations use build-local caches for historical domains, carve positions and
materialized literals. These caches do not cross propagation or undo. Ground supports
use membership/value-set checks, and sparse root members avoid walks over large
unconditional holes. Support gathering can stop after all live values of a ground column
are covered, while every tuple is still checked for feasibility. The reversible live
prefix is committed once per completed sweep, including empty-prefix conflicts; sibling
branches restore filtering from trailed state.

Construction-time root propagation folds unary Table restrictions whose expanded
supports total at most 4096 values. It intersects a sorted survivor set with the current
domain without walking that domain's span, so local search samples from the same
unconditional restrictions. Larger unary relations and multi-column tables retain
deferred preparation. Search-time filtering retains STR2 explanations.

LP explanations cite selected source bound witnesses and recursively expanded row,
fixing and cutoff premises. Current CP propagation and source LP conflicts do not
include an optional exact-theory row-propagation scanner or explanation weakening pass.
See [LP consumer contracts](../lp/architecture.md#consumers-explanations-and-cuts).

## Learned-clause retention

The shared learned store has its cap off by default. When capped, reduction runs at
restart boundaries after pending attachments are drained. Glue clauses, units and
clauses used for propagation since the last reduction survive. Remaining capacity keeps
lower LBD first, then older insertion for ties. Protected clauses can leave the retained
count above the nominal cap.

Selection uses a Boolean drop mask. A bounded LBD histogram applies when the maximum
score is at most 4096 and at most four times the eligible count; sparse ranges use the
packed sort with the same ordering. This mechanism preserves the retention rule. The
native CP store keeps its own reduction policy.

See
[SearchSession](../../klause/src/commonMain/kotlin/com/eignex/klause/solver/search/SearchSession.kt)
and
[ClauseDb](../../klause/src/commonMain/kotlin/com/eignex/klause/backtrack/ClauseDb.kt).

## Open real arithmetic

The `theory.qflra` component owns exact real feasibility in shared search. [Input
semantics](../formats.md#smt-lib) defines supported source syntax and output.

The exact real component uses a difference graph when every arithmetic row is a
one-column bound or a two-column difference with an integral normalized bound. Boolean
guards select active edges. With `n` graph vertices, scaling bounds by `n+1` and
subtracting one from strict edges preserves every simple cycle's sign, including
zero-weight strict cycles. Feasible graphs supply rational potentials; witnesses still
pass the complete source-row check. Infeasible graphs retain the shared LP route for
conflict explanations and learning.

Active source rows remain asserted in the shared LP scope whenever that owner is needed.
Floating LP solves maintain the basis on graph-feasible states; their numerical output
supplies no verdict. Graph feasibility replaces exact LP point certification below the
root and for complete Boolean assignments at the root. A complete graph witness passes
source validation before numerical LP work, so pinned local-search completions need no
floating guide.

Fully pinned difference roots defer LP construction until graph witness validation
declines or a later source-bound decision requires a shared LP scope. If every Boolean
is published at the root, initialization waits for those publications to reach the
theory before checking the pinned arithmetic.

Pure-real local-search completions keep their source rows and Boolean pins, skipping
source presolve before the selected exact real theory certifies the leaf. Mixed
integer/real completions retain source preparation. Bounded potentials from an earlier
feasible graph seed relaxation, which must still settle every active edge. The partial
root relaxation and inconclusive floating updates retain the full shared LP solve.

Graph admission is capped at 10,000 real variables and 100,000 edges. Larger models,
fractional normalized bounds, unsafe Long magnitudes, disequalities, private
disjunctions, Boolean arithmetic beyond shared clauses/cardinalities, and mixed
integer/real models retain the exact LP route. Overridden LP certification policies
retain the LP route because graph evidence has no LP certifier identity. No finite CP
domains are created by this graph.

## Statistics

| Field | Meaning |
|---|---|
| `propagations` | Finite Boolean, singleton, bound, hole and survivor-set deductions reported by completed search fixpoints; excludes decision and root pins |
| `learned` | Learned consequences observed by the traversal, including native CP learning |
| `caNotApplicable`, `caNonAsserting` | Native CP analyses declining a usable or asserting consequence; exported by every CLI format |
| `propagationWork` | Monotonic dispatch, literal, watcher, level and linear-term/reason visits, including work across undo |
| `rootPropagationWork`, `rootPropagationMs` | Initialization root-fixpoint work and time, including private preparation |
| `propagationMs` | Fixpoint elapsed time; some counted reason scans are outside it |
| `openAssertingConflicts` | Shared first-UIP analyses yielding an asserting backjump |
| `openNonAssertingConflicts` | Sound shared conflicts retaining chronological fallback |
| `openReductionNs` | Total shared reduction selection and watch-rebuild time |

A nonzero non-asserting count is a reason to capture the conflict and inspect component
reasons. Zero does not establish complete factor reason coverage. Missing metrics remain
missing, including root theory refutations that never enter shared search. Ratios of
visit work to fixpoint time are coarse because their scopes differ.

`openReductionNs` is zero when the cap is off or no reduction pass is needed.

## Monotone clausal primal probe

Pure Boolean clausal optimization with at most 64 positive-cost variables can seed
incumbents through greedy feasibility repairs when those variables occur only positively
in hard clauses. A private satisfaction repair first obtains a feasible assignment with
unaccepted cost variables true. Caller and baked-root pins form its stable scope.
Temporary cost and pure-literal pins enter private projection; source reconstruction
restores their original coordinates.

Before trial repair, free true values become false when that single change preserves
every hard clause. A retained clause walk tries cheap cost values in descending weight
order. Unit closure fixes caller, root and cost assumptions; focused moves change only
free variables. Clause penalties increase when no candidate move improves the weighted
violation and survive between trials. Each trial permits at most one move per source
variable, retains its assignment and random state across slices, and restores the
previous feasible assignment on failure. Moves spend the caller's shared node budget at
the existing local-search rate; clause and assignment visits charge the owning arm's
work.

The initial feasible model and remaining costly values use private projected
satisfaction repairs. Unit propagation removes fixed literals and satisfied clauses. Two
bounded Boolean elimination passes reuse the presolve mechanism and its source
reconstruction records, then surviving variables receive dense private coordinates. Each
projected trial owns a fresh satisfaction session that resumes across slices. Lifting
restores fixed source values, expands surviving coordinates and applies elimination
records in reverse order. Projection, construction and search work remain charged to the
arm. The containing arm owns overspend debt; private sessions enforce their current
slice allowance without repaying the same debt again. Work before a private slice is
included in the containing arm's cumulative charge. The original model and its ordinary
search remain intact. Private infeasible verdicts contribute no outer proof.

Only feasible trials retain their cheaper cost assumptions. Clause-local polishing
removes unnecessary costly values. Each polishing activation has a 100 ms cap and
retains the whole-solve deadline; it can finish a feasible model after its search slice
expires. The composed verifier checks the full original proposal before publication. A
completed proposal can be verified and delivered while the arm repays work debt, which
continues to block further search. Verification retains its 100 ms cap and the
whole-solve deadline.

Private satisfaction uses the SAT preset with inprocessing and target capture disabled
and starts fresh Boolean decisions with false. Initialization and cumulative work are
charged to the owning arm. The private clausal walk prices literal scans, clause
bookkeeping and assignment visits at the propagation work rate. Each move still spends
the solve-wide move allowance; its literal work is not also priced as a generic
constraint local-search instruction. The complete probe receives at most half the
remaining solve allowance in active execution time, capped at 5 seconds. Trials retain
the caller's solve-wide node budget and the whole-solve cancellation. The probe runs
once before ordinary optimization and is skipped behind an existing shared incumbent or
within repair fragments.

Search counters distinguish eligible probe starts, trial starts, returned models,
completed improving proposals, and proposals verified and installed as arm incumbents.
The CLI emits these counters together when a probe starts, including zero counts for
stages it did not reach. Outcome counters distinguish private refutations from
incomplete stops without a model. These counters describe heuristic progress and carry
no proof.

## Contributed model ownership

Components contribute immutable values through typed keys, with one owner per key.
The assembled model captures its map and each typed key's ownership hook at publication;
reads retain the associated value type without copying. Exact source integer and real
keys preserve arbitrary-precision and rational coordinates alongside the compatibility
keys. Opaque legacy values remain borrowed read-only. Extensions retain open keys for
arrays, functions and native theory values; their captured payloads must be immutable.
See [API migration and mappings](../api.md#typed-contributions-and-migration).
