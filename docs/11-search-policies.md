# 11. Search policies and completion

Search policies govern restarts, safe-root inprocessing and completion of finite assignments.
They retain the scoped deductions and explanation lifetimes established by [learning](10-learning.md).

## Restarts, phases and inprocessing

A `RestartSchedule` decides when a run pops back to its root; the restart action
itself (popping the trail, forgetting, inprocessing) stays with the traversal. Each
driver owns its schedule instance. A single unbounded run is the default. The
configurable schedules, in precedence order, are:

- **Mode switching** dives without restarting until a first feasible solution, then
  alternates a stable stage of sparse Luby restarts that dives on the saved phase with
  focused stages of frequent adaptive restarts.
- **EMA adaptive** and **LBD adaptive** restart when recent learned LBD is worse than
  the long-run average, using moving averages or bounded windows respectively.
- **Luby** restarts after a Luby multiple of a strictly positive decision base.
  `BacktrackParams.lubyRestartBase = null` disables this schedule; zero and negative
  bases are rejected when parameters are constructed, including copies.

Both adaptive schedules block a restart while the trail is well above its average,
since the search is then driving deep toward a model. Learned constraints, activities
and saved phases survive every restart.

When enabled, `PhaseSaving` keeps the saved polarity of each variable, an optional
target phase (the deepest conflict-free Boolean prefix) and a rephase rotation through
target, saved, all-true, all-false and random polarities every 1000 conflicts by
default. A
mode-switching schedule pins the polarity source to its stage instead.

Inprocessing runs at safe complete roots in bounded round-robin slices over the
learned database. Vivification strengthens a clause by asserting the negations of its
literals under propagation; subsumption drops subsumed learned clauses and applies
self-subsuming resolution. Every pass must preserve the variable set, because
portfolio workers share learned clauses under stable variable ids and share the
native arena read-only; variable elimination runs once in [presolve](07-presolve.md)
instead. Seeded searches skip inprocessing, since derivations under assumption pins
would not hold globally.

### Safe roots

Root simplification and failed-literal probing require an unconditional complete
decision-level-zero root. Assumptions, seed restrictions, permanent objective
cutoffs, model blockers and externally implied root restrictions withhold both, so
root premises can be discharged without turning a conditional fact into an
unconditional clause; the structural source replacements themselves add no scope.
Both are disabled by default, and their batch and per-probe limits are explicit
execution settings.

Root simplification examines bounded slices of source and learned Boolean clauses
against permanent root facts; with no Boolean pin it has nothing to satisfy or
shorten and does not scan. Clauses touched by a new root fact are reached through a
source-occurrence index, prepared ahead of traversal or cached by ordinary
propagation, in a bounded neighbourhood walk that alternates with the ordinary scan
and builds no index inside the pass; projections without an index keep the full
source scan. Once the occurrence walks cover every root pin, the ordinary cursor skips
the rest of the source and continues through learned clauses. Positions survive
slices: learned compaction invalidates learned candidates but keeps source progress
at an unchanged root, a changed root restarts the source scan, and logical retirement
keeps learned cursor positions, checking cached candidates for liveness before use. A
completed scan waits for changed input; appended learned clauses extend its range,
and new permanent root facts or learned compaction invalidate it.

