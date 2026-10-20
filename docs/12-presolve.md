# 12. Presolve and reconstruction

With a scope and an allowance in place, the first thing a solve does is prepare
the model. Presolve reduces it before search while retaining the information
needed to interpret results in the original source. It can fix variables,
substitute expressions, remove redundant constraints or strengthen bounds. The
required preservation guarantee depends on the operation: satisfiability,
optimization and counting need different contracts.

Preparation applies only reductions justified for that query, records
reconstruction and objective mappings, and charges its work to the preparation
phase of the allowance from [chapter 11](11-resources.md). This chapter gives the
shape of the pipeline, then the guarantees each reduction must declare, and finally
how rebuilt models and phase accounting are owned. The search that runs on the
prepared model is the subject of [chapter 14](14-search.md).

## Pipeline

Presolve runs in phases over one model. A source phase works on the declared model
before the root bake; it catches cheap refutations such as a gcd-indivisible equality
in time proportional to the factor count, before propagation would narrow a wide
domain one step per round. The root bake then establishes the propagation fixpoint
of the root model; optional failed-literal and singleton arc consistency probing
tiers extend that bake from the presolve lane, so the engine never depends on
presolve. Finite rounds follow, driven by one bounded-fixpoint scheduler shared
with the source lane: each round runs the enabled passes, and a pass above the
cheapest tier reruns only after other passes changed at least 1% of the model.
Presolve and an LP-relaxation harvest iterate to a joint fixpoint: proven domain
tightenings from the LP can unlock further reductions, and the harvest reruns only
when it tightened something, for at most four rounds.

Passes fall into a few families:

| Family | Passes |
|---|---|
| Linear | Coefficient strengthening, Diophantine bound tightening, linear bound fusion, affine singleton elimination, sub-sum aggregation, dependent-equality removal, singleton inequality projection |
| Structural | Redundancy and subsumption, structural reduction, comparison-clause folding, duplicate-column merging, dual fixing |
| Boolean | Implication graph, bounded variable elimination, blocked clause elimination, at-most-one clique merging, XOR unit derivation |
| Symmetry | Symmetry breaking and value precedence |
| Probing | Failed literals, integer bounds and holes, open ranges |
| Around the rounds | Binary column substitution, LP harvest, difference-system posting |

Each pass has a cost tier. `PresolveEmphasis` selects the tiers and round cap:
`off` runs nothing, `conservative` applies the cheap tier once, `default` iterates
the cheap and moderate tiers to a fixpoint and `aggressive` adds exhaustive probing,
each within a cap of 16 rounds and bounded probe budgets. Individual passes can be
enabled or disabled by name; a pass that would break the query's required guarantee
stays disabled regardless.

## Guarantees and source mappings

`PresolvePass.guarantees` separates equisatisfiability, objective preservation, witness
reconstruction, bijection, and complete coverage by reconstructed source assignments.
Objective preservation is conditional on the pass context protecting objective columns.
A satisfiability reduction can retain an optimum while losing source multiplicity; its
successful reconstruction does not establish a bijection. `preservesSolutionSet` retains
the raw solution-set classification for compatibility.

Affine elimination carries `AffineValue` records through both source and finite
presolve. Records own their coefficient arrays and evaluate with exact integer
arithmetic; residue division must be integral and finite results must fit `Long`.
Reconstruction runs in reverse elimination order. `SourceMapping` binds a phase and its
composed reconstruction to the actual source and reduced model objects. Composition
requires the preceding target to be the following source. Equal variable counts and
factor counts do not establish this association.

Retained hint coordinates follow these actual mapping records. Presolve keeps source
column slots; a reconstructed column is excluded from direct definition and search
hints, including aliases and clause-repair outputs. Mapping chains compose those
exclusions. A custom reconstruction without a direct correspondence withholds optional
hint projection. Finite round hosts retain whether every reconstruction closure is
fully described by its declarative rebuild steps; only those mappings retain direct
coordinates outside the rebuilt columns. Native worker construction still uses the
transformed factor model, its selected mechanisms and the original source's
operation/objective binding.

