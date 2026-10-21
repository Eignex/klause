# 7. Presolve and validation

With a scope and an allowance in place, the first thing a solve does is prepare
the model. Presolve reduces it before search while retaining the information
needed to interpret results in the original source. It can fix variables,
substitute expressions, remove redundant constraints or strengthen bounds. The
required preservation guarantee depends on the operation: satisfiability,
optimization and counting need different contracts.

Preparation applies only reductions justified for that query, records
reconstruction and objective mappings, and charges its work to the preparation
phase of the [resource allowance](06-sessions.md#resource-allowances). This
chapter gives the shape of the pipeline, the guarantees each reduction must
declare, and how rebuilt models and phase accounting are owned. It closes with
source witness validation, the check every engine's assignments must pass. The
search that runs on the prepared model is the subject of the
[next chapter](08-search.md).

## Pipeline

Presolve runs in phases over one model. A source phase works on the declared model
before the root bake and catches cheap refutations, such as a gcd-indivisible
equality, before propagation would narrow a wide domain one step per round. The
root bake then establishes the propagation fixpoint of the root model; optional
failed-literal and singleton arc consistency tiers extend it from the presolve lane,
so the engine never depends on presolve. Finite rounds follow under one
bounded-fixpoint scheduler shared with the source lane: a pass above the cheapest
tier reruns only after other passes changed at least 1% of the model. Presolve and
an LP-relaxation harvest iterate to a joint fixpoint for at most four rounds, the
harvest rerunning only when it tightened something.

The round-boundary diminishing-return check uses a model-size heuristic: finite
hosts count live factors plus integer-domain endpoint spans, with both individual
spans and the total saturating at `Long.MAX_VALUE`. Incremental and fresh-rebuild
hosts use the same nonnegative calculation. This size measure does not estimate
measured runtime utility; saturation can hide a reduction until the total fits.

| Family | Passes |
|---|---|
| Linear | Coefficient strengthening, Diophantine bound tightening, linear bound fusion, affine singleton elimination, sub-sum aggregation, dependent-equality removal, singleton inequality projection |
| Structural | Redundancy and subsumption, structural reduction, comparison-clause folding, duplicate-column merging, dual fixing |
| Boolean | Implication graph, bounded variable elimination, blocked clause elimination, at-most-one clique merging, XOR unit derivation |
| Symmetry | Symmetry breaking and value precedence |
| Probing | Failed literals, integer bounds and holes, open ranges |
| Around the rounds | Binary column substitution, LP harvest, difference-system posting |

`PresolveEmphasis` selects the cost tiers: `off` runs nothing, `conservative` applies
the cheap tier once, `default` iterates cheap and moderate tiers to a fixpoint and
`aggressive` adds exhaustive probing, all within 16 rounds. Passes can be enabled or
disabled by name, but a pass that would break the query's required guarantee stays
disabled. Explicitly enabling a problem pass under `off` gives both schedules the
default round cap; an explicit zero round cap still suppresses those schedules.

## Guarantees and source mappings

`PresolvePass.guarantees` separates equisatisfiability, objective preservation,
witness reconstruction, bijection and complete coverage by reconstructed source
assignments. Objective preservation depends on the pass context protecting objective
columns. A satisfiability reduction can keep an optimum while losing source
multiplicity, so successful reconstruction does not establish a bijection.

Affine elimination carries `AffineValue` records through source and finite presolve,
evaluated with exact integer arithmetic and replayed in reverse elimination order.
Finite Boolean elimination, blocked-clause repair, implication merging and singleton
projection retain the same typed rebuild records as their source forms. Binary
substitution records each integer's carrying literal and the source Boolean namespace
size; reconstruction restores later Boolean reductions before reading those literals,
then truncates appended Booleans. Composition preserves that order and the earliest
source namespace.
`SourceMapping` binds a phase and its reconstruction to the actual source and reduced
model objects; composition requires the preceding target to be the following source,
and equal variable or factor counts do not establish that association. A
reconstructed column is excluded from direct definition and search hints; untouched
columns keep their source orientation and ordered branching hints. A custom
reconstruction without direct correspondence withholds hint projection.

Objective adjustments map reduced values into source units as `scale * value +
offset`, with positive exact rational scale, and compose by multiplying scales.
Current reductions protect objective columns and use the identity adjustment.
Independent source evaluation checks the composed adjustment against reconstructed
witnesses. A mapping never issues a proof certificate. Optional
`SourceMapping.captureSolve`, `captureMinimize` and `justification` retain reduced
evidence and typed obligations for
[original-source derivations](13-evidence.md); a transformed terminal cannot gain
original admission from a reduced label, and original-source witness checking
remains required.

Enumeration, counting and sampling require a bijection even when a pass is
explicitly enabled, so multiplicity-changing reductions stay off for them. Projected
enumeration deduplicates the requested coordinates; hash cells stop after `cap + 1`
distinct projections. An exhausted cell supports an exact count or uniform draw; an
interrupted cell supplies only a lower bound.

`SourceSetup.execute` runs preparation-only requests. Finite requests return a
`PreparedOnly` envelope; open linear requests return
`SourceExecutionResult.Prepared` with the original model and native preparation.
These results carry diagnostics only, with no search publication or terminal.

## Rebuild ownership

For a selected private float grid, finite presolve reconstructs into the private grid
model first, then the invocation's explicit grid map lifts to original real
coordinates before source checking. Source and engine plans keep separate
namespaces, and a foreign source, target or consumer cannot reuse the map; see
[private float projections](02-model.md#private-float-projections).

[PresolveShared.rebuildProblem](../klause/src/commonMain/kotlin/com/eignex/klause/presolve/PresolveShared.kt)
and [RootBaker.reseed](../klause/src/commonMain/kotlin/com/eignex/klause/presolve/RootBaker.kt)
carry the originating cancellation token and immutable `ProblemSettings` into every
fresh `BakedProblem`, so deadlines, meters and the pre-bake span gate keep their
scope across rebuilds and reseeds. A fired token skips root-probe phases and stops
propagation at its polling boundaries; partial propagation is sound but can forgo
deductions. Cancellation is cooperative, and probe caps do not bound wall time.
Finite probing and implication harvesting retain the pass token, including its
native-work meter. Value precedence uses that token throughout value-orbit
discovery and stops posting chains when it fires.

## Phase allowance and telemetry

[PresolveBudget](../klause/src/commonMain/kotlin/com/eignex/klause/presolve/PresolveBudget.kt)
owns the phase's deterministic work allowance. Rebuilding a problem neither creates
another allowance nor resets consumed work.

Root propagation reports native work through a propagation-owned meter, and presolve
converts each construction entry and unit of propagation work to 53 phase units.
Construction entries are a structural size proxy over columns, factors and
structural keys; propagation work counts factor fires, literal reads and wakeups,
including native SAT BCP. Each fresh root, probe and repair reports construction once
and flushes deltas before each poll, so nothing is billed twice.

A spent allowance prevents entry into another probe, repair or tier. A completed
failed probe keeps its proven forcing, exclusion or hole, but an interrupted
hypothetical probe publishes nothing. Construction is mandatory and charged even
under a zero allowance. Propagation polls every 256 fires or SAT trail entries, so
a single operation can overrun; the weights are not a time bound.

The CLI pairs the work allowance with an elapsed-time ceiling of the same derived
duration, kept through routing and preparation. Either limit can stop optional
passes, so elapsed cancellation can make deductions and route selection depend on
host speed. `presolve-work-limit=unlimited` removes only the work cap. Effort
telemetry reports construction, propagation work, converted `presolveWork` and probe
calls separately.

Unchanged and allowance-stopped source phases retain the same effort ledger as
successful reductions. Source preparation time excludes theory search; polynomial
encoder rebuilding belongs to preparation. Routes without a factor preparation
report the retained allowance and counters with zero time for that phase.

## Selected reductions

The passes listed under [Pipeline](#pipeline) share the guarantees above. This
section describes what each family deduces and which guarantee it needs.

### Linear reductions

Coefficient strengthening divides an integer or pseudo-Boolean row by the gcd of its
coefficients and rounds the bound inward; an indivisible equality is infeasible.
Diophantine tightening confines each `x_i` in `sum(a_i * x_i) = b` to one residue
class modulo `gcd(a_j : j != i)` and moves its bounds to the nearest member.
Residue extraction and source-bound narrowing poll the phase token. Finite carving
charges each live value visited and polls in chunks of at most 64 values; interruption
discards the unfinished carve and retains completed narrowings.
Dependent-equality removal runs fraction-free elimination over declared unconditional
integer equality rows, dropping a redundant factor only when its complete meaning is
one equality, and refuting a block with no rational solution. Multi-row conjunctions
and relaxations can supply premises but remain intact; disjunctive and reified rows
are excluded. Elimination polls between row combinations and during support merges,
charges visited entries and discards every partial deduction on interruption. Sub-sum
aggregation replaces a row's copy of a defining equality's right-hand side by its
variable. Its candidate and term visits charge and poll the phase token. Rarest-partner
incidence counts and row widths estimate each scan before entry against a work cap
proportional to indexed terms, with an absolute ceiling. Unaffordable scans are
skipped; interruption retains only completed rewrites and their unchanged defining
equalities. Linear bound fusion merges matching upper and lower bounds into an equality
or refutes crossing ones. These preserve the solution set. Affine singleton
elimination and singleton inequality projection need reconstruction, and the
projection is off for solution-set-sensitive queries.

### Structural reductions

Structural reduction offers each factor its `structuralReduce` hook under current
domains, so a pinned global becomes a simpler factor; on source models it reaches
only factors over closed integer columns. Redundancy and subsumption remove
duplicate and dominated rows using indices maintained across rounds.
Clique-implied subsumption builds and extends the conflict graph only when a
surviving factor is an unconditional single-row Boolean `<=` knapsack with positive
weights. Its activity bound uses cliques backed by retained clauses and
cardinalities, so dropping a knapsack cannot remove its own justification.
Comparison-clause folding replaces a clause over sole-use reified comparison
indicators with one comparison disjunction. Source and finite reconstruction restore
every consumed indicator from its defining comparison, before clause polarity, after
later integer reconstructions and before original-source checking or output. These
Boolean columns are excluded from direct definition and search hints. Duplicate-column
merging replaces two columns with identical linear occurrences by their sum and splits the value back on
reconstruction. Dual fixing pins a variable to a bound when no occurrence or the
objective prefers the other direction. Folding, merging and dual fixing change the
solution set.

### Boolean reductions

Finite probing and implication harvesting count only Booleans with live factor
occurrences and no root deduction toward their candidate caps. An empty candidate
set needs no probe root. The implication graph pins each candidate, propagates and
records forced literals; mutual-implication cycles collapse to one representative
and transitively implied binary clauses drop. Graph construction, node and edge traversal, and SCC
discovery charge and poll in bounded chunks. Interrupted reachability restores the
candidate clause's edges, and incomplete SCC discovery publishes no merges. Bounded
variable elimination resolves out a Boolean in clean clauses and no objective when
distinct resolvents neither outnumber the removed clauses nor exceed their literal
volume, counting each literal once per clause. Each pivot permits at most 4,096
clause pairs, width 64 resolvents and 65,536 candidate, occurrence, literal and pair visits.
Visits charge and poll the phase token; a declined or interrupted pivot publishes
nothing, retaining completed eliminations and their reconstruction. These local
representation bounds do not guarantee a search-time improvement.
Blocked clause elimination removes clauses whose resolvents on some literal
are tautologies, repairing them on reconstruction. These are satisfiability
preserving only. At-most-one clique merging replaces pairwise exclusions with one
constraint when at least two drop. XOR unit derivation runs bounded GF(2)
elimination over parity rows, including parities implied over 0/1 integers, and
emits only forced values and refutations.

### Symmetry and probing

Symmetry breaking finds candidate interchangeable variables by colour refinement,
verifies each transposition by remapping every factor, and orders each verified orbit
to keep one representative; objective variables are excluded, and value precedence
breaks value symmetry. Both are off for solution-set-sensitive queries. Probing pins
each free Boolean both ways: a conflicting polarity yields a unit, and a bound implied
by both holds unconditionally. Integer and open-range probing apply singleton arc
consistency to bounds and holes. Open-range compilation, incidence construction,
root-row visits and queued incidence scans charge and poll throughout, including
individual row terms. Interruption keeps proved root bounds and rolls back the
active hypothetical branch before publication. Only units and unconditional bounds
persist.
Source probing and symmetry defer to finite preparation for models containing only
closed discrete columns. With real columns present, their supported Boolean and
integer reductions run in the source phase even when every integer column is
closed or absent; generator lex and value-precedence chains stay finite-owned.

### Clausal objectives

Default clausal optimization keeps binary exclusion clauses rather than merging
at-most-one cliques into cardinalities, which preserves native SAT propagation and
learning. `+amo-clique` or aggressive emphasis permits conversion. A clique
replacement is published only after analysis completes within its cancellation
scope.

### Pre-bake LP bounds

The finite pipeline runs pre-bake LP bound tightening when a factor couples two
nonfixed integer columns whose spans exceed the large-span threshold, or couples a
wide integer column to a continuous one. It queries at most 64 columns, widest
first, and every accepted tightening requires the LP bound certificate.

### Binary integer channels

Binary-column substitution turns single-variable reified relations over a 0/1
integer into Boolean equivalences when every reader supports the replacement,
reusing an existing channel literal where one exists. General reified rows,
value-reading globals and objective columns keep their integers. Substituted values
are reconstructed from their literals, preserving source values and counts.

## Source routing and encoder selection

Presolve also decides, together with routing, which owner enforces each part of the
prepared model: finite propagation, an arithmetic theory or both.

Routing attempts bound closing before rejecting a polynomial composition on open
integer declarations. Proved bounds keep declared holes and factor identities,
unrelated finite globals keep their finite owner, and a surviving open side stays
arithmetic-owned. Invocation-local fixings are never permanent source bounds. A
declined composition keeps its finite owners, and the missing completion withholds
whole-query admission.

Optional passes and bound closing run under the presolve phase token, while required
component installation runs under the containing invocation token. Exhausting the
phase can forgo reductions, but never skips a required encoder or turns an uncovered
source into a supported fragment. Source rewrites reselect encoders against the new
canonical factors; original value sets, reconstruction and assertion premises stay
authoritative after selection.

Factor-backed polynomial execution runs the same source-safe schedule before
rebuilding polynomial ownership and encodings. Its typed source mapping reconstructs
Boolean and integer coordinates before original-source checking and publication,
including streamed optimization incumbents. Native formulas without a canonical
factor view keep their native preparation; disabling presolve is accepted on every
route, while individual reductions require their input representation.
Original-source checking after reconstruction spends the containing arithmetic
allowance and retains an already checked publication if later work stops.

## Source witness validation

Presolve hands search a transformed model, and the following chapters describe
the engines that look for assignments of it. Before any of those engines can
publish an assignment, it must be checked against the original source. Every engine
in the rest of this part relies on this check: a solver's own view of the model is
never the authority on whether a point is a solution.

`solver.validation` owns reusable checks of proposed source coordinates. Search,
local search, LP adapters, evidence and pipeline or portfolio orchestration call
these services; checking does not require an orchestration owner. A check has three
outcomes. Acceptance means the point satisfies every checked source obligation.
Rejection refutes the proposed point, never the source. Indeterminate means a
required checker was unavailable or the check was interrupted. Publication
identity, restrictions, objective scope and certificate admission remain with the
[portfolio](12-portfolio.md#witnesses-and-proof-scope) and [evidence](13-evidence.md) callers
described later in this part.

### Finite points

`composedFixpoint` creates a private propagation session for the full Boolean and
finite integer point and checks the selected original-source relations. The point is
the session's private root, so incompatible caller pins still refute it. Prepared
custom providers keep their selected factories and required original checks.
Cancellation or unavailable required checking returns indeterminate. Every exit
reports the propagation work through the caller's accounting hook, and no scratch
state survives a check.

Exact point checking also validates integer absolute-value and distinctness
relations on authoritative arbitrary-width values. `AllDifferent` compares every
present position, preserving presence-literal polarity and exempt values, and
missing coordinates reject the point.

### Open and real coordinates

`verifyOpenWitness` checks discrete coverage, declared integer bounds and value sets,
then every original relation touching a real coordinate, then projects the remaining
discrete relations into a singleton-domain finite check. An unavailable real checker
stays indeterminate. It invents no bounds for open declarations, and
arbitrary-width integers go to their exact theory checker instead.

`verifyRealCoordinates` checks authoritative rational coordinates against source
bounds and supported exact relations, including strict and reified rows. Missing
coordinates or a factor without a complete checker stay indeterminate; algebraic,
symbolic and wide-integer checks belong to their specialized owners. Visits are
charged to the caller, and rational powers compare normalized numerators and
denominators, using bit-length bounds to refute impossible equalities before
allocating.

Finite real domains compare their captured binary64 literals as exact rationals. An
isolated algebraic root matches a rational member when the literal lies inside its
isolating interval and zeroes its defining polynomial, without opening a polynomial
solver.

### Projected and tolerance-qualified witnesses

`verifyProjectedSourceWitness` combines original relation checking with integer
declarations and a private composed fixpoint over captured descriptors. A caller
callback can add a restriction but cannot replace relation coverage. Exact policy
uses the full original real graph. Nonzero tolerance has a complete adapter for
unconditional non-strict linear comparisons and active indicator rows; strict or
reified tolerance semantics stay indeterminate. A private-grid relation cannot
certify its own original real relation; see
[private float projections](02-model.md#private-float-projections).

`finiteWitnessVerifier` combines the finite point check with exact real checking or
the caller's explicit tolerance policy, checking pins and deduced bounds directly on
the completed point. A tolerance callback requires complete finite real coordinates
and does not change exact evidence semantics. See
[model ownership](02-model.md#published-assignments-and-objectives) for coordinate
lifetimes.

Original linear-source checking evaluates original rational rows and declaration
bounds before normalization. Integrality stays exact; row slack is the larger of the
absolute tolerance and the relative tolerance times the larger of bound magnitude and
the sum of absolute term activities, with cancelling terms kept separate. Accepted
points record whether nominal relations also hold, and tolerance acceptance never
transfers nominal proofs. Unmetered checks poll cancellation at most every 64 visits.
A captured acceptance check carries a separate permission for floating optimum
completion, which zero tolerance withholds.

### Dependencies

The shared services depend on source and factor descriptions, propagation, neutral
sample and verification contracts, exact rational arithmetic and model-facing exact
row views. They do not depend on pipeline, portfolio, backtracking, local-search,
evidence orchestration or concrete theories.

---

Previous: [6. Sessions and resources](06-sessions.md) · [Contents](README.md) · Next: [8. Search and propagation](08-search.md)
