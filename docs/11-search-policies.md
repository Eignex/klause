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
self-subsuming resolution. Every pass must preserve the variable set, because portfolio workers share learned
clauses under stable variable ids and share the native arena read-only; variable
elimination runs once in [presolve](07-presolve.md) instead. Seeded searches skip
inprocessing, since derivations under assumption pins would not hold globally.

Root simplification examines bounded slices of source and learned Boolean
clauses against permanent root facts. An empty Boolean pin trail withholds the scan;
without a pinned literal it cannot satisfy or shorten a Boolean clause. Clauses
affected by a new Boolean root fact can be reached through a source-occurrence index
prepared ahead of traversal or cached by ordinary propagation. This bounded
neighborhood alternates with the ordinary clause scan,
retains its pin and occurrence positions across slices, and builds no index
inside the pass. Pin and occurrence walks admit at most 8,192 entries per slice
even with an unlimited class grant. Projections without an available index retain
the ordinary scan. An indexed candidate at the ordinary cursor advances both walks;
unchanged prefixes are not examined twice. Retired and non-clause source entries
do not defer the remaining literal window. Once available occurrence walks cover
every root pin, the ordinary cursor skips the remaining source suffix and continues
through learned clauses. Missing indexes retain the full source scan. Learned
compaction invalidates learned candidates while preserving stable source candidates
and source scan progress at an unchanged root. A changed root restarts the source
scan after learned compaction. Logical retirement preserves both root passes'
learned cursor positions; cached learned candidates are checked for liveness before
their payload is used. The separate physical mapping generation invalidates indices
after compaction or slot reuse.
A shortened
source clause enters as a permanent session-local replacement before its watches
retire; immutable source text remains available for reasons. The propagation session
owns unfinished source-watch retirement
after replacement admission. Pass reset or optional buffer release retains that record;
a funded slice retires its watches before admitting another source replacement.
Session close releases it. Satisfied source watches retire without changing variable IDs.
Learned selections capture a stable retirement handle before replacement admission;
an unfunded capture retains the candidate and admits no replacement. The root pass
stages one inactive selection at a time. Replacement propagation and required peer
publication complete before a funded root slice authorizes retirement. A satisfied
clause uses the same publication boundary for its premises. Authorized requests stay
kernel-owned through optional buffer release. Abandoned inactive captures retain a
small cancellation owner until cancellation is funded or their scope is released.
Caller metadata and kernel requests share a finite retained allowance bounded by
the configured memory scratch envelope and any learned-memory soft limit. Existing
candidate literal buffers remain part of the pass memory observation. The pass
observes its lifetime controller even without a request; active capture and protected
continuation handles protect its scalar and reference ownership.
Learned replacements retain their imported origin. Physical compaction protects live and
historical reasons and removes obsolete watches; attaching a shortened binary installs
both implication directions and evaluates facts already assigned at the root. There
is no separate stale binary graph or whole-graph rebuild.