Objective adjustments map reduced values into source units as `scale * value + offset`,
with positive exact rational scale. Composition multiplies scales and transports the
later offset through the earlier scale. Current reductions protect objective columns and
use the identity adjustment; binary substitution only zero-extends Boolean weights.
Independent source objective evaluation checks the composed adjustment against
reconstructed witnesses. A mapping discards reduced-model witness certificates when its
source differs from its target and never issues a proof certificate; source publication
retains its complete checker. Optional `SourceMapping.captureSolve` and `captureMinimize`
retain reduced evidence and transformation stages on completed results. They check
reconstructed original witnesses and normalize supported citations into original-source
derivations. A transformed terminal cannot gain original engine admission from a
reduced label; absent or unsupported justification withholds that admission. See
[original-source evidence](17-evidence.md).

Sensitive queries require a bijection even when a pass is explicitly enabled. Affine
elimination and other multiplicity-changing reductions remain disabled for enumeration,
counting and sampling. Projected enumeration deduplicates the requested Boolean and
integer coordinates while retaining terminal exhaustion, indeterminate checks and
decision-budget interruption. Hash cells stop after `cap + 1` distinct projections,
rather than full witnesses. An exhausted cell supports an exact count or uniform draw;
an interrupted cell supplies only a lower bound and cannot support either. This
implementation can still visit many full witnesses of one projection and retains the
per-cell decision budget.

Fixed Product operands reduce to exact linear equalities. Negating a minimum signed
word coefficient uses the wide integer row representation; aliased result and remaining
operand positions combine their exact coefficients before construction. Coordinate
remapping retains that combined row and the original source-check obligation.

Optional `SourceMapping.justification` retains typed source-to-target obligations.
Composition retains each stage's justification in order; declared transformation
guarantees do not provide independent proof coverage. Original-source witness
checking remains required after reconstruction. See [derivation checking](17-evidence.md)
for verified affine projection and objective-adjustment conditions.

`SourceSetup.execute` owns preparation-only execution selected through typed common
options. It validates operation and phase applicability before executing preparation.
Finite requests retain their `PreparedOnly` envelope, including application-supplied
factories. Open linear requests return `SourceExecutionResult.Prepared` with their
original model, native preparation and elapsed time. The original containing stop,
optional phase token and spent phase ledger compose without a new allowance. These
results contain diagnostics, with no search publication or source solve terminal;
frontends only format them. Other native preparation phases decline explicitly.

## Rebuild ownership

