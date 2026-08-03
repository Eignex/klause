package com.eignex.klause.factor.scheduling

import com.eignex.klause.factor.OptPresence
import com.eignex.klause.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.factor.scheduling.internals.CumulativeEff
import com.eignex.klause.factor.scheduling.internals.CumulativeThetaTree
import com.eignex.klause.factor.scheduling.internals.MandatoryProfile
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Lit
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.argsortBy

/**
 * CP propagator for [Cumulative]. Constructed by [Cumulative.asPropagator] and holds the
 * time-tabling and Vilím Θ-tree edge-finding logic so those data structures are only allocated
 * when a CP engine is initialised.
 */
internal class CumulativePropagator(
    val intVars: IntArray,
    private val starts: IntArray,
    private val durations: LongArray,
    private val resources: LongArray,
    private val capacity: Long,
    private val presents: IntArray,
    private val durationVars: IntArray,
    private val resourceVars: IntArray,
    private val capacityVar: Int,
    private val n: Int,
    private val sharpReasonEligible: Boolean,
    private val constantEnergyAndCap: Boolean,
) : Propagator {

    override val expensiveBake: Boolean get() = true

    override val initialIntEventWatches: IntArray = IntEvent.boundEventWatches(intVars)

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? {
        val fallback = collectLinearTightenAntecedents(state, intVars, excludeIdx = -1, extraLit = 0)
        if (!sharpReasonEligible) return fallback
        val eff = effectiveSnapshot(state) ?: return fallback
        val profile = MandatoryProfile()
        for (i in 0 until n) {
            val d = eff.dur[i]
            val r = eff.res[i]
            if (d == 0L || r == 0L) continue
            val dom = state.intDomains[starts[i]]
            profile.addTask(lst = dom.max, ect = dom.min + d, resource = r)
        }
        if (profile.build(eff.cap)) return fallback
        return pointwiseOverloadReason(state, profile.overloadTime, eff, blamed = -1, blamedStart = 0) ?: fallback
    }

    private fun effectiveSnapshot(state: PropagationState): CumulativeEff? {
        val dur = LongArray(n)
        val res = LongArray(n)
        for (i in 0 until n) {
            if (durationVars.isEmpty()) {
                dur[i] = durations[i]
            } else {
                val d = state.intDomains[durationVars[i]]
                if (d.min != d.max) return null
                dur[i] = d.min
            }
            if (resourceVars.isEmpty()) {
                res[i] = resources[i]
            } else {
                val d = state.intDomains[resourceVars[i]]
                if (d.min != d.max) return null
                res[i] = d.min
            }
        }
        val cap = if (capacityVar < 0) {
            capacity
        } else {
            val d = state.intDomains[capacityVar]
            if (d.min != d.max) return null
            d.min
        }
        return CumulativeEff(dur, res, cap)
    }

    private fun pointwiseOverloadReason(
        state: PropagationState,
        t: Long,
        eff: CumulativeEff,
        blamed: Int,
        blamedStart: Int,
    ): IntArray? {
        val out = IntArrayList()
        if (blamedStart != 0) out.add(blamedStart)
        if (blamed >= 0) citeEnergyBounds(out, state, blamed, eff)
        for (k in 0 until n) {
            if (k == blamed) continue
            val d = eff.dur[k]
            val r = eff.res[k]
            if (d <= 0 || r <= 0) continue
            val dom = state.intDomains[starts[k]]
            if (dom.max > t || t >= dom.min + d) continue
            // Cite the task's *current* bounds, not the generalised overlap window [t-d+1, t]: the
            // live `start ≤ dom.max ≤ t` and `start ≥ dom.min ≥ t-d+1` already entail the task spans
            // `t`, and they are the canonical order literals the trail stamps — so 1UIP resolves them
            // instead of leaving the generalised thresholds as extra same-var leaves that a single
            // bound decision crossed, which left these nogoods non-asserting (#744). Mirrors how every
            // other global constraint reasons ([PropagationState.composeIntVarAtomAntecedents]).
            val orig = state.rootDomains[starts[k]]
            if (dom.max < orig.max) out.add(Lit.make(state.atomVarLe(starts[k], dom.max), false))
            if (dom.min > orig.min) out.add(Lit.make(state.atomVarGe(starts[k], dom.min), false))
            citeEnergyBounds(out, state, k, eff)
        }
        if (capacityVar >= 0) {
            val orig = state.rootDomains[capacityVar]
            if (eff.cap < orig.max) out.add(Lit.make(state.atomVarLe(capacityVar, eff.cap), false))
        }
        if (out.size == 0) return null
        return out.toIntArray()
    }

    private fun citeEnergyBounds(out: IntArrayList, state: PropagationState, k: Int, eff: CumulativeEff) {
        if (durationVars.isNotEmpty()) {
            val dv = durationVars[k]
            if (eff.dur[k] > state.rootDomains[dv].min) {
                out.add(Lit.make(state.atomVarGe(dv, eff.dur[k]), false))
            }
        }
        if (resourceVars.isNotEmpty()) {
            val rv = resourceVars[k]
            if (eff.res[k] > state.rootDomains[rv].min) {
                out.add(Lit.make(state.atomVarGe(rv, eff.res[k]), false))
            }
        }
    }

    private fun minTightenReason(
        state: PropagationState,
        i: Int,
        d: Long,
        oldMin: Long,
        newMin: Long,
        eff: CumulativeEff,
    ): IntArray? {
        val orig = state.rootDomains[starts[i]]
        val extra = if (oldMin > orig.min) Lit.make(state.atomVarGe(starts[i], oldMin), false) else 0
        if (newMin - oldMin > d) {
            return windowOverloadReason(state, i, oldMin, newMin - 1 + d, eff, extra)
        }
        return pointwiseOverloadReason(state, newMin - 1, eff, blamed = i, blamedStart = extra)
    }

    private fun maxTightenReason(
        state: PropagationState,
        i: Int,
        d: Long,
        oldMax: Long,
        newMax: Long,
        eff: CumulativeEff,
    ): IntArray? {
        val orig = state.rootDomains[starts[i]]
        val extra = if (oldMax < orig.max) Lit.make(state.atomVarLe(starts[i], oldMax), false) else 0
        if (oldMax - newMax > d) {
            return windowOverloadReason(state, i, newMax + 1, oldMax + d, eff, extra)
        }
        return pointwiseOverloadReason(state, newMax + d, eff, blamed = i, blamedStart = extra)
    }

    private fun windowOverloadReason(
        state: PropagationState,
        i: Int,
        winLo: Long,
        winHi: Long,
        eff: CumulativeEff,
        extra: Int,
    ): IntArray? {
        val out = IntArrayList()
        if (extra != 0) out.add(extra)
        citeEnergyBounds(out, state, i, eff)
        for (k in 0 until n) {
            if (k == i) continue
            val dk = eff.dur[k]
            val rk = eff.res[k]
            if (dk <= 0 || rk <= 0) continue
            val dom = state.intDomains[starts[k]]
            val lst = dom.max
            val ect = dom.min + dk
            if (lst >= ect) continue
            if (ect <= winLo || lst >= winHi) continue
            val orig = state.rootDomains[starts[k]]
            if (dom.min > orig.min) out.add(Lit.make(state.atomVarGe(starts[k], dom.min), false))
            if (dom.max < orig.max) out.add(Lit.make(state.atomVarLe(starts[k], dom.max), false))
            citeEnergyBounds(out, state, k, eff)
        }
        if (capacityVar >= 0) {
            val orig = state.rootDomains[capacityVar]
            if (eff.cap < orig.max) out.add(Lit.make(state.atomVarLe(capacityVar, eff.cap), false))
        }
        if (out.size == 0) return null
        return out.toIntArray()
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        if (n == 0) return true
        // Energetic-reasoning pass runs first and on variable durations/heights/capacity — unlike the
        // time-tabling / edge-finding below it does not need a fully fixed snapshot, so it is the only
        // filtering that fires while resource demands or durations are still ranges.
        if (!energeticNaivePass(state)) return false
        if (!profileHeightPass(state)) return false
        val eff = effectiveSnapshot(state) ?: return true
        val effDur = eff.dur
        val effRes = eff.res
        val effCap = eff.cap
        for (i in 0 until n) {
            if (OptPresence.isDefinitelyAbsent(presents, i, state)) continue
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
            if (effDur[i] > 0 && effRes[i] > effCap) return false
        }
        if (!edgeFindingPass(state, effDur, effRes, effCap)) return false
        val profile = MandatoryProfile()
        for (i in 0 until n) {
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
            val d = effDur[i]
            val r = effRes[i]
            if (d == 0L || r == 0L) continue
            val dom = state.intDomains[starts[i]]
            profile.addTask(lst = dom.max, ect = dom.min + d, resource = r)
        }
        if (!profile.build(effCap)) return false
        val sharpEff = if (sharpReasonEligible) eff else null
        for (i in 0 until n) {
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
            val d = effDur[i]
            val r = effRes[i]
            if (d == 0L || r == 0L) continue
            val v = starts[i]
            val dom = state.intDomains[v]
            if (dom.min == dom.max) continue
            val oldMin = dom.min
            val oldMax = dom.max
            val lstI = oldMax
            val ectI = oldMin + d
            val ownsMandatory = lstI < ectI
            var newMin = oldMin
            while (newMin <= state.intDomains[v].max) {
                if (profile.overloadsAt(newMin, newMin + d, r, effCap, ownsMandatory, lstI, ectI)) {
                    newMin++
                } else {
                    break
                }
            }
            if (newMin > state.intDomains[v].max) return false
            if (newMin != oldMin) {
                val ant = (if (sharpEff != null) minTightenReason(state, i, d, oldMin, newMin, sharpEff) else null)
                    ?: state.composeIntVarAtomAntecedents(intVars)
                if (!state.tightenIntMin(v, newMin, ant)) return false
            }
            var newMax = state.intDomains[v].max
            while (newMax >= state.intDomains[v].min) {
                if (profile.overloadsAt(newMax, newMax + d, r, effCap, ownsMandatory, lstI, ectI)) {
                    newMax--
                } else {
                    break
                }
            }
            if (newMax < state.intDomains[v].min) return false
            if (newMax != oldMax) {
                val ant = (if (sharpEff != null) maxTightenReason(state, i, d, oldMax, newMax, sharpEff) else null)
                    ?: state.composeIntVarAtomAntecedents(intVars)
                if (!state.tightenIntMax(v, newMax, ant)) return false
            }
        }
        return true
    }

    private fun minDur(state: PropagationState, i: Int): Long =
        if (durationVars.isEmpty()) durations[i] else state.intDomains[durationVars[i]].min

    private fun maxDur(state: PropagationState, i: Int): Long =
        if (durationVars.isEmpty()) durations[i] else state.intDomains[durationVars[i]].max

    private fun minHeight(state: PropagationState, i: Int): Long =
        if (resourceVars.isEmpty()) resources[i] else state.intDomains[resourceVars[i]].min

    private fun capMax(state: PropagationState): Long =
        if (capacityVar < 0) capacity else state.intDomains[capacityVar].max

    /**
     * Naive energetic reasoning (after Baptiste–Le Pape–Nuijten, as in choco's `energyNaive`). For
     * the running window `[xMin, xMax]` spanned by the tasks seen so far in non-decreasing latest
     * completion order, every such task's whole execution lies inside the window, so their minimum
     * energies sum to at most `capacity · |window|`. That bounds the current task's resource height
     * and duration and lower-bounds the capacity, and an exceeded budget is a contradiction. Sound
     * for any task order; unlike the time-tabling pass it reasons over variable demands/durations.
     */
    @Suppress("ReturnCount")
    private fun energeticNaivePass(state: PropagationState): Boolean {
        val act = IntArrayList()
        for (i in 0 until n) {
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
            if (minDur(state, i) > 0 && minHeight(state, i) >= 0) act.add(i)
        }
        val m = act.size
        if (m == 0) return true
        val ids = IntArray(m) { act[it] }
        val est = LongArray(m) { state.intDomains[starts[ids[it]]].min }
        val lct = LongArray(m) { state.intDomains[starts[ids[it]]].max + maxDur(state, ids[it]) }
        val order = argsortBy(m) { a, b -> lct[a].compareTo(lct[b]) }
        val camax = capMax(state)
        val ant = state.composeIntVarAtomAntecedents(intVars)
        var xMin = Long.MAX_VALUE
        var xMax = Long.MIN_VALUE
        var surface = 0L
        for (p in 0 until m) {
            val k = order[p]
            val i = ids[k]
            if (est[k] < xMin) xMin = est[k]
            if (lct[k] > xMax) xMax = lct[k]
            val len = xMax - xMin
            if (len > 0L) {
                val availSurf = len * camax - surface
                if (availSurf < 0L) return false
                val md = minDur(state, i)
                if (resourceVars.isNotEmpty() && md > 0) {
                    if (!state.tightenIntMax(resourceVars[i], availSurf / md, ant)) return false
                }
                val mh = minHeight(state, i)
                if (durationVars.isNotEmpty() && mh > 0) {
                    if (!state.tightenIntMax(durationVars[i], availSurf / mh, ant)) return false
                }
                if (capacityVar >= 0) {
                    val capLb = (surface + len - 1L) / len
                    if (!state.tightenIntMin(capacityVar, capLb, ant)) return false
                }
            }
            surface += minDur(state, i) * minHeight(state, i)
            if (surface > (xMax - xMin) * camax) return false
        }
        return true
    }

    /**
     * Profile-based resource-height pruning (choco's `updateHeights`). A mandatory profile built
     * from each present task's compulsory part `[start.max, start.min + minDur)` at its *minimum*
     * demand bounds how tall a task spanning a peak may be: `height ≤ capacity − (peak − ownMin)`.
     * Sharper than the energetic area bound at a tall, narrow compulsory peak inside a long window.
     * Bounds-only on min demands, so it is sound while heights are still ranges.
     */
    @Suppress("ReturnCount")
    private fun profileHeightPass(state: PropagationState): Boolean {
        if (resourceVars.isEmpty()) return true
        val cap = capMax(state)
        val profile = MandatoryProfile()
        for (i in 0 until n) {
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
            val lst = state.intDomains[starts[i]].max
            val ect = state.intDomains[starts[i]].min + minDur(state, i)
            val h = minHeight(state, i)
            if (lst < ect && h > 0) profile.addTask(lst, ect, h)
        }
        if (!profile.build(cap)) return false
        val ant = state.composeIntVarAtomAntecedents(intVars)
        for (i in 0 until n) {
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
            val lst = state.intDomains[starts[i]].max
            val ect = state.intDomains[starts[i]].min + minDur(state, i)
            if (lst >= ect) continue
            val newUb = cap - (profile.maxLevelOver(lst, ect) - minHeight(state, i))
            if (newUb < state.intDomains[resourceVars[i]].max &&
                !state.tightenIntMax(resourceVars[i], newUb, ant)
            ) {
                return false
            }
        }
        return true
    }

    private fun edgeFindingPass(state: PropagationState, effDur: LongArray, effRes: LongArray, effCap: Long): Boolean {
        if (n < 2 || effCap == 0L) return true
        val active = IntArrayList()
        for (i in 0 until n) {
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
            if (effDur[i] > 0 && effRes[i] > 0) active.add(i)
        }
        val m = active.size
        if (m < 2) return true

        val taskIds = IntArray(m) { active[it] }
        val ests = LongArray(m) { state.intDomains[starts[taskIds[it]]].min }
        val lcts = LongArray(m) { state.intDomains[starts[taskIds[it]]].max + effDur[taskIds[it]] }
        val energies = LongArray(m) { effDur[taskIds[it]] * effRes[taskIds[it]] }
        val cs = LongArray(m) { effRes[taskIds[it]] }

        val estOrder = argsortBy(m) { a, b -> ests[a].compareTo(ests[b]) }
        val leafPos = IntArray(m)
        for (leafIdx in 0 until m) leafPos[estOrder[leafIdx]] = leafIdx
        val lctOrder = argsortBy(m) { a, b -> lcts[a].compareTo(lcts[b]) }

        val tree = CumulativeThetaTree(n = m, capacity = effCap)
        tree.setLeafOrder(leafPos)
        val capL = effCap
        val activeStarts = if (constantEnergyAndCap) IntArrayList() else null
        var scopedAnt: IntArray? = null
        var scopedAntBuilt = false
        var ant: IntArray? = null
        var antBuilt = false

        var k = 0
        while (k < m) {
            val tau = lcts[lctOrder[k]]
            while (k < m && lcts[lctOrder[k]] == tau) {
                val j = lctOrder[k]
                tree.activate(j, ests[j], energies[j])
                activeStarts?.add(starts[taskIds[j]])
                k++
            }
            scopedAntBuilt = false
            val envTheta = tree.envOfTheta()
            val capTau = capL * tau
            if (envTheta > capTau) return false
            for (ki in k until m) {
                val i = lctOrder[ki]
                val eI = energies[i]
                val cI = cs[i]
                val envWith = tree.envIfActivated(i, ests[i], eI)
                if (envWith <= capTau) continue
                val numerator = envTheta - (effCap - cI) * tau
                if (numerator <= 0L) continue
                val newEst = (numerator + cI - 1L) / cI
                val v = starts[taskIds[i]]
                if (newEst > state.intDomains[v].min) {
                    val reason: IntArray?
                    if (activeStarts != null) {
                        if (!scopedAntBuilt) {
                            scopedAnt = state.composeIntVarAtomAntecedents(activeStarts.toIntArray())
                            scopedAntBuilt = true
                        }
                        reason = scopedAnt
                    } else {
                        if (!antBuilt) {
                            ant = state.composeIntVarAtomAntecedents(intVars)
                            antBuilt = true
                        }
                        reason = ant
                    }
                    if (!state.tightenIntMin(v, newEst, reason)) return false
                }
            }
        }
        return true
    }
}
