package com.eignex.klause.solver.factor.scheduling

import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.factor.OptPresence
import com.eignex.klause.solver.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.solver.factor.scheduling.internals.CumulativeThetaTree
import com.eignex.klause.solver.factor.scheduling.internals.MandatoryProfile
import com.eignex.klause.solver.propagation.IntEvent
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.argsortByIntKey
import kotlin.math.max

/**
 * CP propagator for [Disjunctive]. Constructed by [Disjunctive.asPropagator] and provides
 * time-tabling, detectable precedences, and Vilím Θ-tree edge-finding for the unary case.
 */
internal class DisjunctivePropagator(
    val boolVars: IntArray,
    val intVars: IntArray,
    private val starts: IntArray,
    private val durations: IntArray,
    private val presents: IntArray,
    private val durationVars: IntArray,
    private val n: Int,
) : Propagator {

    override val initialIntEventWatches: IntArray = IntEvent.boundEventWatches(intVars)

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

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectLinearTightenAntecedents(state, intVars, excludeIdx = -1, extraLit = 0)

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
        val profile = MandatoryProfile()
        for (i in 0 until n) {
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
            val d = effDur[i]
            if (d == 0) continue
            val dom = state.intDomains[starts[i]]
            profile.addTask(lst = dom.max, ect = dom.min + d, resource = 1)
        }
        if (!profile.build(cap = 1)) return false
        val ant = state.composeIntVarAtomAntecedents(intVars)
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
            var newMin = dom.min
            while (newMin <= state.intDomains[v].max) {
                if (profile.overloadsAt(newMin, newMin + d, r = 1, cap = 1, ownsMandatory, lstI, ectI)) {
                    newMin++
                } else {
                    break
                }
            }
            if (newMin > state.intDomains[v].max) return false
            if (newMin != state.intDomains[v].min && !state.tightenIntMin(v, newMin, ant)) return false
            var newMax = state.intDomains[v].max
            while (newMax >= state.intDomains[v].min) {
                if (profile.overloadsAt(newMax, newMax + d, r = 1, cap = 1, ownsMandatory, lstI, ectI)) {
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
    private fun detectablePrecedences(state: PropagationState, effDur: IntArray): Boolean {
        val ant = state.composeIntVarAtomAntecedents(intVars)
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
                if (!state.tightenIntMin(vi, newMinI, ant)) return false
            }
        }
        return true
    }

    private fun edgeFinding(state: PropagationState, effDur: IntArray): Boolean {
        if (n < 2) return true
        return forwardPass(state, effDur, reversed = false) && forwardPass(state, effDur, reversed = true)
    }

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

        val estOrder = argsortByIntKey(m) { ests[it] }
        val leafPos = IntArray(m)
        for (leafIdx in 0 until m) leafPos[estOrder[leafIdx]] = leafIdx
        val lctOrder = argsortByIntKey(m) { lcts[it] }

        val tree = CumulativeThetaTree(n = m, capacity = 1)
        tree.setLeafOrder(leafPos)
        val ant = state.composeIntVarAtomAntecedents(intVars)

        var k = 0
        while (k < m) {
            val tau = lcts[lctOrder[k]].toLong()
            while (k < m && lcts[lctOrder[k]].toLong() == tau) {
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
                if (envTheta > Int.MAX_VALUE.toLong()) continue
                val bound = envTheta.toInt()
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