A shortened source clause enters as a permanent session-local replacement before its
watches retire; immutable source text stays available for reasons, and the session
owns the unfinished source-watch retirement until a funded slice completes it or the
session closes. A shortened learned clause keeps its imported origin. Its selection
captures a stable [retirement handle](10-learning.md#native-sat-lane) before the
replacement is admitted, an unfunded capture admits no replacement, and the pass
stages one selection at a time; retirement is authorized only after replacement
propagation and required peer publication complete, and a satisfied clause uses the
same publication boundary for its premises. Attaching a shortened binary installs
both implication directions and evaluates facts already assigned at the root; there
is no separate binary graph. Satisfied source watches retire without changing
variable IDs.

Failed-literal probing rotates through a bounded set of source Boolean and existing
integer-bound literals, unfixed integer endpoints (including columns without atoms)
and coordinates from newly examined learned clauses of two to eight literals. Each
slice snapshots its candidate spaces, visits each global candidate at most once and
creates no atoms while scanning; equality atoms decline. Bound probes are ordinary
single-bound decisions with the same undo and cancellation paths. A completed root
conflict proves the complementary unit, which is attached and propagated through
ordinary search; a proved bound enters as a session-local clause over its semantic
atom and publishes through the original integer coordinate. Funded probing precedes
cheap root simplification so proved facts can refresh binaries in the same
opportunity, and each attempt has a propagation-work ceiling within the invocation
token. Successful and interrupted probes retract to the complete root; an interrupted
probe supplies no deduction, and speculative retraction keeps the saved phases while
still notifying variable selectors.

`rootProbeImplications` lets a completed consistent pure-Boolean probe keep one newly
implied Boolean literal as a binary clause conditional on its probe literal,
independently of failed-literal units. Capture is prepaid, reads only the final trail
entries, skips a consequence already present as a direct binary reason, and admits at
most one binary per probe and 64 per search scope through ordinary learned ownership
and forgetting. Optional buffer release keeps pending alternatives, cursors and that
count; reseeding discards the alternatives and resets the count.
`rootProbeBinaryGate` limits native probing in a state without Boolean root facts or
known binary clauses to newly examined learned binaries, since one Boolean assignment
cannot start unit propagation there; a discovered live binary reopens ordinary
rotation for the rest of the scope. `rootProbeGeneralGate` is the corresponding
heuristic for general Boolean states: one initial global batch, then new short
learned clauses before global probes, with the opposite polarity of a learned
neighbourhood probe that implied nothing tried next. General constraints can
propagate without a binary, so this gate is an optional admission order, not a
propagation impossibility test. A root fact or binary hint reopens global rotation in
both gates, and scope reset clears the hints.

Completed root facts are published to shared components before traversal resumes,
through the CP publisher, so a consequence reaches peers without another private
decision level in its producer. Attachment and root publication are required cleanup
once a consequence is admitted: an interrupted root attachment keeps its queued
fixpoint and completes under the next invocation before root facts are published or a
decision is made. A pass is marked complete only after consistent publication, or a
slice that needed none; cancellation, an indeterminate peer and unfinished attachment
withhold the marker.

### Inprocessing work

The containing invocation and worker allowance pay for scans, occurrence-index
preparation, probing, attachment propagation and protected retirement, all in the
propagation-work ledger; diagnostic probe and visit counts add no second charge. Root
simplification, probing, subsumption and vivification each have a work ceiling inside
the scheduled slice's total: each pass receives the remainder after earlier passes,
capped by its class grant after debt repayment, and zero withholds the class. Atomic
copying, sorting and compaction can overspend, and the class and scheduled loop repay
their debt before admitting more work; reseeding resets cursors without forgiving
debt. Required attachment and publication still run when local ceilings stop optional
work, keeping their overspend as debt, and the containing invocation still cancels
them. Time and work cancellation keep no partial probe.

Restart cadence remains a trigger. Optional propagation-work intervals and learned
clause growth also request work at natural roots without forcing a restart;
the growth mark excludes a pass's own attachments, and a cooldown requires
non-inprocessing work after each attempted slice, restart requests included.
An optional warmup withholds slices until the requested non-inprocessing work
follows the first eligible complete root; initialization cannot fund it, and
scope reset renews its origin without forgiving debt. `inprocessingProbeInterval`
spaces optional probe slices by non-inprocessing work, the first eligible slice
needing none; buffer release keeps that clock and reseeding renews it. Probe,
maintenance, publication and cleanup work cannot fund the next slice. Required
attachment and publication, and funded continuations of retained candidates,
source-watch retirement and selected requests, run ahead of these optional gates;
a continuation receives one root-class grant and starts no new scan.  Class rotation
keeps a cheap pass from taking every slice. Triggers, cooldown and warmup are off
by default, and the additional ceilings are unlimited unless configured.

Scan positions, residual candidates, probe cursors, vivification batches and
subsumption preparation survive suspension; the subsumption occurrence window is
bounded and advances round-robin. Compaction invalidates root indices through their
mapping generation and other pass snapshots through the learned revision, without
changing source variable IDs. Pass buffers are counted once by identity in the
retained-memory observation, as modeled owners rather than a heap measurement.
Optional preparation can be discarded under memory pressure, but an interrupted root
attachment or pending peer publication protects its buffers until the consequence
completes. Reseeding and closing detach pass buffers.

Per-class attempted, eligible, shortened, unit, stopped and work counters diagnose
applicability; probe outcomes separate a consistent fixpoint, a completed refutation
and an interruption. Attachment, retirement and continuation work are subsets of
class work, which overlaps containing propagation work, and per-class and slice
latency histograms are observations, not a pause bound. These counters select no
adaptive score or default threshold, and missing learned-lifecycle or family
attribution does not establish that a pass was useless.

## Theory completion

Arithmetic owners complete or refute the coordinates left by finite search.
[Theory services](17-theories.md) define their fragment-specific checks.

A component's partial consistency check or relaxation witness does not accept a
complete source assignment: every participating owner must finish its required check
before publication. [Theory contracts](17-theories.md) define the fragment-specific
checks and evidence. Difference graphs, exact linear systems and polynomial completion
keep their own state while sharing the traversal's source facts and decision lifetime.
Integer integrality and finite factors cannot be dropped to obtain an arithmetic
witness.

Exact linear components wait for finite-owned coordinates before proposing splits
or accepting completion. Finite publications supply scoped bounds and fixed values
while the finite owner keeps hole membership, so an arithmetic point outside a source
value set is withheld rather than accepted on its bound hull. Retraction invalidates
both peers' scoped outcomes.

Difference components can imply comparison guards from checked active source paths;
these carry clause-form reasons and enter the shared Boolean fixpoint. See
[difference proofs](17-theories.md#difference-logic). Open quadratic integer optimization branches
on exact wide predicates and prunes against certified bounds and checked incumbents,
without turning objective cutoffs into infeasibility conflicts. See
[nonlinear integer quadratic objectives](19-nonlinear-arithmetic.md#quadratic-objectives).

### Selected theory encoders

Prepared factor encoders supply the full relations used by shared arithmetic
components; admission and component construction consume the same selected capture.
Variable ownership is independent of factor participation: finite declarations retain
finite state and choices, while a propagator and encoder can consume one factor
together. Component descriptors distinguish primary `factorIndices` from overlapping
`participatingFactorIndices`, retaining one authoritative coordinate owner.

Linear relations keep their conjunction and disjunction structure, row activators,
strictness and original factor attribution. Private real coordinates stay in the
combined exact model and are stripped before publication. Polynomial installation
conjoins factor relations with all private auxiliary definitions, and finite integers
embedded there are private reals fixed from finite state. Relaxation selection cannot
omit an encoder's enforcing relation, and the original source checker stays required.
A finite registration skips duplicate original point checks only for factors whose
mandatory arithmetic peer installs a complete enforcing encoding.

### Arithmetic coordinate agreement

Prepared registration collections with several arithmetic completion groups install
`SourceCoordinateAgreementComponent` after their ordinary participants. Each group
must implement `SourceCoordinateAgreement`; unsupported groups leave the combined
result indeterminate. Standard preparation retains one combined arithmetic owner and
installs no agreement participant.

The exact-linear adapter exchanges normalized source bounds through the neutral atom
registry, and peers enforce the same predicates at the shared decision level. Each
source coordinate has one declared owner. After fragment checks, agreement validates
the immutable typed snapshot against each peer's current rows and conditions; it
never combines private fragment points, and a rejected point does not refute other
completions. Exact integers and rational reals are admitted; unsupported
contributions stay indeterminate rather than rounded. Agreement supplies no general
theory combination or bound authority and opens no fresh allowance.

### Contributed model ownership

Components contribute immutable values through typed keys, one owner per key, and
the assembled model reads them without copying. Exact source integer and real keys
preserve arbitrary-precision and rational coordinates; extensions can add open keys
whose payloads must be immutable. See
[Typed contributions and mappings](23-api.md#typed-contributions).

## Primal heuristics and statistics

Primal heuristics propose incumbents inside complete search and supply no proof
authority.

### Root primal proposals

On mixed models, the root LP relaxation also proposes incumbents. Nearest rounding,
directed upward and downward rounding of the retained LP point, a peer worker's
discrete assignment and a feasibility pump each produce discrete targets. Every
distinct target is pinned through the arm's own propagation, and the leaf checks of
relations whose selected propagation is inert must accept the pinned point, so it is
a search leaf. It must then satisfy the caller's assumptions and complete its reals
through the real-leaf owner. Only a completed leaf that strictly improves the current
cutoff becomes an incumbent; a declined one lets the probe try its next target.

The opt-in `lbTreeSearch` probe extends this to a private finite trail with exhaustive
fractional and value alternatives, trying the nearer side of the LP value first. It
returns its own propagated leaves only and exports no optimum, refutation, bound or recession
claim. The `lp-lbtree` portfolio recipe can enable it.

Root primal work charges the containing root ledger and solve-wide `NodeBudget`, and
a fallback solve receives only the remaining allowance.

### Monotone clausal primal probe

Pure Boolean clausal optimization with at most 64 positive-cost variables can guide
search through greedy feasibility repairs when those variables occur only
positively in hard clauses. The probe runs once before ordinary optimization and is
skipped behind an existing shared incumbent or within repair fragments.

A private satisfaction repair first finds a feasible assignment, then turns off any
cost variable whose removal alone keeps every hard clause satisfied. Trials then try
to turn off the remaining cost variables in descending weight order through a clause
walk with penalty escalation, restoring the previous feasible assignment on failure.
Trials run on a projected model reduced by unit propagation and bounded Boolean
elimination, and lift their result back. Private infeasible verdicts contribute no
outer proof. The search puts a proposal's values first until a decision it led conflicts; the
proposal does not become an incumbent.

The probe's work is charged to its arm and the solve-wide move allowance, within at
most half the remaining time and 5 seconds.

### Statistics

Counters describe observed work, never proof coverage.

Local-search candidate refutations distinguish required checks before completion,
the completion or neighborhood provider, and required checks after its witness.
Their sum belongs to the existing candidate-refutation total. A rejected candidate
does not refute the unrestricted source. Missing historical stage counts do not
identify a rejection stage; the CLI emits the split only when it accounts for the
whole refutation total.

`propagations` counts deductions from completed finite fixpoints, excluding decision
and root pins; `propagationWork` also counts dispatch, watcher, scheduling and reason
visits across undo. Root figures are subsets of totals, and fixpoint time excludes
some counted reason scans. Native CP and shared first-UIP outcomes are reported
separately: a nonzero non-asserting count points at a reason that did not support
first-UIP learning and identifies chronological fallback, while zero does not
establish explanation coverage. Native learned-constraint counts include integer
rows, whose terms do not count as Boolean learned literals; `learningIntegerSessions`
counts sessions with integer-row learning enabled, independently of learned objects.
Missing metrics stay absent, including root theory results that never enter
traversal. Live solve snapshots include presolve LP work once and keep the observed LP
snapshot when no aggregation is needed, and directional branch counters are copied
into snapshots, isolated from later observations. Complete open-theory search adds
`smtCompleteSearch*` observations of assignment depth, LP checks and stages; they stay
absent until observed and overlap, so they are not a work total. The CLI also exports
native CP work and time, with root figures as subsets of the totals.
[SearchStats](../klause/src/commonMain/kotlin/com/eignex/klause/solver/result/SearchStats.kt)
and [CLI statistics](24-cli.md#statistics) define the exported fields.

| Field | Meaning |
|---|---|
| `propagations` | Finite Boolean, singleton, bound, hole and survivor-set deductions reported by completed search fixpoints; excludes decision and root pins |
| `learned` | Learned consequences observed by the traversal, including native CP learning |
| `caNotApplicable`, `caNonAsserting` | Native CP analyses declining a usable or asserting consequence; exported by every CLI format |
| `propagationWork` | Monotonic dispatch, literal, watcher, level, scheduling and linear-term/reason visits, including work across undo |
| `rootPropagationWork`, `rootPropagationMs` | Initialization root-fixpoint work and time, including private preparation |
| `propagationMs` | Fixpoint elapsed time; some counted reason scans are outside it |
| `openAssertingConflicts` | Shared first-UIP analyses yielding an asserting backjump |
| `openNonAssertingConflicts` | Sound shared conflicts retaining chronological fallback |
| `openReductionNs` | Total shared reduction selection and watch-rebuild time; zero when the cap is off |

---

Previous: [10. Learning](10-learning.md) · [Contents](README.md) · Next: [12. Local search](12-local-search.md)