Failed-literal probing alternates a bounded rotating set of source Boolean
and existing integer-bound literals with coordinates from newly examined learned
clauses of two to eight literals. The global rotation alternates existing literals
with unfixed integer endpoints, including columns without existing atoms. Separate
cursors preserve endpoint progress when the atom registry grows. Each slice
snapshots both candidate spaces and visits each global candidate at most once;
scanning creates no atoms. A completed endpoint conflict names only the complementary
unit it proves; registry and threshold-index growth enter the containing work and debt.
Equality atoms decline. Bound probes use ordinary single-bound
decisions and discard implied-result payloads, retaining the same undo and cancellation
paths. A proved bound enters as a session-local clause over its semantic atom and
publishes through the original integer coordinate.
Permanent source replacements supply the same bounded priorities; their clauses and
ownership remain protected. A priority supplies no consequence without completed propagation.
The learned cursor advances without replaying an exhausted unchanged neighborhood;
compaction invalidates its indices. Preparation and retained literal storage share the
probe class allowance and memory observation. Funded probing precedes cheap root
simplification so proved facts can refresh binaries in the same safe-root opportunity. Each attempt has a propagation-work ceiling within
the invocation token. A completed root conflict proves the complementary unit, which is attached and
propagated through ordinary search. A completed consistent pure-Boolean probe can retain
one newly implied Boolean literal as a binary clause conditional on its probe literal.
`rootProbeImplications` controls this capture independently of failed-literal units.
`rootProbeBinaryGate` restricts Native probing without Boolean root facts or known
binary clauses to newly examined learned binaries. With no binary clause, one Boolean
assignment cannot start unit propagation in an unassigned clausal state. A discovered
live binary enables ordinary rotation for the rest of the scope, including after
optional buffer release or retirement. General propagation retains ordinary rotation.
An exhausted unchanged neighborhood does no further scanning, and preparation alone
does not start the optional probe interval. Scope reset clears the binary hint;
required consequence publication retains its separate eligibility.
`rootProbeGeneralGate` applies a separate heuristic to Boolean General states without
root facts or known binaries. One initial global batch uses the ordinary probe and
containing grants; later slices examine new learned clauses of two to eight literals
before admitting global probes. An unfunded slice retains that initial opportunity,
and optional buffer release preserves its exhaustion until scope reset.
If a completed learned-neighborhood probe propagates no additional Boolean literal,
its opposite polarity precedes the next neighborhood candidate under the same batch
and work ceilings. This optional literal survives an unfunded slice and buffer release;
scope reset or a root fact or binary hint discards it. A mirrored probe does not
schedule another mirror.
General constraints can propagate without a binary,
so this admission is optional rather than a propagation impossibility test. Integer
endpoint rotation retains its ordinary policy. A root fact or binary hint reopens
global rotation; exhausted preparation does not delay new learned candidates by the
probe interval. Required implication publication keeps its independent eligibility.
Disabling it skips capture reservations and cancels a retained optional candidate under
a funded probe visit; the scope admission count remains unchanged.
Capture is prepaid before propagation and reads at most the final two trail entries.
After rollback, a funded lookup skips a matching direct binary reason and the bounded
history prevents repeated admissions. A direct or duplicate final consequence yields
to the retained earlier consequence without another probe. At most one binary enters
per completed probe and 64 enter per search scope;
the 64-key history and pending alternatives share the probe class and retained-memory
grants. Unfunded admission resumes at a fresh safe root without another probe.
Optional buffer release drops history and neighborhood arrays while preserving the
pending alternatives, primitive cursors and admission count. Learned remapping invalidates
the direct-reason hint, without changing the proved literals. Reseeding discards
the pending alternatives and resets that count. Admitted binaries use ordinary
learned ownership, forgetting and required attachment completion.
Completed root facts are published to shared
components before traversal resumes. Successful and interrupted probes retract to the
complete root; interruption supplies no deduction. Speculative retractions preserve
the saved search phases while retaining ordinary unassignment notifications for
variable selectors. Clause scans and probes enter the existing inprocessing and
propagation work counters. Cursors survive suspension and
reset on reseeding; learned snapshots retain their physical mapping generation.

Both root passes require an unconditional complete decision-level-zero root. Assumptions,
seed restrictions, permanent objective cutoffs, model blockers and externally implied
root restrictions withhold these passes. Root premises can therefore be discharged
without turning a conditional fact into an unconditional clause. The structural
source replacements themselves introduce no additional scope. Probing and root
simplification are disabled by default; their batch and per-probe limits are explicit
execution settings.

The containing invocation and worker work allowance includes scans, occurrence-index
preparation, probing, attachment propagation and protected retirement. Scans enter
the propagation-work ledger; diagnostic probe and visit counts add no second charge.
Each of the four classes has a separate work ceiling inside the scheduled slice's
total ceiling. Each pass receives the containing remainder after earlier passes;
its remaining optional work is the smaller of that remainder and its class grant
after debt repayment. Zero withholds that class.
`inprocessingProbeInterval` spaces optional probe slices by non-inprocessing work
while cheap maintenance keeps its shared root schedule. The first eligible slice
needs no preceding interval. Probe, maintenance, publication and cleanup work cannot
fund the next slice; pending funded continuations resume before this optional gate.
Buffer release preserves the interval clock, and reseeding renews its origin. Completed atomic copying, sorting and
compaction can overspend; the class and scheduled loop repay their respective debt
before admitting more work. Separate containing and per-class debt-added and
debt-repaid counters retain those charges without adding work. Reseeding resets
cursors without forgiving that debt.
Time and work cancellation retain no partial probe.
Attachment and root publication are required cleanup once a consequence is admitted:
local ceilings stop later optional work and retain the cleanup's overspend as debt.
The containing invocation still cancels them. An interrupted root attachment keeps
its queued fixpoint and completes under the next invocation before publishing root
facts or making a decision. Root publication retains its bounded scan across slices.
It retains the CP publisher so a consequence reaches peers without creating another
private decision level in its producer. Passes receive a completion marker after
consistent publication, or after a slice that requires no publication. Cancellation,
an indeterminate peer and an unfinished attachment withhold that marker. The marker
records completion without running another class slice or granting more work.

