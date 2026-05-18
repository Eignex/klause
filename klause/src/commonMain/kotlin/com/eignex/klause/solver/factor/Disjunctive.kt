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
 * Together (1)+(2)+(3) match Choco's `disjunctive(default)` strength on classical JSP
 * benchmarks. The remaining gap to MiniZinc's strongest cumulative-via-disjunctive
 * propagators is Vilím's Θ-tree O(n log n) edge-finding plus the not-first / not-last
 * complement; both are natural follow-ups but contribute only constant-factor wins on
 * the n ≤ 50 size class where klause-LS lives.
 *
 * Variable durations aren't supported yet (matches [Cumulative]). All complexity figures
 * are per propagator call; the deductive engine iterates to fixpoint via the worklist.
 */
class Disjunctive(
    val starts: IntArray,
    val durations: IntArray,
) : LocalSearchFactor {

    init {
        require(starts.size == durations.size) {
            "Disjunctive arrays must match: starts=${starts.size} durations=${durations.size}"
        }
        for (i in durations.indices) {
            require(durations[i] >= 0) { "Disjunctive durations[$i] must be ≥ 0, got ${durations[i]}" }
        }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = starts

    private val n: Int = starts.size

    /** LS delegate: cumulative with all-1 resources and capacity 1. Identical cost
     *  surface as disjunctive, so we reuse rather than copy the timeline machinery. */
    private val cumulativeBacking: Cumulative = Cumulative(
        starts = starts,
        durations = durations,
        resources = IntArray(n) { 1 },
        capacity = 1,
    )

    override fun initialize(state: LocalSearchState, factorId: Int) =
        cumulativeBacking.initialize(state, factorId)

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        cumulativeBacking.isViolated(state, factorId)

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int =
        cumulativeBacking.deltaIfIntSet(state, factorId, intVar, newValue)

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int =
        cumulativeBacking.applyIntSet(state, factorId, intVar, oldValue)

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) =
        cumulativeBacking.proposeRepairMoves(state, factorId, sink)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        if (n == 0) return true
        // 1. Time-tabling (cap = 1).
        if (!timeTable(state)) return false
        // 2. Pairwise detectable precedences.
        if (!detectablePrecedences(state)) return false
        // 3. Edge-finding (Carlier-Pinson lite).
        if (!edgeFinding(state)) return false
        return true
    }

    /** Build the mandatory profile from each task's `[lst, ect)` compulsory part; fail on
     *  level > 1; shave any non-fixed task's start endpoints if placement would create
     *  an additional unit-overlap with the mandatory profile. */
    private fun timeTable(state: PropagationState): Boolean {
        val events = ArrayList<IntArray>(n * 2)
        for (i in 0 until n) {
            val d = durations[i]
            if (d == 0) continue
            val dom = state.intDomains[starts[i]]
            val lst = dom.max
            val ect = dom.min + d
            if (lst < ect) {
                events.add(intArrayOf(lst, +1))
                events.add(intArrayOf(ect, -1))
            }
        }
        events.sortWith(compareBy({ it[0] }, { -it[1] }))
        // Sweep; capture segments where level > 0 (mandatory occupied).
        val segFrom = IntArray(events.size)
        val segTo = IntArray(events.size)
        var segCount = 0
        var level = 0
        var cursor = if (events.isEmpty()) 0 else events[0][0]
        for ((idx, ev) in events.withIndex()) {
            val t = ev[0]
            if (t > cursor && level > 0) {
                segFrom[segCount] = cursor; segTo[segCount] = t; segCount++
            }
            level += ev[1]
            cursor = t
            if (idx == events.size - 1 || events[idx + 1][0] != t) {
                if (level > 1) return false
            }
        }
        // Shave non-fixed tasks against the mandatory profile.
        for (i in 0 until n) {
            val d = durations[i]
            if (d == 0) continue
            val v = starts[i]
            val dom = state.intDomains[v]
            if (dom.min == dom.max) continue
            val lstI = dom.max; val ectI = dom.min + d
            val ownsMandatory = lstI < ectI
            // Tighten dom.min upward.
            var newMin = dom.min
            while (newMin <= state.intDomains[v].max) {
                if (mandatoryConflicts(segFrom, segTo, segCount, newMin, newMin + d, ownsMandatory, lstI, ectI)) {
                    newMin++
                } else break
            }
            if (newMin > state.intDomains[v].max) return false
            if (newMin != state.intDomains[v].min && !state.tightenIntMin(v, newMin)) return false
            // Tighten dom.max downward.
            var newMax = state.intDomains[v].max
            while (newMax >= state.intDomains[v].min) {
                if (mandatoryConflicts(segFrom, segTo, segCount, newMax, newMax + d, ownsMandatory, lstI, ectI)) {
                    newMax--
                } else break
            }
            if (newMax < state.intDomains[v].min) return false
            if (newMax != state.intDomains[v].max && !state.tightenIntMax(v, newMax)) return false
        }
        return true
    }

    /** True iff placing a task `[s, sPlusD)` would coincide with any existing mandatory
     *  segment (subtracting the task's own already-counted mandatory contribution). */
    private fun mandatoryConflicts(
        segFrom: IntArray, segTo: IntArray, segCount: Int,
        s: Int, sPlusD: Int,
        ownsMandatory: Boolean, lstI: Int, ectI: Int,
    ): Boolean {
        for (k in 0 until segCount) {
            val from = segFrom[k]; val to = segTo[k]
            if (to <= s || from >= sPlusD) continue
            if (ownsMandatory) {
                // The task's own mandatory part shadows the segment; the placement only
                // *adds* extra occupancy if the segment extends past this task's own
                // mandatory window.
                val outsideOwn = (from < lstI) || (to > ectI)
                if (!outsideOwn) continue
            }
            return true
        }
        return false
    }

    /** Pairwise rule: if `est_i + dur_i > lst_j`, task i can't end before j must start;
     *  i must come strictly after j. Tighten `start_i.min ≥ est_j + dur_j`. */
    private fun detectablePrecedences(state: PropagationState): Boolean {
        for (i in 0 until n) {
            if (durations[i] == 0) continue
            val vi = starts[i]
            val di = state.intDomains[vi]
            var newMinI = di.min
            for (j in 0 until n) {
                if (j == i) continue
                if (durations[j] == 0) continue
                val dj = state.intDomains[starts[j]]
                // i cannot precede j iff est_i + dur_i > lst_j.
                if (di.min + durations[i] > dj.max) {
                    // Also check the symmetric direction — if both can't precede the
                    // other, no consistent ordering exists.
                    if (dj.min + durations[j] > di.max) return false
                    newMinI = max(newMinI, dj.min + durations[j])
                }
            }
            if (newMinI != di.min) {
                if (newMinI > di.max) return false
                if (!state.tightenIntMin(vi, newMinI)) return false
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
    private fun edgeFinding(state: PropagationState): Boolean {
        if (n < 2) return true
        // Forward pass: tighten start_i.min.
        val others = IntArray(n)
        for (i in 0 until n) {
            if (durations[i] == 0) continue
            val vi = starts[i]
            val di = state.intDomains[vi]
            var k = 0
            for (j in 0 until n) if (j != i && durations[j] > 0) { others[k++] = j }
            // Sort others by lct = dom.max + dur ascending.
            sortByLct(others, k, state)
            // Sweep prefixes.
            var sumDur = 0
            var minEstOmega = Int.MAX_VALUE
            var newMinI = di.min
            for (p in 0 until k) {
                val j = others[p]
                val dj = state.intDomains[starts[j]]
                sumDur += durations[j]
                minEstOmega = min(minEstOmega, dj.min)
                val lctOmega = dj.max + durations[j] // since list is lct-sorted, last is max
                val estUnion = min(minEstOmega, di.min)
                if (estUnion + durations[i] + sumDur > lctOmega) {
                    // i can't fit before all of Ω.
                    val push = minEstOmega + sumDur
                    if (push > newMinI) newMinI = push
                }
            }
            if (newMinI != di.min) {
                if (newMinI > di.max) return false
                if (!state.tightenIntMin(vi, newMinI)) return false
            }
        }
        // Backward pass: tighten start_i.max via the symmetric overflow.
        for (i in 0 until n) {
            if (durations[i] == 0) continue
            val vi = starts[i]
            val di = state.intDomains[vi]
            var k = 0
            for (j in 0 until n) if (j != i && durations[j] > 0) { others[k++] = j }
            sortByEstDesc(others, k, state)
            var sumDur = 0
            var maxLctOmega = Int.MIN_VALUE
            var newMaxI = di.max
            for (p in 0 until k) {
                val j = others[p]
                val dj = state.intDomains[starts[j]]
                sumDur += durations[j]
                val lctJ = dj.max + durations[j]
                if (lctJ > maxLctOmega) maxLctOmega = lctJ
                val estOmega = dj.min // est-sorted desc → last considered has the min est
                val lctUnion = max(maxLctOmega, di.max + durations[i])
                if (estOmega + durations[i] + sumDur > lctUnion) {
                    // i can't fit after all of Ω → i must end before some of Ω → start_i.max ≤ maxLctOmega − sumDur − dur_i
                    val push = maxLctOmega - sumDur - durations[i]
                    if (push < newMaxI) newMaxI = push
                }
            }
            if (newMaxI != di.max) {
                if (newMaxI < di.min) return false
                if (!state.tightenIntMax(vi, newMaxI)) return false
            }
        }
        return true
    }

    private fun sortByLct(arr: IntArray, len: Int, state: PropagationState) {
        // Insertion sort — len is typically small (≤ 50 on Challenge JSP).
        for (i in 1 until len) {
            val cur = arr[i]
            val curKey = state.intDomains[starts[cur]].max + durations[cur]
            var j = i - 1
            while (j >= 0) {
                val cmpKey = state.intDomains[starts[arr[j]]].max + durations[arr[j]]
                if (cmpKey <= curKey) break
                arr[j + 1] = arr[j]; j--
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
                arr[j + 1] = arr[j]; j--
            }
            arr[j + 1] = cur
        }
    }}
