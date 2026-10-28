# 9. Learning and theory completion

Search learns from scoped deduction reasons and asks arithmetic owners to complete
finite assignments. This chapter follows conflict analysis, clause retention and
restarts, then theory completion and primal proposals. Traversal and propagation
are described in [search](08-search.md).

## Explanation lifetime

Propagators supply sound source reasons for deductions and conflicts. An assignment's
undo lifetime can be deeper than the effective level of its reason: Boolean pins use
the deeper of that level and the current decision depth, so an asserting backjump
retains its consequence.

Reasons are built lazily where that is safe. Single-sided linear indicator deductions
record a lazy payload during trailed search, which analysis resolves against the
bounds at the pin by reconstructing them from the undo log; untrailed and two-sided
deductions stay eager. Reasons are built only when a bound actually tightens the live
domain. A reified equality whose target value is a hole keeps that removal as a
premise, because bounds alone cannot justify the conflict.

Mandatory unit-integer `Distinct` deductions retain the Hall source positions in a
lazy payload. Explanation reconstructs their bounds and holes at the deduction's
trail boundary; later carves cannot enter the earlier reason. Offset identities are
immutable, so the premises name only original integer coordinates. Matching failures
retain the deficient Hall set. Within the value-walk allowance, bounds can lift
across values already occupied by that set, and holes inside its union need no premise.

Assumption cores keep the applied seed prefix when propagation refutes it, or the full
assumption set when the tree is exhausted. Cores are jointly refuted with the hard
model and need not be minimal; an interrupted seeding cannot certify the full seed.
An internal opt-in seed projection starts from the historical forcing clause of a
Boolean pin contradicted by the next named assumption. It resolves eager Boolean
antecedents until every remaining literal is negated by an applied named pin, keeping
transitive premises rather than using their decision levels as proof. Root units
must belong to the captured baked model. Integer atoms, lazy or unavailable reasons,
cycles, cancellation and local limits retain the certified prefix. The projection
admits at most 128 literals, 128 resolution steps and 8,192 visits, charged to the
same propagation work and containing token. An empty projection supplies no verdict.

### Table explanations

Table filtering keeps STR2 explanations, built from build-local caches of historical
domains that never cross propagation or undo. The reversible live prefix is committed
once per completed sweep, and sibling branches restore filtering from trailed state.
Ground table deductions record their bound or excluded value at the actual pin's
trail position. Historical cell checks consider only tuples whose target cells fall
in that removal and choose one blocking cell per tuple. Interval deductions and table
conflicts reconstruct the general historical support relation.
Ground relations share a value-to-row index across projection siblings and sessions.
Each value uses a primitive row posting or a tuple mask, whichever needs fewer bytes.
Index construction admits at most 32 MiB of primitive buffers including sort scratch;
larger relations keep the scan path. A reason visits only the target value or bound
range's postings, then checks the deduction's historical domains. Mutable live
prefixes and explanation caches remain state-local.
Support-index construction and historical reconstruction poll the state's invocation
token. Interrupted construction publishes no shared index or reason memo. A stopped
lazy reason is unavailable; it cannot become an empty premise list, and conflict
analysis discards its unfinished learned result. A completed immutable index remains
available across state retraction and later invocations.
Several unsupported interior values commit as one domain mutation. Their individual
lazy reasons refer to the pre-batch trail boundary, and each removal retains its
own carve witness for existing and late equality atoms. Undo restores the prior
domain and removes the batch's witnesses together.
Root propagation folds unary Table restrictions with at most 4096 expanded supports,
so local search samples from the same restrictions.

MDD explanations reconstruct full symbol membership at the deduction's trail
position. A cut path cites the bound or interior removal that excludes its symbol;
later carves cannot become premises of an earlier deduction. Weighted path reasons
use the same historical membership.
Each value removal records its symbol. Shared sparse/dense symbol postings select
that symbol's edges for the cut; storage declines retain the indexed graph scan.
Table values and MDD symbols use the same bounded immutable posting representation.

### LP explanations

