package com.eignex.klause.factor.scheduling

import com.eignex.klause.factor.OptPresence
import com.eignex.klause.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.factor.scheduling.internals.CumulativeThetaTree
import com.eignex.klause.factor.scheduling.internals.MandatoryProfile
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.argsortBy
import kotlin.math.max

/**
 * CP propagator for the no-overlap case of [com.eignex.klause.factor.scheduling.Cumulative]
 * (`unary = true`, built via [com.eignex.klause.factor.scheduling.Cumulative.unary]) and provides
 * time-tabling, detectable precedences, and Θ-tree edge-finding for the unary case.
 */
internal class DisjunctivePropagator(
    val intVars: IntArray,
    private val starts: IntArray,
    private val durations: LongArray,
    private val presents: IntArray,
    private val durationVars: IntArray,
    private val n: Int,
) : Propagator {

    override val expensiveBake: Boolean get() = true

    override val initialIntEventWatches: IntArray = IntEvent.boundEventWatches(intVars)

    /** Snapshot effective per-task durations. Returns null if any duration var is not
     *  fixed at this fixpoint pass — propagation defers in that case (sound). */
    private fun effDurOrNull(state: PropagationState): LongArray? {
        if (durationVars.isEmpty()) return LongArray(n) { durations[it] }
        val out = LongArray(n)
        for (i in 0 until n) {
            val d = state.intDomains[durationVars[i]]
            if (d.min != d.max) return null
            out[i] = d.min
        }
        return out
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? {
        // Each sharp path cites only the tasks responsible for a self-contained contradiction; any
        // failure none of them matches falls back to the sound whole-scope reason.
        val effDur = effDurOrNull(state)
        if (effDur != null) {
            mutualPrecedenceReason(state, effDur)?.let { return it }
            profileOverloadReason(state, effDur)?.let { return it }
            energeticWindowReason(state, effDur)?.let { return it }
        }
        return collectLinearTightenAntecedents(state, intVars, excludeIdx = -1, extraLit = 0)
    }

    private fun isActive(state: PropagationState, i: Int, effDur: LongArray): Boolean =
        effDur[i] != 0L && OptPresence.isDefinitelyPresent(presents, i, state)

    private fun reasonOver(state: PropagationState, taskIdx: IntArrayList): IntArray? {
        val vars = IntArrayList()
        for (t in 0 until taskIdx.size) {
            val i = taskIdx[t]
            vars.add(starts[i])
            if (durationVars.isNotEmpty()) vars.add(durationVars[i])
        }
        return collectLinearTightenAntecedents(state, vars.toIntArray(), excludeIdx = -1, extraLit = 0)
    }

    /** Two tasks each forced strictly after the other — implied by just those two starts. */
    private fun mutualPrecedenceReason(state: PropagationState, effDur: LongArray): IntArray? {
        for (i in 0 until n) {
            if (!isActive(state, i, effDur)) continue
            val di = state.intDomains[starts[i]]
            for (j in 0 until n) {
                if (j == i || !isActive(state, j, effDur)) continue
                val dj = state.intDomains[starts[j]]
                if (di.min + effDur[i] > dj.max && dj.min + effDur[j] > di.max) {
                    val pair = IntArrayList()
                    pair.add(i)
                    pair.add(j)
                    return reasonOver(state, pair)
                }
            }
        }
        return null
    }

    /** Tasks whose compulsory parts stack two-deep over one time point — a unit-resource overload. */
    private fun profileOverloadReason(state: PropagationState, effDur: LongArray): IntArray? {
        val profile = MandatoryProfile()
        for (i in 0 until n) {
            if (!isActive(state, i, effDur)) continue
            val dom = state.intDomains[starts[i]]
            profile.addTask(lst = dom.max, ect = dom.min + effDur[i], resource = 1L)
        }
        if (profile.build(cap = 1L)) return null
        val t = profile.overloadTime
        val covering = IntArrayList()
        for (i in 0 until n) {
            if (!isActive(state, i, effDur)) continue
            val dom = state.intDomains[starts[i]]
            if (dom.max <= t && t < dom.min + effDur[i]) covering.add(i)
        }
        return if (covering.size >= 2) reasonOver(state, covering) else null
    }

    /** A window `[t1, t2)` whose fully-contained tasks' durations sum past its length cannot pack. */
    private fun energeticWindowReason(state: PropagationState, effDur: LongArray): IntArray? {
        for (a in 0 until n) {
            if (!isActive(state, a, effDur)) continue
            val t1 = state.intDomains[starts[a]].min
            for (b in 0 until n) {
                if (!isActive(state, b, effDur)) continue
                val t2 = state.intDomains[starts[b]].max + effDur[b]
                if (t2 <= t1) continue
                val inside = IntArrayList()
                var load = 0L
                for (i in 0 until n) {
                    if (!isActive(state, i, effDur)) continue
                    val dom = state.intDomains[starts[i]]
                    if (dom.min >= t1 && dom.max + effDur[i] <= t2) {
                        inside.add(i)
                        load += effDur[i]
                    }
                }
                if (load > t2 - t1 && inside.size >= 2) return reasonOver(state, inside)
            }
        }
        return null
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
    private fun timeTable(state: PropagationState, effDur: LongArray): Boolean {
        val profile = MandatoryProfile()
        for (i in 0 until n) {
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
            val d = effDur[i]
            if (d == 0L) continue
            val dom = state.intDomains[starts[i]]
            profile.addTask(lst = dom.max, ect = dom.min + d, resource = 1L)
        }
        if (!profile.build(cap = 1L)) return false
        val ant = state.composeIntVarAtomAntecedents(intVars)
        for (i in 0 until n) {
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
            val d = effDur[i]
            if (d == 0L) continue
            val v = starts[i]
            val dom = state.intDomains[v]
            if (dom.min == dom.max) continue
            val lstI = dom.max
            val ectI = dom.min + d
            val ownsMandatory = lstI < ectI
            var newMin = dom.min
            while (newMin <= state.intDomains[v].max) {
                if (profile.overloadsAt(newMin, newMin + d, r = 1L, cap = 1L, ownsMandatory, lstI, ectI)) {
                    newMin++
                } else {
                    break
                }
            }
            if (newMin > state.intDomains[v].max) return false
            if (newMin != state.intDomains[v].min && !state.tightenIntMin(v, newMin, ant)) return false
            var newMax = state.intDomains[v].max
            while (newMax >= state.intDomains[v].min) {
                if (profile.overloadsAt(newMax, newMax + d, r = 1L, cap = 1L, ownsMandatory, lstI, ectI)) {
                    newMax--
                } else {
                    break
                }
            }
            if (newMax < state.intDomains[v].min) return false
            if (newMax != state.intDomains[v].max && !state.tightenIntMax(v, newMax, ant)) return false
        }
        return true
    }

    /** Pairwise rule: if `est_i + dur_i > lst_j`, task i can't end before j must start;
     *  i must come strictly after j. Tighten `start_i.min ≥ est_j + dur_j`. */
    private fun detectablePrecedences(state: PropagationState, effDur: LongArray): Boolean {
        val ant = state.composeIntVarAtomAntecedents(intVars)
        for (i in 0 until n) {
            if (effDur[i] == 0L) continue
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
            val vi = starts[i]
            val di = state.intDomains[vi]
            var newMinI = di.min
            for (j in 0 until n) {
                if (j == i) continue
                if (effDur[j] == 0L) continue
                if (!OptPresence.isDefinitelyPresent(presents, j, state)) continue
                val dj = state.intDomains[starts[j]]
                if (di.min + effDur[i] > dj.max) {
                    if (dj.min + effDur[j] > di.max) return false
                    newMinI = max(newMinI, dj.min + effDur[j])
                }
            }
            if (newMinI != di.min) {
                if (newMinI > di.max) return false
                if (!state.tightenIntMin(vi, newMinI, ant)) return false
            }
        }
        return true
    }

    private fun edgeFinding(state: PropagationState, effDur: LongArray): Boolean {
        if (n < 2) return true
        return forwardPass(state, effDur, reversed = false) && forwardPass(state, effDur, reversed = true)
    }

    @Suppress("ReturnCount")
    private fun forwardPass(state: PropagationState, effDur: LongArray, reversed: Boolean): Boolean {
        val active = IntArrayList()
        for (i in 0 until n) {
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
            if (effDur[i] > 0) active.add(i)
        }
        val m = active.size
        if (m < 2) return true

        val taskIds = IntArray(m) { active[it] }
        val durs = LongArray(m) { effDur[taskIds[it]] }
        val ests = LongArray(m)
        val lcts = LongArray(m)
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
        val energies = LongArray(m) { durs[it] }

        val estOrder = argsortBy(m) { a, b -> ests[a].compareTo(ests[b]) }
        val leafPos = IntArray(m)
        for (leafIdx in 0 until m) leafPos[estOrder[leafIdx]] = leafIdx
        val lctOrder = argsortBy(m) { a, b -> lcts[a].compareTo(lcts[b]) }

        val tree = CumulativeThetaTree(n = m, capacity = 1L)
        tree.setLeafOrder(leafPos)
        val ant = state.composeIntVarAtomAntecedents(intVars)

        var k = 0
        while (k < m) {
            val tau = lcts[lctOrder[k]]
            while (k < m && lcts[lctOrder[k]] == tau) {
                val j = lctOrder[k]
                tree.activate(j, ests[j], energies[j])
                k++
            }
            val envTheta = tree.envOfTheta()
            if (envTheta > tau) return false
            for (ki in k until m) {
                val cand = lctOrder[ki]
                tree.activate(cand, ests[cand], energies[cand])
                val envWith = tree.envOfTheta()
                tree.deactivate(cand)
                if (envWith <= tau) continue
                val bound = envTheta
                val v = starts[taskIds[cand]]
                if (!reversed) {
                    if (bound > state.intDomains[v].min && !state.tightenIntMin(v, bound, ant)) return false
                } else {
                    val newMax = -bound - durs[cand]
                    if (newMax < state.intDomains[v].max && !state.tightenIntMax(v, newMax, ant)) return false
                }
            }
        }
        return true
    }
}
