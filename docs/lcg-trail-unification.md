# LCG trail unification — audit, reproduction, and the principled fix (#671)

Phase-0 deliverable for the SAT+CP trail unification (#671, the "final unification" line item of
the full-LCG epic-of-epics #624). This records what the engine already implements, the one real
remaining defect (measured), and the **principled** architecture that removes it by construction —
not a per-case hotfix.

## What is already implemented (P1–P4, verified)

- **P1 — single chronological trail / one pop.** `PropagationSession.popToLevel` makes a single
  `state.undoTo(mark)` (`Undo.kt`) that unwinds bool pins, int-domain narrowings (+ level / reason /
  antecedent / holes), the #592 reversible cells (`RevInt`/`RevRef`/`RevIntArray`/`RevLongArray` in
  `revTrail`), and the order-atom truths together. No separate SAT vs CP rewind.
- **P2 — int bounds as first-class trail literals.** The #588 ladder atoms (`atomVarGe/Le/Eq`) carry
  truth / level / reason / antecedent at threshold-crossing (`Atoms.kt wakeAtom`), are watched via
  `atomWatchersByLit`, and `pinLit` dispatches an atom literal back to `tightenIntMin/Max` /
  `excludeIntValue` (the bidirectional channel).
- **P3 — propagators as advisors in one loop.** `runToFixpoint` interleaves factor `propagate` and
  atom-wakes in one worklist.
- **P4 — conflict analysis over bool + atom literals.** `ConflictAnalyzer.analyzeFromSeed` runs
  1-UIP over `universe = numBoolVars + atomCount`.
- **Int branching is bound-literal-only.** `makeNode` builds `IntNode` for both the free and fixed
  engines; it branches `[v ≤ s]` / `[v ≥ s+1]` (single literal per decision), never an equality pin.
  So the historical "equality decision → two same-level bound atoms" is already gone.

## The one real defect (measured)

Reproduced on `liner-sf-repositioning` (MiniZinc corpus), 30 s:

| engine | feasible | learned | caNonAsserting / failures |
|---|---|---|---|
| klause `cp-single` (free) | no | 18 | 15 / 33 |
| klause `fixed` (annotation) | no | **0** | **47 / 47** |
| Choco `fixed` | **yes (523360)** | — | — |

Choco solves it; klause finds no feasible solution and, on the fixed engine, **learns nothing** —
every conflict produces a non-asserting clause and the engine backtracks chronologically.

