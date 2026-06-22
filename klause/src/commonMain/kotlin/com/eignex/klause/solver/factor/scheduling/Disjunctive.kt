package com.eignex.klause.solver.factor.scheduling

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.Invariant
import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.solver.factor.OptPresence
import com.eignex.klause.solver.factor.remapLits
import com.eignex.klause.solver.factor.remapVars

/**
 * Disjunctive (one-machine / unary-resource) constraint: tasks must not overlap in time.
 * Task `i` occupies `[starts(i), starts(i) + durations(i))`; for any two tasks `i ≠ j`
 * one must end before the other starts.
 *
 * Semantically the unary special case of [Cumulative] (`resources(i) = 1`, `capacity = 1`),
 * and the LS side delegates to a private cumulative for cost, repair moves, and the
 * incremental usage timeline — the disjunctive surface is identical, no reason to copy.
 * The win is on the propagator: disjunctive admits much stronger reasoning than cumulative
 * because at any time point at most ONE task runs, which collapses energetic arguments to
 * pure time-window arithmetic. This factor ships three propagation passes layered together:
 *
 *  1. **Time-tabling** (same as cumulative with cap = 1). For each task with `lst < ect`,
 *     the mandatory part `[lst, ect)` is reserved; any other task whose start would land
 *     inside that window has its domain endpoints shaved.
 *  2. **Pairwise detectable precedences**. For every ordered pair `(i, j)`: if `est_i +
 *     dur_i > lst_j` then `i` cannot end before `j` must start, so `j` is forced before
 *     `i` and `start_i.min ≥ est_j + dur_j`. Catches the "two tasks both want to run on
 *     the resource and one is provably first" pattern that drives most JSP / SMT
 *     scheduling decompositions.
 *  3. **Edge-finding (Vilím Θ-tree, O(n² log n))**. Routed through `CumulativeThetaTree` at
 *     capacity 1 — the unary special case. For each LCT threshold τ the envelope
 *     `Env(Θ_τ) = max_{Ω⊆Θ_τ} (est(Ω) + e(Ω)) = ect(Θ_τ)`; a task `i ∉ Θ_τ` whose insertion
 *     pushes `Env(Θ_τ ∪ {i}) > τ` must end after all of Θ_τ, giving `start_i.min ≥ Env(Θ_τ)`.
 *     A second sweep on the reflected timeline tightens `start_i.max`, and `Env(Θ_τ) > τ`
 *     detects the energetic overload. This is the maximum-over-subsets bound, not a relaxation.
 *
 * Together (1)+(2)+(3) — time-tabling, detectable precedences, and Θ-tree-tight unary
 * edge-finding — match Choco's `disjunctive(default)` strength on classical JSP benchmarks.
 *
 * Variable durations aren't supported yet (matches [Cumulative]). All complexity figures
 * are per propagator call; the deductive engine iterates to fixpoint via the worklist.
 */
class Disjunctive(
    /** Task start-time variable ids. */
    val starts: IntArray,
    /** Constant per-task durations. */
    val durations: IntArray,
    /** Per-task presence literals; empty for the non-opt fast path. Absent tasks impose
     *  no no-overlap obligation. The cost / propagation passes route through the
     *  Cumulative LS-cost delegate and reuse its opt machinery. */
    val presents: IntArray = EmptyIntArray,
    /** Per-task duration variables; empty = use [durations] as constants. */
    val durationVars: IntArray = EmptyIntArray,
) : Factor {

    init {
        require(starts.size == durations.size) {
            "Disjunctive arrays must match: starts=${starts.size} durations=${durations.size}"
        }
        for (i in durations.indices) {
            require(durations[i] >= 0) { "Disjunctive durations[$i] must be ≥ 0, got ${durations[i]}" }
        }
        require(presents.isEmpty() || presents.size == starts.size) {
            "Disjunctive: presents must be empty or match starts arity"
        }
        require(durationVars.isEmpty() || durationVars.size == starts.size) {
            "Disjunctive: durationVars must be empty or match starts arity"
        }
    }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor =
        Disjunctive(starts.remapVars(intMap), durations, presents.remapLits(boolMap), durationVars.remapVars(intMap))

    /** Position-faithful: keeps the task arrays in order and folds in the constant durations and the
     *  var/const split (#531). */
    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.DISJUNCTIVE) {
        ints(durations)
        ints(starts)
        ints(presents)
        ints(durationVars)
    }

    override val boolVars: IntArray = OptPresence.presenceVarIds(presents)
    override val intVars: IntArray = if (durationVars.isEmpty()) starts else starts + durationVars

    /** Number of tasks. */
    val n: Int = starts.size

    private val cumulativeBacking: Cumulative = Cumulative(
        starts = starts,
        durations = durations,
        resources = IntArray(n) { 1 },
        capacity = 1,
        presents = presents,
        durationVars = durationVars,
    )

    override fun asPropagator(): Propagator = DisjunctivePropagator(
        intVars = intVars,
        starts = starts,
        durations = durations,
        presents = presents,
        durationVars = durationVars,
        n = n,
    )

    override fun asInvariant(): Invariant = DisjunctiveInvariant(
        starts = starts,
        durations = durations,
        presents = presents,
        durationVars = durationVars,
        cumulativeBacking = cumulativeBacking.asInvariant() as CumulativeInvariant,
    )
}
