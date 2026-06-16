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