A completed root scan waits for changed input. Appended learned clauses extend its
remaining range without replaying unchanged sources; new permanent root facts or
learned compaction invalidate the completed range. Partial candidates retain their
position until admission or rejection.
Root scan positions and residual candidates, probe cursors, vivification replacement
batches and subsumption preparation survive suspension. The subsumption occurrence
window retains at most 1,024 clauses
and 8,192 literals, advances round-robin, and declines a clause exceeding its remaining
literal space. Compaction invalidates root indices through their mapping generation
and other pass snapshots through the learned revision, without changing source
variable IDs. Replacement attachment precedes retirement; active and
historical reasons retain their ordinary protection.
The retained-memory observation counts each primitive buffer once by identity,
including residual capacity, occurrence postings and replacement metadata, and models
live reference slots separately. These are modeled owners rather than a whole-worker
heap measurement. Optional preparation can be discarded under memory pressure;
an interrupted root attachment or pending peer publication protects the retained
buffers until that consequence is complete. Reseeding and closing detach pass buffers,
and vivification resets replace their containers so empty capacity is released.

Restart cadence remains a trigger. Optional propagation-work intervals and learned
clause growth also request work at natural roots, without forcing a restart. The
cooldown requires non-inprocessing work after an attempted slice and applies to
restart requests too. The growth mark excludes the pass's own attachments, so a
pass cannot earn its own next opportunity. Optional warmup starts work and growth
marks at the first eligible complete root and withholds slices until the requested
non-inprocessing work follows that root. Initialization cannot fund that warmup;
scope reset renews its origin without forgiving debt. Required attachment and
publication finish ahead of optional admission. Class rotation
prevents a cheap pass from always taking the entire offered slice. Work and growth
triggers, cooldown and warmup are disabled by default; the additional work ceilings are
unlimited unless configured. Existing general off and SAT preset controls remain.
Funded root continuation resumes retained candidates, source-watch retirement and
selected requests before optional trigger gates. It receives one root-class grant
inside the containing grant and starts no new optional scan. Protected requests
yield ordinary search after a complete protected cycle; deferred work leaves that
cycle incomplete. Ownership changes reopen protection checks, while memory
observation alone supplies no new opportunity. Capture, authorization, cancellation,
acknowledgement and retirement retain their charged work and class latency.
Per-class attempted, eligible, shortened, unit, stopped and work counters diagnose
applicability. Probe outcomes distinguish a consistent fixpoint, a completed
refutation and an interrupted attempt; these counters add no second work charge.
Completed Boolean General probes additionally report empty and productive trails
and implied pin counts before rollback, excluding the probe decision and existing
root facts. Mirrored attempts partition into empty, productive, refuted and
interrupted outcomes; their pin counts are a subset of the General counts.
Attachment, retirement and continuation work are subsets of class work, which
overlaps containing propagation work. Root publication has a
separate containing charge and also cannot earn the next trigger. Per-class, scheduled
slice, continuation and publication durations keep disjoint latency buckets below
one, ten, one hundred and one thousand milliseconds, plus a bucket for at least
one second. These nested spans overlap and provide observations, not a hard pause bound. Learned lifecycle and S4
family attribution retain their own authority and completeness limits; missing
attribution does not establish zero usefulness. These observations select no
adaptive score or default threshold.

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
and root pins; `propagationWork` also counts dispatch, watcher, scheduling and reason visits
across undo. Root figures are subsets of totals. Fixpoint time excludes some counted
reason scans.

Native CP and shared first-UIP outcomes are reported separately. Non-asserting counts
identify chronological fallback, while zero does not establish explanation coverage.
Native learned-constraint counts include integer rows; their terms do not count as
Boolean learned literals.
`learningIntegerSessions` counts native sessions with integer-row learning enabled,
independently of constructed, admitted and consumed objects.
Missing metrics stay absent, including root theory results that never enter traversal.
Live solve snapshots include presolve LP work once and retain the observed LP snapshot
when no aggregation is needed.
Directional branch events update private per-outcome counters; snapshots copy their
counts, retaining isolation from later observations.
`smtCompleteSearch*` stage observations overlap and cannot be summed as a work total.
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

A nonzero non-asserting count points at a component reason that did not support
first-UIP learning. Zero does not establish complete factor reason coverage. Missing
metrics remain missing, including root theory refutations that never enter shared
search.

The CLI also exports native CP work and time (`nativeCpPropagationWork` and related
fields), where root figures are subsets of the totals. Complete open-theory search
adds `smtCompleteSearch*` observations of Boolean assignment depth, LP checks and
stage counters; they stay absent until observed and overlap, so they are not a work
total. See the [CLI](24-cli.md).

---

Previous: [10. Learning](10-learning.md) · [Contents](README.md) · Next: [12. Local search](12-local-search.md)