LP explanations cite source bound witnesses and recursively expanded row, fixing and
cutoff premises, including rows used to substitute continuous terms; an unexpressible
scope withholds the consequence. With LP learning enabled, a bounded deduplicating
pool admits each row tightening as an implication in the persistent CP session, so it
survives undo and propagates in sibling branches. These clauses assume no incumbent
cutoff. See
[LP consumer contracts](17-linear-programming.md#consumers-explanations-and-cuts).

An external fixing enters CP at the active decision depth without a CP factor owner.
Its explicit premises, when supplied, remain its reason. An immediate contradiction
has no factor conflict clause to analyze; conflicts reached by the ensuing fixpoint
use the factor that actually failed. An unrelated preceding factor's context cannot
provide a fixing's level or conflict reason.

## Conflict analysis

`ConflictAnalyzer` walks the implication graph backwards from a conflict, resolving
each step against the antecedents of the resolved variable in reverse assignment order,
until exactly one literal at the current decision level remains: the first unique
implication point. The graph spans Boolean variables and integer order literals
uniformly. Factors emit per-factor conflict reasons and record antecedents on every
force, so the analysis resolves through non-clause deductions instead of treating
them as leaves. A variable with no antecedent is a decision, assumption or root fact.

Folding an antecedent into the nogood is delegated to a `ConflictResolvent`:
`ClauseResolvent` is the default and sound fallback, and the pseudo-Boolean resolvent
below is the alternative. A learned constraint carries its backjump level, its LBD
(the number of distinct decision levels it touches) and whether it asserts after
backjumping; a root conflict learns nothing. The variables seen during analysis form
the bump set for VSIDS and CHB.

`ClauseMinimizer` shortens every first-UIP clause before it is emitted: first by
self-subsuming resolution, then by binary-resolution minimization. Dropping a
redundant literal yields a stronger implied clause, so the result stays an asserting
nogood. The redundancy memo is cleared before each conflict, since levels and
antecedents belong to that analysis.

Two analyzers share this construction. The native CP analyzer learns over CP reasons
and stores into the native clause database. The shared first-UIP analyzer in
`SearchLearning` learns over component reasons; a shared conflict with usable reasons
asserts and backjumps, while a sound resolvent that cannot assert keeps chronological
fallback. Unexplained component publications cannot supply missing antecedents.

The numerical frontier also uses the shared clause-resolution operation on checked
source refutations. Each retained resolvent carries an exhaustive integer-split
derivation over its two input proofs. It enters the numerical coverage tree only
after a new source check, preserving proof authority independently of clause names
and queue order. This bounded opt-in policy retains complete Boolean scope and
opening restrictions; unsupported predicates cannot produce a resolvent.

## Native SAT lane

A pure-Boolean model whose factors are all clauses runs on a dedicated lane. The
`nativeSat` setting auto-dispatches by default: the lane is selected exactly when the
propagation projection is eligible, and a custom clause projection makes it
ineligible. Clause literals are packed into an immutable arena whose clause indices
match the model's factors, so reasons still identify source factors, and the arena is
shared read-only across portfolio workers.

Propagation is two-watched-literal unit propagation over a trail pointer, without
per-factor dispatch or integer order literals, reading a per-session truth byte per
variable. Learned clauses live in a growable side buffer with a wrapping
replacement-scan cursor per clause, and reductions compact them in place. The lane
shares the general trail, pins, antecedents and conflict analyzer.

## Pseudo-Boolean learning

`pbLearning` auto-enables cutting-plane analysis for pure-Boolean models. Explicit
enablement admits Boolean conflicts in mixed models. The seed, each loaded source
constraint and each reconstructed reason must contain only Boolean coordinates;
integer order, equality and hole atoms retain clause fallback before Boolean indexing.
An asserting native PB result takes precedence over shared clause analysis and stays
in the CP store. Other mixed conflicts keep shared clause analysis.

The resolvent is a linear `≥` nogood seeded from the violated bound. Each step
rounds the reason up after division by its pivot coefficient, then adds it to cancel
the pivot. Weakening and saturation keep coefficients bounded and canonical. These
nonnegative combinations with Chvátal-Gomory rounding preserve implication. A step
that declines, including signed-word overflow, falls back to clause resolution.
Learned pseudo-Boolean constraints share clause retention.

## Integer row learning

`integerRowLearning` is opt-in and independent of pseudo-Boolean learning. Mandatory
finite `Linear` inequalities and the explaining half of equalities provide integer
rows. Each resolved endpoint must name an unextended historical linear reason from
the same source row; historical holes, reification, disequalities, wide constants
and unsupported projections retain clause fallback.

Opposite-sign nonnegative combinations use checked signed arithmetic and integer
gcd normalization. Each combination must remain conflicting. A retained row must
propagate against the session's historical domains at its native backjump and the
mapped shared target; literal counts do not establish assertion. Foreign unavailable
bound premises retain clause analysis. The producer admits at most 16 terms, 16
combinations, 64 native levels and 64 stored rows, with bounded duplicate lookup.

Rows share the learned store and integer bounds propagation. Eager reasons freeze
the historical clause premises without a compactable lazy factor id. Integer bound
event subscriptions wake rows after undo and are remapped or removed on forgetting.
Later conflicts use these retained rows' clause explanations; row combination loads
only original mandatory source rows.
Only clauses are shared across arms. Row satisfaction tests the complete maximum
activity; a satisfied endpoint literal cannot discharge the row or certify a root
contradiction.

## Learned-clause retention

Native forgetting runs at restart boundaries only with a configured cap. Permanent
and glue clauses (default LBD at most 2) survive; remaining space favors lower LBD.
The SAT preset enables capped tiered retention:

| Tier | Initial classification | Lifetime |
|---|---|---|
| Core | At/below glue threshold | Never deleted |
| Mid | Through LBD 6 | Survives reduction; idle clauses demote to local |
| Local | Above LBD 6 | Aggressively deleted; conflict use promotes to mid |

Tiers persist independently of later LBD. Shared component learning is uncapped by
default; capped reduction retains glue clauses, units and recent clauses before
ranking by LBD. [ClauseDb](../klause/src/commonMain/kotlin/com/eignex/klause/backtrack/ClauseDb.kt)
and [SearchLearning](../klause/src/commonMain/kotlin/com/eignex/klause/solver/search/SearchLearning.kt)
own the respective stores.

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

Inprocessing runs at restart boundaries in bounded round-robin slices over the
learned database. Vivification strengthens a clause by asserting the negations of its
literals under propagation; subsumption drops subsumed learned clauses and applies
self-subsuming resolution. Every pass must preserve the variable set, because portfolio workers share learned
clauses under stable variable ids and share the native arena read-only; variable
elimination runs once in [presolve](07-presolve.md) instead. Seeded searches skip
inprocessing, since derivations under assumption pins would not hold globally.

## Theory completion

Arithmetic owners complete or refute the coordinates left by finite search.
[Theory services](14-theories.md) define their fragment-specific checks.

A component's partial consistency check or relaxation witness does not accept a
complete source assignment: every participating owner must finish its required check
before publication. [Theory contracts](14-theories.md) define the fragment-specific
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
[difference proofs](14-theories.md#difference-logic). Open quadratic integer optimization branches
on exact wide predicates and prunes against certified bounds and checked incumbents,
without turning objective cutoffs into infeasibility conflicts. See
[nonlinear integer quadratic objectives](16-nonlinear-arithmetic.md#quadratic-objectives).

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
[Typed contributions and mappings](19-api.md#typed-contributions).

## Primal heuristics and statistics

Primal heuristics propose incumbents inside complete search. Each proposal needs
full source verification and supplies no proof authority.

### Root primal proposals

On mixed models, the root LP relaxation also proposes incumbents. Nearest rounding,
directed upward and downward rounding of the retained LP point, a peer worker's
discrete assignment and a feasibility pump each produce discrete targets. Every
distinct proposal passes the discrete fixpoint under the caller's assumptions, then
real completion through the real-leaf owner or the caller's source tolerance check,
then full source verification. Only a complete checked witness that strictly
improves the current cutoff becomes an incumbent.

The opt-in `lbTreeSearch` probe extends this to a private finite trail with exhaustive
fractional and value alternatives, trying the nearer side of the LP value first. It
returns accepted witnesses only and exports no optimum, refutation, bound or recession
claim. The `lp-lbtree` portfolio recipe can enable it.

Root primal work charges the containing root ledger and solve-wide `NodeBudget`, and
a fallback solve receives only the remaining allowance.

### Monotone clausal primal probe

Pure Boolean clausal optimization with at most 64 positive-cost variables can seed
incumbents through greedy feasibility repairs when those variables occur only
positively in hard clauses. The probe runs once before ordinary optimization and is
skipped behind an existing shared incumbent or within repair fragments.

A private satisfaction repair first finds a feasible assignment, then turns off any
cost variable whose removal alone keeps every hard clause satisfied. Trials then try
to turn off the remaining cost variables in descending weight order through a clause
walk with penalty escalation, restoring the previous feasible assignment on failure.
Trials run on a projected model reduced by unit propagation and bounded Boolean
elimination, and lift their result back. Private infeasible verdicts contribute no
outer proof, and the full original proposal is verified before publication.

The probe's work is charged to its arm and the solve-wide move allowance, within at
most half the remaining time and 5 seconds.

### Statistics

Counters describe observed work, never proof coverage.
`propagations` counts deductions from completed finite fixpoints, excluding decision
and root pins; `propagationWork` also counts dispatch, watcher, scheduling and reason visits
across undo. Root figures are subsets of totals. Fixpoint time excludes some counted
reason scans.

Native CP and shared first-UIP outcomes are reported separately. Non-asserting counts
identify chronological fallback, while zero does not establish explanation coverage.
Native learned-constraint counts include integer rows; their terms do not count as
Boolean learned literals.
Missing metrics stay absent, including root theory results that never enter traversal.
`smtCompleteSearch*` stage observations overlap and cannot be summed as a work total.
[SearchStats](../klause/src/commonMain/kotlin/com/eignex/klause/solver/result/SearchStats.kt)
and [CLI statistics](20-cli.md#statistics) define the exported fields.

---|---|
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
total. See the [CLI](20-cli.md).

---

Previous: [8. Search and propagation](08-search.md) · [Contents](README.md) · Next: [10. Local search](10-local-search.md)