For a selected private float grid, finite presolve reconstructs into its actual
private grid model first. The invocation's explicit cross-kind grid map then lifts
to original real coordinates before source checking. The canonical source plan and
private engine plan retain separate factor/coordinate namespaces. A matching number
of coordinates does not establish correspondence, and a foreign source, target or
selected consumer cannot reuse the map. Original real bounds and global proof scope
remain with the source; see [private float projections](03.1-source-relations.md#private-float-projections).

[PresolveShared.rebuildProblem](../klause/src/commonMain/kotlin/com/eignex/klause/presolve/PresolveShared.kt)
carries the originating cancellation token into a fresh `BakedProblem`.
[RootBaker.reseed](../klause/src/commonMain/kotlin/com/eignex/klause/presolve/RootBaker.kt)
preserves that token when it creates a problem carrying probe deductions. The deadline,
work-stop predicate and work meter therefore keep their originating scope across fresh
rebuilds and reseeds. Rebuilds and reseeds also preserve the source model's immutable
`ProblemSettings`; the pre-bake span gate cannot change when another invocation changes
ambient configuration.

A fired token skips root-probe phases and stops cancellation-aware propagation at its
polling boundaries. Partial propagation is sound but can forgo deductions. Cancellation
is cooperative: propagation-state construction and work between polls can exceed a
deadline. Probe-call caps do not establish a wall-clock bound.

## Phase allowance and telemetry

[PresolveBudget](../klause/src/commonMain/kotlin/com/eignex/klause/presolve/PresolveBudget.kt)
owns the phase's deterministic work allowance. Its tokens carry a native-work adapter,
so explicit `Cancellation.charge` calls reduce the original allowance, and rebuilding
a problem neither creates another allowance nor resets consumed work.

Root propagation reports native work through a narrow propagation-owned meter, and
presolve converts it: each construction entry and each unit of native propagation
work costs 53 phase units. Construction entries count Boolean, integer and real
columns, factors and each factor's structural-key weight, a deterministic size proxy
rather than a count of bytes or instructions. Native propagation work counts factor
fires, literal reads and wakeup entries, including native SAT BCP. Every fresh root,
probe and repair reports construction once and flushes counter deltas before each
cancellation poll and on return, so no delta is billed twice; a reseed bills its
fresh root, and an already-baked input bills nothing. Probe calls (Boolean probes,
integer bound/hole SAC and repairs) and round entries accumulate across tiers,
reseeds and schedules. Integer probe limits apply per variable and per tier.

A spent allowance or fired outer token prevents entry into another probe, polarity,
repair or tier. A completed failed probe keeps its proven forcing, bound exclusion or
hole even with no allowance left, and completed pass deltas remain mandatory; the
conditional deductions of an interrupted hypothetical probe are never published.
Construction is mandatory and charged after completion, even under a zero allowance.
Propagation polls every 256 CP factor fires or native SAT trail entries, so a single
fire, trail entry or construction can overrun the allowance; the weights are not a
time bound. `PresolveBudget(0)` stops optional probing, and an explicit
`presolve-work-limit=0` supplies an exhausted allowance. Counters saturate at
`Long.MAX_VALUE`.

The CLI pairs the work allowance with a shared elapsed-time ceiling of the same
derived duration, retained without restart through routing and source/finite
preparation. Either limit can stop optional passes; partial reductions remain sound,
but elapsed cancellation can change the deductions and selected route with host
speed. `presolve-work-limit=unlimited` removes only the work cap, and an explicit
nonpositive presolve millisecond budget disables both derived limits. CLI effort
telemetry reports construction entries and propagation work separately from
converted `presolveWork` and probe calls. See [resource allowances](11-resources.md).

## Selected reductions

The passes listed under [Pipeline](#pipeline) share the guarantees above. This
section describes what each family deduces and which guarantee it needs, then the
reductions that carry additional representation or cost rules.

### Linear reductions

Coefficient strengthening divides an integer or pseudo-Boolean row by the gcd of its
coefficients and rounds the bound inward; an equality whose bound is not divisible is
infeasible, and an indivisible disequality is dropped as always true. Diophantine
tightening goes further per variable: in `sum(a_i * x_i) = b`, every other term is a
multiple of `m = gcd(a_j : j != i)`, which confines `x_i` to one residue class and
moves its bounds inward to the nearest member. Finite domains of bounded size lose
their off-class values; source ranges move only closed sides. Dependent-equality
removal runs exact fraction-free elimination over the equality block, drops rows that
reduce to `0 = 0` and refutes a block with no rational solution. Sub-sum aggregation
rewrites a row containing the whole right-hand side of a defining equality
`y = B + sum(A_j * x_j)` to a single `k * y` term. Linear bound fusion collapses an
upper and lower bound on the same coefficient vector into an equality when they meet,
and refutes the model when they cross. These are solution-set preserving.
Affine singleton elimination substitutes equality-defined variables and singleton
inequality projection drops a variable that occurs in exactly one inequality,
pinning it at its best bound on reconstruction; both need reconstruction, and the
projection is disabled for solution-set-sensitive queries.

### Structural reductions

Structural reduction offers each factor its own `structuralReduce` hook under the
current domains, so globals that their structure pins become simpler factors; on
source models it reaches only factors whose integer columns are all closed, since an
open column has no domain to reason from. Redundancy and subsumption remove duplicate
and dominated rows, maintaining their indices across rounds so a fruitless rerun
costs time proportional to the changed factors. Comparison-clause folding replaces a
clause over sole-use indicators of single-variable reified comparisons with one
comparison disjunction. Duplicate-column merging replaces two integer columns that
occur in exactly the same linear rows with equal coefficients by their sum,
widening the survivor to the Minkowski sum and splitting the value back on
reconstruction. Dual fixing pins a variable to a bound when every occurrence is a
monotone inequality that moving it toward that bound cannot violate and the objective
does not prefer the other direction. Folding, merging and dual fixing change the
solution set and are disabled for counting and enumeration.

### Boolean reductions

The implication graph pins each free Boolean, propagates, and records the literals
it forces. Literals on a mutual-implication cycle collapse to one representative,
and a binary clause already implied by a chain of other binary clauses is dropped.
Bounded variable elimination resolves out a Boolean that appears only in clean
all-Boolean clauses and no objective, when the resolvents do not outnumber the
clauses they replace. Blocked clause elimination removes a clause whose resolvents on
some literal are all tautologies, repairing it on reconstruction by setting that
literal. Equivalent-literal substitution, BVE and BCE are satisfiability preserving but
not solution-set preserving, so counting and enumeration disable them. At-most-one
clique merging grows pairwise exclusions into maximal cliques and replaces them with
one at-most-one constraint only when at least two constraints drop. XOR unit
derivation runs a bounded GF(2) elimination over parity rows, including the parity
that integer equalities imply over 0/1 columns, and emits only forced literals,
forced columns and refutations; the rows stay for propagation.

### Symmetry and probing

Symmetry breaking finds candidate interchangeable variables by colour refinement and
individualization-refinement, then verifies each transposition by remapping every
factor and comparing structural-key counts, so a broken symmetry is always genuine.
Each verified orbit is ordered to keep one representative; objective variables are
excluded, and value precedence breaks value symmetry separately. Both keep one
representative per orbit and are disabled for solution-set-sensitive queries. Probing pins each free Boolean in
both polarities: a polarity that propagates to a conflict yields a unit, and a bound
implied under both polarities holds unconditionally as their union. Integer probing
applies singleton arc consistency to bounds and holes, and open-range probing does
the same on source ranges. Probing persists only units and unconditional bounds,
never the binary implications it observes.

### Clausal objectives

Default clausal optimization retains binary exclusion clauses rather than merging
at-most-one cliques into cardinalities. This preserves native SAT propagation and
learning and avoids repeated clique preparation. An explicit `+amo-clique` or
aggressive emphasis permits conversion; nonclausal models and feasibility queries
keep their configured clique policy. A clique replacement is published only after
clique analysis and factor matching finish within their cancellation scope, so a
cancelled replacement leaves the input factors intact.

### Pre-bake LP bounds

The finite pipeline enters pre-bake LP feasibility and bound tightening when a factor
couples at least two distinct nonfixed integer columns whose spans exceed the
large-span threshold captured in the model settings, or couples a wide integer column
to a continuous column. Bound queries use the same threshold per variable, in
widest-first order, for at most 64 columns; narrow columns stay in the relaxation but
keep their declared domains until the root bake. Every accepted tightening requires
the LP bound certificate, and skipping a query retains all source constraints and
feasible values.

### Binary integer channels

Binary-column substitution accepts single-variable reified relations whose truth
differs at 0 and 1 when every reader supports the Boolean replacement. These channels
become Boolean equivalences; general reified rows, value-reading globals and objective
integer columns keep their integer columns. Substituted integers reuse an existing
channel literal with its polarity, removing its redundant equivalence clauses, or
receive a fresh Boolean. Integers are pinned during search and reconstructed from
their value literals, preserving source values and solution counts.

## Source routing and encoder selection

Presolve also decides, together with routing, which owner enforces each part of the
prepared model: finite propagation, an arithmetic theory or both.

Source routing attempts bound closing before rejecting a polynomial composition on
open integer declarations. Proved bounds keep declared holes and original factor
identities, unrelated finite globals keep their finite owner, and a surviving open
side remains arithmetic-owned. Bounded integer-conditioned linear encoders may supply
complete guarded coverage beside an open linear fragment; an uncovered nonlinear open
composition still declines polynomial admission. Native indicator rows join bound
closure only when the source fixes their trigger to the enabling value, and
invocation-local fixings are never permanent source bounds. A declined polynomial
composition keeps its finite owners and compact CP projection; open integer columns
stay unplaced and the missing completion withholds whole-query admission.

Optional passes and bound closing run under the presolve phase token, while required
component installation runs under the containing invocation token. An exhausted
presolve phase can therefore forgo reductions while complete search still installs
every required encoder, and optional exhaustion never turns an uncovered source into
a supported fragment. An interrupted installation keeps its resource decline and
publishes no partial relation. Completed immutable planning can be reused when bound
proving returns the identical model, projections and objective metadata, after
polling the containing stop; reuse renews no allowance.

Source rewrites reselect encoders against the new canonical factors. Finite
preparation forwards its captured projection selection into the deferred root bake;
domain-only rebuilds keep their descriptors, and changed factor sets select providers
against the rewritten model. Narrowing an open side to a proved finite declaration
can create a finite choice owner, but needing arithmetic or exact coefficients cannot
promote unrelated finite declarations. Pre-bake LP queries consume the selected
relaxation descriptors, and a required source check grants an omitted relaxation no
permission to emit default rows. Original value sets, reconstruction and assertion
premises remain authoritative after an encoding is selected.

---

Previous: [11. Resource allowances](11-resources.md) · [Contents](README.md) · Next: [13. Source witness validation](13-validation.md)
