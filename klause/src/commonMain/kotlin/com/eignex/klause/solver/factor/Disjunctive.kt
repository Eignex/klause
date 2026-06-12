package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntArrayList
import kotlin.math.max

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
 *  3. **Edge-finding (Vilím Θ-tree, O(n² log n))**. Routed through [CumulativeThetaTree] at
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

    override val boolVars: IntArray = OptPresence.presenceVarIds(presents)
    override val intVars: IntArray = if (durationVars.isEmpty()) starts else starts + durationVars

    private val n: Int = starts.size

    private val cumulativeBacking: Cumulative = Cumulative(
        starts = starts,
        durations = durations,
        resources = IntArray(n) { 1 },
        capacity = 1,
        presents = presents,
        durationVars = durationVars,
    )

    override fun initialize(state: LocalSearchState, factorId: Int) = cumulativeBacking.initialize(state, factorId)

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        cumulativeBacking.isViolated(state, factorId)

    /** Graded violation: delegates to the unit-capacity [Cumulative] backing, so the degree is
     *  the total time-overlap energy `Σ_t max(0, concurrency_t − 1)` — a real gradient. */
    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        cumulativeBacking.violationDegree(state, factorId)

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int =
        cumulativeBacking.deltaIfIntSet(state, factorId, intVar, newValue)

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int =
        cumulativeBacking.applyIntSet(state, factorId, intVar, oldValue)

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int =
        cumulativeBacking.deltaIfBoolFlipped(state, factorId, boolVar)

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int =
        cumulativeBacking.applyBoolFlip(state, factorId, boolVar)

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) =
        cumulativeBacking.proposeRepairMoves(state, factorId, sink)

    /** Snapshot effective per-task durations. Returns null if any duration var is not
     *  fixed at this fixpoint pass — propagation defers in that case (sound). */
    private fun effDurOrNull(state: PropagationState): IntArray? {
        if (durationVars.isEmpty()) return durations
        val out = IntArray(n)
        for (i in 0 until n) {
            val d = state.intDomains[durationVars[i]]
            if (d.min != d.max) return null
            out[i] = d.min
        }
        return out
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        if (n == 0) return true
        val effDur = effDurOrNull(state) ?: return true
        if (!timeTable(state, effDur)) return false
        if (!detectablePrecedences(state, effDur)) return false
        if (!edgeFinding(state, effDur)) return false
        return true
    }

    /** Build the mandatory profile from each task's `[lst, ect)` compulsory part; fail on
     *  level > 1; shave any non-fixed task's start endpoints if placement would create
     *  an additional unit-overlap with the mandatory profile. */
    private fun timeTable(state: PropagationState, effDur: IntArray): Boolean {
        // Capacity-1, unit-resource specialization of the shared mandatory profile.
        val profile = MandatoryProfile()
        for (i in 0 until n) {
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
            val d = effDur[i]
            if (d == 0) continue
            val dom = state.intDomains[starts[i]]
            profile.addTask(lst = dom.max, ect = dom.min + d, resource = 1)
        }
        if (!profile.build(cap = 1)) return false
        // Shave non-fixed tasks against the mandatory profile. Only definitely-present
        // tasks get tightened — unpinned-presence tasks might still vanish.
        for (i in 0 until n) {
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
            val d = effDur[i]
            if (d == 0) continue
            val v = starts[i]
            val dom = state.intDomains[v]
            if (dom.min == dom.max) continue
            val lstI = dom.max
            val ectI = dom.min + d
            val ownsMandatory = lstI < ectI
            // Tighten dom.min upward.
            var newMin = dom.min
            while (newMin <= state.intDomains[v].max) {
                if (profile.overloadsAt(newMin, newMin + d, r = 1, cap = 1, ownsMandatory, lstI, ectI)) {
                    newMin++
                } else {
                    break
                }
            }
            if (newMin > state.intDomains[v].max) return false
            if (newMin != state.intDomains[v].min && !state.tightenIntMin(v, newMin)) return false
            // Tighten dom.max downward.
            var newMax = state.intDomains[v].max
            while (newMax >= state.intDomains[v].min) {
                if (profile.overloadsAt(newMax, newMax + d, r = 1, cap = 1, ownsMandatory, lstI, ectI)) {
                    newMax--
                } else {
                    break
                }
            }
            if (newMax < state.intDomains[v].min) return false
            if (newMax != state.intDomains[v].max && !state.tightenIntMax(
                    v,
                    newMax,
                    state.composeIntVarAtomAntecedents(starts),
                )
            ) {
                return false
            }
        }
        return true
    }

    /** Pairwise rule: if `est_i + dur_i > lst_j`, task i can't end before j must start;
     *  i must come strictly after j. Tighten `start_i.min ≥ est_j + dur_j`. */
    private fun detectablePrecedences(state: PropagationState, effDur: IntArray): Boolean {
        for (i in 0 until n) {
            if (effDur[i] == 0) continue
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
            val vi = starts[i]
            val di = state.intDomains[vi]
            var newMinI = di.min
            for (j in 0 until n) {
                if (j == i) continue
                if (effDur[j] == 0) continue
                if (!OptPresence.isDefinitelyPresent(presents, j, state)) continue
                val dj = state.intDomains[starts[j]]
                if (di.min + effDur[i] > dj.max) {
                    if (dj.min + effDur[j] > di.max) return false
                    newMinI = max(newMinI, dj.min + effDur[j])
                }
            }
            if (newMinI != di.min) {
                if (newMinI > di.max) return false
                if (!state.tightenIntMin(vi, newMinI, state.composeIntVarAtomAntecedents(starts))) return false
            }
        }
        return true
    }

    /**
     * Vilím Θ-tree edge-finding for the unary case — capacity 1, unit energy `e_i = dur_i`,
     * the [CumulativeThetaTree]'s strongest specialization. For each LCT threshold τ the
     * active set Θ_τ = { j : lct(j) ≤ τ } has the C = 1 envelope (its earliest completion)
     *   Env(Θ_τ) = max_{Ω ⊆ Θ_τ, Ω ≠ ∅} (est(Ω) + e(Ω)) = ect(Θ_τ).
     *
     *  - **Overload:** `Env(Θ_τ) > τ` ⇒ Θ_τ cannot complete by its own deadline ⇒ infeasible.
     *  - **Edge-finding:** for a task `i ∉ Θ_τ`, if *inserting* `i` pushes the envelope past τ
     *    — `Env(Θ_τ ∪ {i}) > τ` — then `i` cannot finish within the window, so it must end
     *    after all of Θ_τ and `est(i) ≥ Env(Θ_τ)`.
     *
     * Crucially the detection inserts `i` into the tree, so the envelope folds in `i`'s own
     * est; flat-adding `e_i` to `Env(Θ_τ)` would over-detect when `est_i < est(Ω)` (a task
     * able to run *before* Θ would be wrongly forced after it). The [forwardPass] tightens
     * earliest starts; rerunning it on the reflected timeline (`t → −t`, so a start `s` maps
     * to `−(s + dur)`) tightens latest starts. Each pass is O(m² log m) over the active tasks.
     */
    private fun edgeFinding(state: PropagationState, effDur: IntArray): Boolean {
        if (n < 2) return true
        return forwardPass(state, effDur, reversed = false) && forwardPass(state, effDur, reversed = true)
    }

    /**
     * One Θ-tree edge-finding sweep. With [reversed] = false it works in normal time and
     * tightens `start.min`; with [reversed] = true it works on the reflected timeline (where
     * an earliest-start bound maps back to a `start.max` bound) and so tightens `start.max`.
     */
    @Suppress("ReturnCount")
    private fun forwardPass(state: PropagationState, effDur: IntArray, reversed: Boolean): Boolean {
        val active = IntArrayList()
        for (i in 0 until n) {
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
            if (effDur[i] > 0) active.add(i)
        }
        val m = active.size
        if (m < 2) return true

        val taskIds = IntArray(m) { active[it] }
        val durs = IntArray(m) { effDur[taskIds[it]] }
        // In reflected time a task occupying [s, s+d) maps to start −(s + d): est' = −(lst+d),
        // lct' = −est. So the est/lct arrays just negate-and-swap the original endpoints.
        val ests = IntArray(m)
        val lcts = IntArray(m)
        for (t in 0 until m) {
            val dom = state.intDomains[starts[taskIds[t]]]
            if (!reversed) {
                ests[t] = dom.min
                lcts[t] = dom.max + durs[t]
            } else {
                ests[t] = -(dom.max + durs[t])
                lcts[t] = -dom.min
            }
        }
        val energies = LongArray(m) { durs[it].toLong() }

        val estOrder = (0 until m).sortedWith(compareBy({ ests[it] }, { it })).toIntArray()
        val leafPos = IntArray(m)
        for (leafIdx in 0 until m) leafPos[estOrder[leafIdx]] = leafIdx
        val lctOrder = (0 until m).sortedWith(compareBy({ lcts[it] }, { it })).toIntArray()

        val tree = CumulativeThetaTree(n = m, capacity = 1)
        tree.setLeafOrder(leafPos)
        val ant = state.composeIntVarAtomAntecedents(starts)

        var k = 0
        while (k < m) {
            val tau = lcts[lctOrder[k]].toLong()
            while (k < m && lcts[lctOrder[k]].toLong() == tau) {
                val j = lctOrder[k]
                tree.activate(j, ests[j], energies[j])
                k++
            }
            val envTheta = tree.envOfTheta() // ect(Θ_τ)
            if (envTheta > tau) return false // overload
            for (ki in k until m) {
                val cand = lctOrder[ki]
                // Detection inserts the candidate so its own est folds into the envelope anchor,
                // then restores the tree — this is what makes the bound sound (vs flat env+e).
                tree.activate(cand, ests[cand], energies[cand])
                val envWith = tree.envOfTheta()
                tree.deactivate(cand)
                if (envWith <= tau) continue
                if (envTheta > Int.MAX_VALUE.toLong()) continue
                val bound = envTheta.toInt() // est(cand) ≥ ect(Θ_τ)
                val v = starts[taskIds[cand]]
                if (!reversed) {
                    if (bound > state.intDomains[v].min && !state.tightenIntMin(v, bound, ant)) return false
                } else {
                    // s' ≥ bound with s' = −(s + dur) ⇒ s ≤ −bound − dur.
                    val newMax = -bound - durs[cand]
                    if (newMax < state.intDomains[v].max && !state.tightenIntMax(v, newMax, ant)) return false
                }
            }
        }
        return true
    }
}
