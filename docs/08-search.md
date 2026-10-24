# 8. Search and propagation

This chapter describes the first of the engines that run on a prepared model:
complete search. Search explores alternatives, restores state when a branch fails and
checks complete assignments with the [source validation](07-presolve.md#source-witness-validation) service
before publication. Finite propagation narrows Boolean and integer domains, and
arithmetic components enforce the parts of the source model they own. Learning
reuses conflicts only when their explanations remain valid in that scope.

The traversal loop is shared by every complete strategy: establish a fixpoint,
check or choose a decision, apply it, and retract to another alternative after a
conflict. Exhausting alternatives supports a refutation only if all required checks
completed; interruption leaves the unexplored space undecided. This chapter
covers the shared search owners, traversal and branching, and finite propagation;
the [next chapter](09-learning.md) covers explanations, learning and theory
completion.

## Shared component search

[solver.search](../klause/src/commonMain/kotlin/com/eignex/klause/solver/search)
owns decisions, levels, branching, retraction, complete checks, restarts and model
assembly. CP and theory components contribute residual state and reasons through the
same `SearchComponent` lifecycle. Batch `Theory` services participate through
`TheorySearchComponent`; directly incremental arithmetic components use the lifecycle
without a batch service. Participation supplies no completeness guarantee:
indeterminate required checks prevent exhaustion from becoming a source refutation.
See [theory roles](14-theories.md#extension-roles-and-completeness). The session
exchanges Boolean literals, semantic integer bounds and opaque theory decisions
rather than native CP domains.

Four owners divide the work:

- [SearchSession](../klause/src/commonMain/kotlin/com/eignex/klause/solver/search/SearchSession.kt)
  coordinates component callbacks, conflicts and complete checks.
- [SearchTrail](../klause/src/commonMain/kotlin/com/eignex/klause/solver/search/SearchTrail.kt)
  owns decisions, Boolean values, reasons and publishers, integer facts, undo levels
  and pending assertion delivery.
- [SearchLearning](../klause/src/commonMain/kotlin/com/eignex/klause/solver/search/SearchLearning.kt)
  owns shared clauses, attachment and propagation cursors, unit reawakening and
  reduction. Clause identifiers and watch-list order remain stable across index
  growth and rebuilds.
- [SearchRun](../klause/src/commonMain/kotlin/com/eignex/klause/solver/search/SearchRun.kt)
  contains the single traversal loop and owns frames, decisions, restarts,
  cancellation cadence and resumable/terminal state.

A model policy can pause required completion with `SearchModelDisposition.Pause`.
The run retains the assembled model and its decision trail, and retries that policy
before node pruning, branching or complete checks. Pausing neither blocks the model
nor records an indeterminate region. Cancellation still bounds each retry; a root
reset discards pending completion with the old traversal.

Retraction removes trail facts and restores integer premises before notifying
components; a root rebuild keeps learned clauses. Components obtain their token,
accounting and stop diagnostics from `SearchContext.executionControls`; see
[resource allowances](06-sessions.md#component-execution-controls). Components with
retained root preparation implement `PreparingSearchComponent` and return no verdict
until it completes; see
[portfolio preparation](12-portfolio.md#polynomial-root-preparation).

## Traversal and branching

Every pass of the traversal loop starts by choosing what to decide next. This
section describes how branching proposals are collected and ordered, how component
hints choose which side of a split to try first, which columns a leaf completes
without a decision, how arithmetic splits get stable Boolean names, and how
components are registered and assembled into one session.

### Lazy branch selection

`SearchBranchProposal` supplies exhaustive, mutually exclusive alternatives without
requiring the deductive component lifecycle; `SearchBrancher` keeps its component
interface. Each proposal declares a category (Boolean, finite or arithmetic)
describing its supplying mechanism, and branchers without one count as finite.

`SearchBranchSelection.Default` consults the supplied Boolean heuristic first,
then installed components in installation order, then separate legacy branchers,
then independent proposals. `Priority` captures each of Boolean, finite and
arithmetic exactly once and consults them in that order, preserving the relative
proposal order within each category. Reading a category performs no probe. A
selected proposal prevents later probes; null falls through and an empty list
exhausts the residual node.

Selection changes neither component installation nor propagation order: every
returned alternative goes through the same trail, accounting, restart and backjump
machinery, and a required unknown check still prevents exhaustion from becoming a
refutation. A conditioned run keeps the decision level at which it opens; restarts
return there, backjumps below it fall back to chronological traversal, and
exhaustion is conditional (see [sessions](06-sessions.md)).

For an entirely theory-owned integer difference graph, source-proved relative
interval proposals precede Boolean choices through arithmetic category priority,
registering exact complementary difference predicates; see
[difference logic](14-theories.md#difference-logic).

### Polarity hints

Once a Boolean variable is selected, a component can suggest which side to try
first. Component-owned candidate hints reach ordinary Boolean traversal through the
`SearchCandidateHints` surface; caller and deferred hints keep precedence. A
preference only reorders the two sides of an already selected split, preserving the
complementary alternative. Propagation and complete component checks keep their
authority, and a hypothetical value supplies no arithmetic fact or feasibility claim.

Built-in providers derive preferences from a completed arithmetic point: a single
linear completion owner reuses the point of its last numerical check, and the opt-in
`openHintFlips` provider indexes source clauses and prefers the polarity that breaks
fewer of them. Index construction is bounded and charged to the opening token; see
[native arithmetic allowances](06-sessions.md#native-arithmetic-allowances).
Solution-guided LP bounding may also order values by verified peer incumbents until
its first local witness. Hints supply no incumbent, pruning or publication authority.

A non-restarting retained complete optimization worker with an external cutoff reads
the current pooled assignment at its first root and revalidates it before using it
as a local incumbent. A witness equal to the containing portfolio's cutoff
can seed that worker's incumbent and objective propagation at its first root; it
need not improve the containing portfolio again. Invalid assignments and assignments
above the cutoff do not seed it. This starting witness supplies bounds without
priming solution guidance or earning incumbent progress. Restarting workers retain
their discovery and root-primal startup path. Rebindable neighborhood searches retain strict
improvement admission, and exhaustion under an external cutoff keeps its conditional
result scope.

### Leaf-completed columns

Presolve keeps every column slot, so a column whose readers were all reduced away
(folded comparison indicators, eliminated or substituted columns) stays a free
column. A one-model CP search, satisfaction or optimization, leaves such columns
undecided: [LeafColumns](../klause/src/commonMain/kotlin/com/eignex/klause/propagation/LeafColumns.kt)
names the columns no factor declares, less every objective term, and the built-in
selectors pass over them. A leaf then reads `false` or the domain minimum for each
one still free. Because nothing reads these columns, that value extends every
assignment the leaf's checks accept, and refutation is unchanged.

The columns are skipped, never fixed: assumptions and neighborhood pins still
assign them, and a later search over the same model may need either value.
Enumeration, sampling and model counting tell solutions apart by these columns,
and a supplied search component may read any column through the shared trail, so
those searches decide every column.

### Source bound atoms

Fractional source integers split at exact floor/ceiling thresholds: Long values in
CP decisions, arbitrary precision in open theories. Learning needs stable
complementary atoms, so each split is named as a Boolean before first-UIP analysis;
names belong to one immutable source root, and unnameable conflicts stay
chronological.

[SourceBoundAtom](../klause/src/commonMain/kotlin/com/eignex/klause/solver/search/SourceBoundAtom.kt)
owns these neutral exact bounds. Integer splits keep adjacent thresholds, rational
splits keep complementary strictness, and terms are normalized before registration.
Coefficient growth, size limits or registry capacity decline without installing a
partial pair, and retraction preserves atom meanings. Only the linear adapter turns
the predicates into LP rows. The linear component names a source disjunction when
arithmetic branching reaches it; a group that cannot be fully named leaves the search
indeterminate.

### Assembly and registration

Source-bound registrations pair prepared execution descriptors with component
factories. The collection rejects duplicate factor and coordinate ownership before
invoking a factory, and the traversal never selects concrete arithmetic routes.
Repeated openings share read-only source data and projections while creating fresh
component instances. `SearchComponentSet` permits one session construction attempt;
push/pop and conditioned runs operate within that session, and cleanup follows
[invocation scopes](06-sessions.md#theory-invocation-scopes). Open linear arithmetic
prepares its source rows once through `prepareExactLiraSource` and opens a fresh
component per session.

Exact affine optimization records Boolean and integer coverage alongside checked
bounds; only a complete coverage tree proves a global optimum, while a checked
source ray can finish existentially. A checked node bound that excludes improvement
over the invocation's checked incumbent closes that unexpanded subtree. Its scoped
derivation remains in the coverage tree; global optimality rechecks the composed
bound with the original-source witness after every alternative is covered. See
[linear certification](17-linear-programming.md#refinement-and-strict-inequalities).

Complete assignments use typed source-keyed values from each owner, assembled only
after all required checks finish. Missing or multiply represented coordinates leave
publication unavailable. [Theory completion](09-learning.md#theory-completion) describes
how those values are contributed.

## Finite propagation

After a decision is applied, search runs the finite-domain fixpoint: every
propagator narrows the Boolean and integer domains it watches until nothing changes
or a conflict appears. This section describes who owns that state, how the
propagation projection for each factor is selected, how propagation feeds the
branching heuristics, and which checks a complete finite point must pass before it
can be published. The reasons propagators record are the subject of the
[next chapter](09-learning.md).

### Finite propagation

[PropagationSession](../klause/src/commonMain/kotlin/com/eignex/klause/propagation/PropagationSession.kt)
owns the finite-domain trail and fixpoint. Sessions over one immutable model share
stateless propagation projections and occurrence indexes; incremental payloads,
scratch, watchers and failure contexts belong to each state. Undo discards pending
failure context, and fresh presolve rebuilds keep the originating cancellation token;
see [presolve](07-presolve.md).

Boolean-only models journal each pin without integer-state padding. Integer undo
replays saved domains and interior carves. Unconditional root carves are kept in
state-owned interval runs, so late equality atoms and historical queries still see
them.

Layered MDD filtering watches interior removals as well as bounds. Forward and
backward reachability and weighted path bounds use full symbol membership. Shared
root reachability is admitted only when every position contains the whole diagram
alphabet; a hole inside that alphabet requires private reachability. Reversible
layers restore the filtering state across sibling branches.

A completed fixpoint can share Boolean- and root-grounded native subproofs with search
peers through a bounded memo of at most 4,096 entries. No memo survives a callback,
suspension or retraction.

#### Arithmetic kernels

Propagators guard signed-word arithmetic explicitly rather than wrapping. Increasing
chains reject endpoints whose successor would overflow; product bounds clamp corner
products and use exact square-root inverses for repeated operands; integer `Power`
uses exact comparisons and filters at most 256 values per fire, falling back to bounds
on wider domains. Mixed-sign inputs keep their interior values, and complete
assignment checks still enforce the full relation.

`DivMod` collects supports from at most 4,096 operand pairs before narrowing any
role. Repeated roles must agree within each tuple. Support application follows the
distinct coordinate order, tightens the lower bound before the upper bound and
then removes unsupported interior values.

#### Difference systems

The finite difference system is posted after presolve only when its fragment has a
guarded edge between two unfixed root integer columns; edges to fixed columns act as
unary bounds. Finite preparation can also append a redundant difference system over
exact real rows and real table selections, whose vertices are private arithmetic
coordinates rather than finite real domains. Source guards and table index atoms enter
the graph directly, so a contradictory selection is excluded before all discrete
columns are fixed. A consistent graph supplies no model; all original factors and
source checks remain. See
[real graph admission](14-theories.md#real-graph-admission-and-checks).

### Selected propagation projections

`PropagationProjectionProvider` selects a source-bound factory and its filtering and
pinned-check capabilities during preparation. A factory whose `NoPropagator` result
disagrees with its declared inert role is rejected. Alternative deductions must stay
sound for the original factor, including watchers and explanations.
`SOUND_FILTERING`, `BOUNDS_FILTERING` and `DOMAIN_FILTERING` describe deduction
mechanisms, not full consistency at every size.

Exact integer rows select compact signed-word propagation when their constants fit
and the sum of coefficient magnitudes is at most `Long.MAX_VALUE`, keeping residual
checks within 128 bits; wider rows keep arbitrary-precision propagation. Packed SAT
propagation requires built-in clause descriptors, so a clause assigned to a custom
projection uses the general CP lane. AllDifferent tries its cached incremental path
before full matching. Assignment-dependent payloads such as live tuples and diagram
reachability stay state-owned.

DivMod retains complete signed tuples for at most 4096 live operand pairs in each
state's factor payload. Each fire recomputes live supports in reusable primitive
sets against all operand and output domains, including holes and aliased roles.
Dividend-indexed tuple buckets skip rows whose dividend is absent.
Output narrowing does not remove stored tuples. The payload survives retraction;
restored operand values outside its construction domains require rebuilding it.
Larger products keep bounds filtering without tuple enumeration, and a later small
branch can construct its own bounded relation. Deductions cite the current bound
and hole premises rather than retained branch assumptions.
Retention reserves the operand product times arity plus 64 cells per payload from
a 1,048,576-cell state allowance. Replacements reuse their reservation; tombstoning
can leave unused reservation. Exhaustion keeps complete small-product filtering
with temporary supports, bounding retained storage without weakening deductions.

An inert descriptor requires an explicit reason and cannot claim an exact check, so
its absent mechanism needs an enforcing peer or separate source certification.
Objective-bound overlays omit CP propagation, and a sound LP family may decline
emission; omitted rows only weaken the relaxation. A factor rewrite reselects
providers before the compact CP view is built, and a selected finite relaxation is
never source-completion authority.

### Branching and global deductions

Branching receives each conflict's full variable and factor attribution, including
implication-graph variables of learned conflicts: VSIDS bumps each implicated
variable once, and CHB rewards each attributed variable. Successful fixpoints deliver
new deductions to propagation-aware selectors. Phase saving captures values before
retraction, so propagated as well as decided polarities survive backjumps and
restarts. Dom/wdeg failure weights belong to the solve and survive restarts; fresh
selectors isolate solves that share a projection.

Circuit propagation removes targets claimed by fixed incoming edges and premature
chain closures throughout successor domains, including interior values. Full
circuits also exclude self-loops; optional cycles treat an absent self-loop as
mandatory participation even when it is an interior hole. Conditional removals cite
their fixed edges and required participation premises and retract with them.
Reachability and full-circuit dominator filtering inspect the remaining arcs.

General CP optimization builds its private state in retained batches before
traversal, and assumption installation commits each fixpoint before the next;
closing releases the cursor without exposing partial state. See
[private CP preparation](12-portfolio.md#private-cp-preparation).

Cumulative edge-finding runs in both time directions over fixed durations, demands
and capacity, declining the reversed pass when its arithmetic is not safely
representable.

Diffn's constant-size sweep filters positive-area compulsory parts. Strict boxes
with a zero dimension also receive the pairwise separation check, so the sweep's
empty-area shortcut cannot admit an invalid complete assignment. Non-strict boxes
with a zero dimension impose no overlap restriction.

Minimizing a single integer variable over scheduling globals raises its root lower
bound by a strip-energy floor. Chains of two-variable unit-coefficient rows and
maximum results prove which tasks end by the objective. Their energy from the
strongest left edge, divided by the capacity, bounds it: a Cumulative uses its
declared capacity, and a constant-size Diffn projects onto either axis with the
counted rectangles' perpendicular extent. The raise cites the live start and
perpendicular bounds it reads; starts or coordinates the model leaves open are
skipped.

### Complete finite checks

Complete finite checks pin the full assignment and run a completed CP fixpoint;
interrupted verification stays indeterminate. A zero local-search score is not an
exact certificate.

Selected partial or inert finite deduction keeps an original-source check at a
complete point, using a private propagation state over original factors. Without a
complete original checker the leaf stays indeterminate. Source-real checks belong to
arithmetic completion.

#### Custom factor publication checks

Custom factors keep the `Propagator` and `Invariant` extension paths. A custom
`Propagator` without an `Invariant` maps to `NoInvariant`, which contributes no local
violation, so before publishing a zero-score candidate, local search and ALNS pin
every Boolean and integer coordinate in a private propagation state over the
uncovered constraints. A completed full-assignment fixpoint checks the candidate;
root consistency alone does not. Custom propagators must therefore reject invalid
complete assignments.

Rejection applies only to the candidate, and interrupted checking withholds
publication without refuting the source. A custom `Invariant` supplies local checking
and scoring but no CP propagator. Structural-only custom factors refuse CP and
local-search execution, and constraints needing continuous coordinates without an
exact local checker decline finite local search. Custom implied linear rows stay
available to the LP relaxation.

---

Previous: [7. Presolve and validation](07-presolve.md) · [Contents](README.md) · Next: [9. Learning and theory completion](09-learning.md)
