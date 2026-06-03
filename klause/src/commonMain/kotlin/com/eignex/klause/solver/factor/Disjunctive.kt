package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState
import kotlin.math.max
import kotlin.math.min

/**
 * Disjunctive (one-machine / unary-resource) constraint: tasks must not overlap in time.
 * Task `i` occupies `[starts[i], starts[i] + durations[i])`; for any two tasks `i ≠ j`
 * one must end before the other starts.
 *
 * Semantically the unary special case of [Cumulative] (`resources[i] = 1`, `capacity = 1`),
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
 *  3. **Edge-finding (Carlier-Pinson lite, O(n²))**. For each task `i` and each
 *     lct-sorted prefix `Ω` of the other tasks, checks the energetic overflow
 *     `est(Ω∪{i}) + dur_i + sum_dur(Ω) > lct(Ω)`. When it fires, `i` must finish after
 *     all of `Ω`, so `start_i.min ≥ est(Ω) + sum_dur(Ω)`. Symmetric pass on est-sorted
 *     suffixes tightens `start_i.max ≤ lct(Ω) − sum_dur(Ω) − dur_i`.
 *
 * Together (1)+(2)+(3) catch the JSP / RCPSP disjunctive patterns SMT scheduling
 * decompositions lean on, but the edge-finding pass (3) is the Carlier-Pinson "lite"
 * relaxation — it always pushes to `est(Ω) + sum_dur(Ω)` rather than the Θ-tree
 * maximum-over-subsets bound (see the note on [edgeFinding]) — so this is *not* full
 * Θ-tree-tight unary reasoning and does not match Choco's `disjunctive(default)` in all
 * cases. Routing (3) through a unary Θ-Λ tree (the sound envelope edge-finder; the plain
 * [CumulativeThetaTree] `Env(Θ)+e_i` shortcut over-detects when `est_i < est(Ω)`) is the
 * remaining tightening.
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
) : LocalSearchFactor {

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
     * Carlier-Pinson "lite" edge-finding (O(n²)). For each task i and each lct-sorted
     * prefix Ω of the remaining tasks, fires when `est(Ω∪{i}) + dur_i + sum_dur(Ω) >
     * lct(Ω)` — i can't fit inside the window so it must come after all of Ω — and pushes
     * `start_i.min ≥ est(Ω) + sum_dur(Ω)`. Symmetric pass on est-sorted suffixes tightens
     * `start_i.max`.
     *
     * Not tight as Vilím's Θ-tree (we always push to `est(Ω) + sum_dur(Ω)` rather than to
     * the maximum-over-subsets adjustment), but sound and catches the JSP / RCPSP
     * disjunctive-only patterns the user-facing TODO calls out.
     */
    private fun edgeFinding(state: PropagationState, effDur: IntArray): Boolean {
        if (n < 2) return true
        val others = IntArray(n)
        for (i in 0 until n) {
            if (effDur[i] == 0) continue
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
            val vi = starts[i]
            val di = state.intDomains[vi]
            var k = 0
            for (j in 0 until n) {
                if (j != i && effDur[j] > 0 && OptPresence.isDefinitelyPresent(presents, j, state)) {
                    others[k++] = j
                }
            }
            sortByLct(others, k, state, effDur)
            var sumDur = 0
            var minEstOmega = Int.MAX_VALUE
            var newMinI = di.min
            for (p in 0 until k) {
                val j = others[p]
                val dj = state.intDomains[starts[j]]
                sumDur += effDur[j]
                minEstOmega = min(minEstOmega, dj.min)
                val lctOmega = dj.max + effDur[j]
                val estUnion = min(minEstOmega, di.min)
                if (estUnion + effDur[i] + sumDur > lctOmega) {
                    val push = minEstOmega + sumDur
                    if (push > newMinI) newMinI = push
                }
            }
            if (newMinI != di.min) {
                if (newMinI > di.max) return false
                if (!state.tightenIntMin(vi, newMinI, state.composeIntVarAtomAntecedents(starts))) return false
            }
        }
        for (i in 0 until n) {
            if (effDur[i] == 0) continue
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
            val vi = starts[i]
            val di = state.intDomains[vi]
            var k = 0
            for (j in 0 until n) {
                if (j != i && effDur[j] > 0 && OptPresence.isDefinitelyPresent(presents, j, state)) {
                    others[k++] = j
                }
            }
            sortByEstDesc(others, k, state)
            var sumDur = 0
            var maxLctOmega = Int.MIN_VALUE
            var newMaxI = di.max
            for (p in 0 until k) {
                val j = others[p]
                val dj = state.intDomains[starts[j]]
                sumDur += effDur[j]
                val lctJ = dj.max + effDur[j]
                if (lctJ > maxLctOmega) maxLctOmega = lctJ
                val estOmega = dj.min
                val lctUnion = max(maxLctOmega, di.max + effDur[i])
                if (estOmega + effDur[i] + sumDur > lctUnion) {
                    val push = maxLctOmega - sumDur - effDur[i]
                    if (push < newMaxI) newMaxI = push
                }
            }
            if (newMaxI != di.max) {
                if (newMaxI < di.min) return false
                if (!state.tightenIntMax(vi, newMaxI, state.composeIntVarAtomAntecedents(starts))) return false
            }
        }
        return true
    }

    private fun sortByLct(arr: IntArray, len: Int, state: PropagationState, effDur: IntArray) {
        for (i in 1 until len) {
            val cur = arr[i]
            val curKey = state.intDomains[starts[cur]].max + effDur[cur]
            var j = i - 1
            while (j >= 0) {
                val cmpKey = state.intDomains[starts[arr[j]]].max + effDur[arr[j]]
                if (cmpKey <= curKey) break
                arr[j + 1] = arr[j]
                j--
            }
            arr[j + 1] = cur
        }
    }

    private fun sortByEstDesc(arr: IntArray, len: Int, state: PropagationState) {
        for (i in 1 until len) {
            val cur = arr[i]
            val curKey = state.intDomains[starts[cur]].min
            var j = i - 1
            while (j >= 0) {
                val cmpKey = state.intDomains[starts[arr[j]]].min
                if (cmpKey >= curKey) break
                arr[j + 1] = arr[j]
                j--
            }
            arr[j + 1] = cur
        }
    }
}