**Root cause (from `ConflictAnalyzer`'s own invariant, lines ~51–55):** bool literals never go
non-asserting because they sit on a chronological trail (`boolPinOrder`) and 1-UIP resolves them in
reverse-derivation order over an acyclic graph. Int-bound **atom antecedents reference current
bounds**, which lets two same-level atoms cite each other (`A`'s reason mentions `B` and vice-versa).
A cyclic same-level atom graph **cannot** be collapsed by 1-UIP, so ≥2 conflict-level literals
survive → non-asserting → `learned=0`. This is structural, not instance-specific.

A hotfix was tried and **reverted on evidence**: collapsing a same-variable `¬[v≥k] ∨ ¬[v≤k]`
conflict-level pair into `¬[v=k]` (equivalence-preserving). It did not help — on the fixed engine no
such pairs existed (learned stayed 0) and it slowed the solver. Patching cycle *shapes* is
whack-a-mole; the cycles take many forms.

## The principled fix (removes non-asserting by construction)

Give atoms the same trail discipline as bool literals, so the implication graph is acyclic and 1-UIP
is always asserting — the actual payoff of the unification:

1. **Time-ordered atom trail.** Every established atom literal gets a real chronological trail
   position in derivation order (bool pins already do via `boolPinOrder`).
2. **Unified reverse-trail 1-UIP pivots.** Pick conflict pivots by the single reverse-trail order for
   bool and atom literals alike; remove the `seenAtomList` allocation-order fallback and the
   cycle-tolerant `resolved` guard.
3. **Time-respecting antecedents.** A deduction's recorded antecedent must reference the bound *as it
   was when the deduction fired* (an earlier trail position), never the current mutual bound. Then no
   same-level cycle can exist — `caNonAsserting` → ~0 because the cycle is unrepresentable, not
   because cases are caught.

This is a core change to `ConflictAnalyzer`'s resolution loop and to antecedent threading across
propagators — the most false-UNSAT-prone area in the solver. It must be gated by: the `liner-sf`
repro (`engine=fixed`: `learned` rises, `caNonAsserting`→~0) **and** the enumerate-vs-brute oracles
across deep backtracking (a wrong antecedent = false UNSAT). It is deliberately *not* shipped as a
rushed change.

## Verification assets on disk
- repro: `./gradlew :klause-bench:bench --args="solve suite=mzn-bench name=liner engine=fixed timeout=30000"`
- baselines captured under `klause-bench/output/` (`klause-fixed-*`, `klause-cp-single-*`, `choco-p1-fixed-*`).


## Update — hands-on attempt: the precise diagnosis and what is/ isn't sound

A direct attempt to remove non-asserting refined the diagnosis, fixed a real **soundness-oracle gap**,
and ruled out the "obvious" fixes by the enumerate-vs-brute oracle. All findings are evidence-backed.

### Two distinct non-asserting causes (proven by dumping conflict clauses on liner-sf `fixed`)
1. **Decision-collateral fan.** An int-bound *decision* on a holey domain (`currentFactor = -1`, no
   reason) crosses many thresholds at once, so `propagateAtomsForVar` wakes a fan of same-variable
   order atoms as independent **decision leaves** (`rsn=-1`). 1-UIP cannot reduce several same-level
   leaves to one UIP.
2. **Propagation crossed-hole cycle (the dominant cause on liner-sf).** A *propagation* whose bound
   move sweeps a variable past pre-existing interior holes re-stamps those crossed-hole eq atoms (via
   `wakeAtom`) to the move's reason, while the move's reason cites those same atoms
   (`antecedentsAcrossHoles`) — a same-variable, same-level mutual citation 1-UIP cannot collapse.
   Dumped clause: 5 conflict-level atoms on one int var, all `rsn=<one factor>`.

### A soundness-oracle gap was found and fixed (the real win)
The test stub `ExcludeOnFix` (in `ProductArrayMinMaxBoundsEventTest` / `ElementDeltaTest`) called
`excludeIntValue(dst, value)` with **null antecedents** — it under-explained a *conditional* exclusion.
`excludeIntValueImpl` then recorded the new bound's reason as just the prior bound (`appendPriorBound`),
dropping *why* the value was excluded. With seeds widened `1L..4L → 1L..300L`, this surfaced as klause
learning an unsound clause and losing a solution at **seed 11**. Root cause: the **test factor
violated the explain-your-forces contract**, not the engine. Fix: `ExcludeOnFix` now cites
`composeIntVarAtomAntecedents([src])`; clean `main` then passes all 300 seeds. (Earlier notes in this
doc speculated a latent *engine* unsoundness — that was this test stub; the analyzer is sound for
contract-compliant factors. A defensive guard that *detects* an under-explained exclusion would still
be worthwhile, since the failure mode is silent.)

### Fixes tried for the non-asserting causes, and their verdicts (all gated by the oracle)
- **Decision-ladder** (a decision's collateral order atoms cite the new-bound atom via the order-ladder
  entailment `[v≥newMin] → [v≥k]/¬[v≤k]/¬[v=k]`): **sound** (full oracle green once the test stub is
  fixed), but a clean A/B on liner shows **no measured win** — `fixed` stays `learned=0`, and
  `cp-single` is `learned=60, caNonAsserting≈120` either way. liner-sf is dominated by cause (2), which
  this does not touch. Not shipped (sound, but no demonstrated benefit, and it adds hot-path cost).
- **Full ladder** (collateral cite the new-bound representative for *every* move, decisions and
  propagations — the natural fix for cause (2)): **unsound** — loses an `AllDifferent` solution and a
  `LinearBoundsEventTest` solution (both real explaining factors, with the test stub already fixed). A
  propagation that sweeps a pre-existing hole re-attributes that crossed-hole atom to the *sweeping*
  move's level/reason, which mis-levels 1-UIP.
- **`wakeAtom` no-re-establishment / gating** (the opposite: keep a crossed-hole atom's *carve-time*
  reason instead of re-stamping it): also **unsound** — produces a bogus unit clause `[iv0≤0]` and
  loses an `AllDifferent` solution `(3,0,5,2,1,4)`.
- **Order-literal subsumption merge** (`finalizeClause`): **sound** but **no win** — liner-sf's
  surviving conflict-level atoms (`[v=33524]`, `[v=51430]`, `[v≥432952]`) do not subsume each other.
- **Defer-leaf 1-UIP**: crashes (`UIP atom undetermined`) and loses solutions.

The common root: a crossed-hole order atom is false for **two** valid reasons at **two** levels (the
carve at `L1`, and "below the new bound" at `L2 > L1`). Conflict analysis needs one consistent
`(level, reason)`. Keeping `L1` (gating) corrupts one way; attributing `L2` (ladder) corrupts the
other. No `wakeAtom`/analyzer patch escapes this — it is structural.

### The remaining principled fix (dedicated, sound-by-construction)
Eight distinct patch-level attempts (above) were each rejected by the enumerate-vs-brute oracle or
showed no win. The only robust route is to make the order literals genuine first-class trail entries:
**order-ladder consistency as real entailed clauses** the analyzer resolves through, with order-atom
truth flowing from **BCP over those clauses** (not domain-sweep), so each atom has exactly one
establishment. The infrastructure exists — clauses already carry atom literals, BCP watches them
(`atomWatchersByLit`), `pinLit` unit-propagates them with recorded antecedents
(`propagation/ClauseDb.kt` `addLearnedClause`). This is a large, multi-session rewrite of the most
false-UNSAT-prone code, **not** a patch. **Gate any such change on the enumerate-vs-brute oracle under
many seeds** (the single-seed oracle hid the `ExcludeOnFix` gap for the life of that test).

## Phase-0 baseline measurements (the rewrite's before-state)

Captured on `main` at the order-literal-rewrite branch point, `engine=fixed` unless noted. Gate the
rewrite against these: `learned` should rise and `caNonAsserting` fall, with no enumerate-vs-brute
regression under the multi-seed harness.

liner-sf-repositioning (the canonical case), 30s:

| engine | nodes | failures | learned | caNonAsserting | caNotApplicable | feasible |
|---|---|---|---|---|---|---|
| `fixed` | 101 | 173 | **0** | **173 (100%)** | 0 | no |
| `cp-single` | 620 | 167 | 60 | 107 (64%) | 0 | no |

The pathology is **not** liner-specific. A 30-instance mzn-bench sample (`per-family=1 max=30 seed=1`,
`engine=fixed`, 10s each) shows 15% of all conflicts non-asserting *overall*, but severely
concentrated — a whole class learns almost nothing while others learn fine:

| non-asserting rate | instances (sample) |
|---|---|
| ~100% | carseq, cvrp, costas-array |
| 97–99% | black-hole, diameterc-mst, amaze, bacp, crosswords |
| 73–86% | depot-placement, elitserien-handball |
| 0–7% (healthy) | cargo, carpet-cutting, community-detection, cyclic-rcpsp, cryptanalysis, fast-food, … |

So the order-literal rewrite is not a one-instance fix: it targets the class of holey-int /
order-atom-heavy models (≈ a third of the conflict-bearing sample) that currently discard nearly all
their learned clauses. `caNotApplicable` is ~0 everywhere, so the loss is non-asserting clauses
(the same-variable order-atom fans/cycles), not missing conflict reasons.

## Core-rewrite progress: the single-establishment (gating) piece is verified sound

Building toward the order-literal trail rewrite, the **single-establishment** piece — `wakeAtom` does
not re-stamp an already-determined order atom (it keeps the atom's first establishment, only firing
watchers on a re-cross) — was implemented and **passes the full enumerate-vs-brute oracle** (all 1686
tests, plus the multi-seed harness; an added "kept-atom reason must be currently false" assertion never
trips). Earlier this same gating looked unsound — that was the unfixed `ExcludeOnFix` stub plus the
buggy #1–#3 scaffolding; on clean `main` (stub fixed, merged via #696) gating is sound on its own.

So the establishment half of "each order atom has exactly one `(level, reason)`" works. It is **not yet
a win on its own**: with gating, the crossed-hole atoms a snapped bound move sweeps become
`lvl = -1` (history-derived) and form a same-variable conflict-level fan — e.g.
`[v≤51429] ∨ [v=51430] ∨ [v≥432952]` — that does not subsume and does not resolve to the bound, so
liner-sf stays `learned = 0`. Root of the residual: those crossed-hole atoms have **no recoverable
reason pointing at the cause that excluded them** — `holeHist` records only *pure interior* carves, not
edge-excluded holes, so `holeReasonFor` returns null and they degrade to conflict-level leaves.

### Remaining piece (now fully specified): ladder-clause BCP for the collateral
The fix that pays off, sound-by-construction:
1. Lazily materialize order-ladder consistency clauses between adjacent **materialized** atoms of each
   int var (`(¬[v≤a] ∨ [v≤b])` for a<b, the GE↔LE complement, the EQ↔bounds links). Huge domains are
   fine — only materialized thresholds get clauses.
2. A bound move wakes **only its representative** order atom (the new bound), with the move's real
   reason; the collateral order atoms it currently sweeps are instead propagated by **BCP over the
   ladder clauses**, each taking the ladder clause as its (valid, entailed) reason.
3. Then conflict analysis resolves every collateral atom → ladder clause → representative bound →
   the move's other-variable reason. The same-variable fan collapses to one literal; non-asserting
   becomes structurally impossible, and it is sound because the ladder clauses are real axioms (no
   hand-rolled antecedent, no re-attribution).

Gate: the multi-seed harness (bump seeds locally) + full oracle at every step, and the liner-sf /
mzn-bench baseline above for the payoff measurement. Build it behind a default-off flag so the current
(sound) path stays the default until the new path is both sound and a measured win.

## Core-rewrite: the irreducible blocker (traced to the source)

The non-asserting fan was traced all the way down. On a snapped/holey bound move,
`Mutators.kt`'s `antecedentsAcrossHoles` records the crossed **same-variable** hole atoms `[v=h]`
(and `appendPriorBound` the prior same-variable bound) into `intMin/MaxAntecedents[v]`. Since
*every* one of `v`'s order atoms resolves to that per-variable move reason
([atomAntecedentsDerived]), the reason citing `v`'s own atoms is a same-variable self-cycle 1UIP
cannot collapse — proven by dumping a conflict: each conflict-level `iv115` atom resolves to a reason
listing `iv115`'s own atoms.

Both ends were attempted and the wall is real:
- **Analyzer side** (all oracle-green, but no win on their own, because the cycle is in the reason,
  not the analyzer): single-establishment gating; `clearEqIfFreed` keeping the carve slot; deriving a
  looser atom's reason as the bound's move reason; growing the 1UIP frontier to resolve atoms
  materialised mid-analysis.
- **Source side** (the real fix — make the snap reason cite the holes' *carve reasons* over other
  variables instead of `[v=h]`): requires every value-exclusion to record its reason. Extending
  `pushHoleHist` to all exclusions (interior + edge, single + batch) makes `holeReasonFor` complete,
  but folding those carve reasons into the snap reason is **unsound** — it loses solutions on
  AllDifferent (a real explaining factor): a value's carve reason, folded into a *later* snap,
  over-attributes / mis-levels the learned clause (the per-variable store has no per-value
  establishment *level*, only the reason).

**Conclusion (evidence-backed):** the per-variable bound-reason representation cannot carry what a
sound, collapsing nogood needs — each order literal needs its **own** establishment level + reason
(true first-class trail residency), or the order-ladder consistency must live as **real DB clauses**
so resolution flows through entailed axioms. That is the genuine multi-session, soundness-critical
rewrite; no in-place patch over the per-variable store is both sound and collapsing. The harness +
baseline above are the gate and yardstick for whoever lands it.

## Resolution — the same-variable cycle IS broken in place (sound, oracle-green)

The "irreducible blocker" conclusion above was **wrong about the route, right about the requirement**:
each order literal does need its own establishment level + reason — but that can be recorded *in
place*, without folding carve reasons into the snap reason (the unsound step that defeated the prior
attempt). The fix that lands it (commit on this branch):

1. **Record each eq atom's reason at its first death, in the move's NEAR region** (`recordEqDeath` in
   `Atoms.kt`). A value killed below the raised min / above the lowered max *requested* bound is ruled
   out by that requested bound alone, whose reason cites **other variables** (`antNear`): a sound,
   acyclic per-value reason (`antNear ⟹ v ≥ reqMin ⟹ v ≠ k` for `k < reqMin`). Recorded once
   (guarded), truncated with the move on backtrack. The earlier attempt recorded a *later* snap's
   reason for a *pre-existing* hole — which the snap does not actually exclude — and that was the
   unsoundness. First-death-only sidesteps it: the first time any value dies it is always in some
   move's near region (or a pure-interior carve, already recorded).
2. **A crossed hole derives/wakes with its recorded carve reason, not the bound move** (`wakeAtom`
   + `atomAntecedentsDerived` + `falseBoundReason` consult `holeReasonFor`/`holeHistHas`). The eq
   atom then resolves to the other variables that excluded it, off the same-var fan.
3. **Cite the requested-bound atom only for a *decision* move** (`antecedents == null`, in
   `tightenIntMin/MaxImpl`). A propagation already carries `antecedents` that imply the bound, so the
   self-citation was redundant *and* the last surviving cycle (the prior-bound LE/GE atom citing the
   move whose reason lists it).

Result: the EQ/LE same-variable fan collapses; `learned` rises from 0 on the holey-int class
(black-hole 14→712, crosswords 10→39, bacp's asserting rate 1.4%→3.9% at 15 s) and the full oracle +
multi-seed adversarial harness stay green. **What it does NOT yet fix:** a *second*, distinct
non-asserting shape where two propagated **bool** literals sharing one antecedent fail to collapse
because the 1-UIP pivot scan carries a monotonically-decreasing `boolPinOrder` cursor and never
revisits a bool an atom-pivot's reason cites below it. A naive rescan-from-top is unsound (it resolves
atoms the ping-pong guard deliberately keeps as leaves), so that one is a separate, careful follow-up
in `ConflictAnalyzer.analyzeFromSeed` — it is *not* an order-literal issue.

## Follow-up — the residual non-asserting is bounded by the trail representation, not a missing patch

Investigating the residual (after #704) reduced it to one root: the monotonic `boolPinOrder` cursor in
`analyzeFromSeed`. It has two manifestations, both confirmed by dumping conflicts:

- **Stranded bool** — a bool an atom-pivot's reason cites sits at a trail position the cursor already
  passed; never revisited, it lingers at the conflict level.
- **Decision surfaced early** — the cursor returns a level's *decision* atom (assigned first, low
  position) while propagated atoms it stranded *above* the cursor are still seen; the decision is a leaf,
  so the loop drains the propagated atoms as leaves → non-asserting (e.g. costas-array: `iv4/LE/6` drained
  with `clc=5`).

Patching the *analyzer* around a dirty trail proved impossible — each attempt was unsound:
- **Rescan the pin trail from the top each iteration** (pick the genuinely most-recent seen literal):
  UNSOUND on a dirty trail — loses solutions on AllDifferent, because an atom's `boolPinOrder` position
  was its *wake* order, not the level its truth was decided, so "most-recent on the trail" ≠
  reverse-assignment order for atoms.
- **Defer a leaf pivot** (resolve a still-resolvable current-level literal before the decision):
  UNSOUND — same root. Reordering atom resolution off the trail order corrupts the clause.

**Conclusion:** resolving atoms in correct reverse-assignment order — the prerequisite for collapsing
*both* manifestations — requires each order literal to carry a real, single trail position. That is the
trail-residency rewrite below; once the trail is clean the rescan is sound and subsumes both the
stranded-bool and decision-early cases at once.

## The trail-residency rewrite — completed (sound, oracle-green, a measured win)

The blocker was that an order literal could carry *multiple, mis-dated* trail entries: a hole carved at
level 3 then swept by a bound move at level 5 was re-stamped to level 5, and `clearEqIfFreed` reset
still-excluded holes to "derive from history" — so an atom's `boolPinOrder` position no longer matched
the level its truth was decided, and reverse-assignment-order resolution was impossible. Two changes
give every determined order literal **one consistent trail entry** (position ↔ establishment level):

1. **Single establishment in `wakeAtom`** — stamp the slot (and append to `boolPinOrder`) only when the
   atom's truth actually *flips*. A later bound move that re-crosses an atom already at that truth leaves
   its first establishment — the level/position/reason where its truth was really decided — intact.
2. **`clearEqIfFreed` keeps the slot** for a still-excluded hole on backtrack. Under single establishment
   the slot holds the carve's establishment, still in force until the carve's own undo fires; its
   `boolPinOrder` entry sits below the backtrack mark and survives the truncation. (The old "reset to
   derive-from-history" existed *only* to paper over the re-stamp that single establishment removes.)

With the trail clean, the analyzer resolves atoms exactly like bools: **`analyzeFromSeed` rescans the
unified pin trail from the top each iteration** for the most-recent seen current-level literal. Because a
reason now only cites earlier-established (lower-position) literals, the rescan always converges and never
strands — the monotonic cursor, the separate atom-scan-as-primary, the bool-recovery and the
leaf-drain-stranding all go away. (A `seenAtomList` fallback remains only for atoms materialised
mid-analysis, which are never on the trail.)

Soundness gated throughout by the multi-seed adversarial enumerate-vs-brute harness + full `:klause:jvmTest`.
Measured A/B vs the pre-rewrite engine (`engine=fixed`, 15 s, holey-int):

| instance | before | after |
|---|---|---|
| black-hole | UNKNOWN (unsolved), 17270 nodes, 25821 non-asserting | **solved**, 1633 nodes, 293 non-asserting |
| bacp | obj 67, 4896 nodes, 3856 non-asserting | obj 67, **2190 nodes**, 16 non-asserting |
| talent-scheduling | cost 122, 17050 nodes | cost 122, 13926 nodes |

Asserting rate on the holey-int sample rose from ~3–15 % to ~68 %; conflict counts collapsed by 10–80×, so
the per-iteration rescan is a net win (far fewer conflicts to analyse) with no throughput regression. The
costas-array remainder is now a handful of genuinely multi-decision conflicts, not a structural fan.
