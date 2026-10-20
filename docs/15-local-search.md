# 15. Local search

Complete search explores the space exhaustively and can prove results. Local
search gives that up in exchange for speed on large or loosely constrained models:
it looks for feasible or improving assignments by changing values and measuring
constraint violations. It keeps incremental scores so a candidate move can be
evaluated without rebuilding the whole model. Defined outputs and coordinated moves
keep related coordinates consistent as the walk progresses.

A low score guides the walk; it does not prove feasibility, optimality or
infeasibility. Every candidate still passes the same
[source validation](13-validation.md) as a complete-search witness, including
arithmetic completion, before it is published, so local search can share incumbents
with complete search in the [portfolios](16-portfolio.md) of the next chapter.
Caller scopes follow [sessions](10-sessions.md), and work is charged against the
[resource allowances](11-resources.md) of the containing solve.

## The walk

A run holds one complete assignment of the working model and repeats one loop:

1. **Generate.** Move sources propose candidate moves for the current assignment.
   A move is a primitive `BoolFlip`, `IntSet` or `RealSet`, or a `Compound` of
   primitives applied as one transition.
2. **Score.** Each candidate is graded by the change it would cause in the
   model's violation, and at feasibility in its objective.
3. **Select.** Tabu, configuration checking and an acceptance rule choose one
   candidate, or none.
4. **Apply.** The chosen move is committed; maintained definitions recompute
   their outputs and every affected factor reconciles its score.
5. **Restart.** When the walk stalls, a restart policy perturbs or replaces the
   assignment.

Each factor contributes an `Invariant`: a maintained constraint with a graded
**violation degree**, zero when satisfied and growing with the distance from
satisfaction. The hard cost is the sum of those degrees, not a count of violated
factors, so tight arithmetic and global constraints give the walk a gradient. A
candidate's `deltaIf*` result must equal the exact degree change the move would
cause; committed moves reconcile cost and violated membership from the exact
post-move degrees. The immutable invariant projection is shared; mutable payloads,
the assignment, weights and random state belong to each `LocalSearchState`.

Optimization uses two phases. While the hard cost is positive, the walk fights for
feasibility. At zero cost it descends on the objective, either through the
strategy's own feasibility-preserving moves or, for violation-native strategies, by
treating an `objective ≤ incumbent` ratchet as one more constraint. Every strategy
therefore optimizes on an optimization model; a satisfaction model ends at its first
feasible point.

## Subchapters

The subchapters follow a run from its strategy to its lifetime:

1. [Strategies and moves](15.1-strategies.md) describes the four axes every
   strategy is built from: move sources, scoring, acceptance and schedules, with
   tabu and the named recipes built over them.
2. [Preparation and definitions](15.2-preparation.md) covers how a run prepares
   its projection and initial assignment, and how one-way definitions, repairs and
   literal channels keep derived coordinates consistent.
3. [Scoring and neighborhoods](15.3-scoring.md) covers reversible scoring probes,
   selected scoring projections, continuous scores and the structured
   neighborhoods of NValue, Cumulative and Diffn.
4. [Completion, restarts and ownership](15.4-lifetime.md) covers completing
   continuous coordinates, exact coordinate neighborhoods, restart anchors, ALNS
   seeding, resumable handles and residual reporting.

---

Previous: [14.5 Primal heuristics and statistics](14.5-primal-and-statistics.md) · [Contents](README.md) · Next: [15.1 Strategies and moves](15.1-strategies.md)
