package com.eignex.klause.solver.factor.scheduling

import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.factor.OptPresence
import com.eignex.klause.solver.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.solver.factor.scheduling.internals.CumulativeEff
import com.eignex.klause.solver.factor.scheduling.internals.CumulativeThetaTree
import com.eignex.klause.solver.factor.scheduling.internals.MandatoryProfile
import com.eignex.klause.solver.propagation.IntEvent
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.argsortByIntKey

/**
 * CP propagator for [Cumulative]. Constructed by [Cumulative.asPropagator] and holds the
 * time-tabling and Vilím Θ-tree edge-finding logic so those data structures are only allocated
 * when a CP engine is initialised.
 */
internal class CumulativePropagator(
    override val boolVars: IntArray,
    override val intVars: IntArray,
    private val starts: IntArray,
    private val durations: IntArray,
    private val resources: IntArray,
    private val capacity: Int,
    private val presents: IntArray,
    private val durationVars: IntArray,
    private val resourceVars: IntArray,
    private val capacityVar: Int,
    private val n: Int,
    private val sharpReasonEligible: Boolean,
    private val constantEnergyAndCap: Boolean,
) : Propagator {

    override val initialIntEventWatches: IntArray = IntEvent.boundEventWatches(intVars)

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? {
        val fallback = collectLinearTightenAntecedents(state, intVars, excludeIdx = -1, extraLit = 0)
        if (!sharpReasonEligible) return fallback
        val eff = effectiveSnapshot(state) ?: return fallback
        val profile = MandatoryProfile()
        for (i in 0 until n) {
            val d = eff.dur[i]
            val r = eff.res[i]
            if (d == 0 || r == 0) continue
            val dom = state.intDomains[starts[i]]
            profile.addTask(lst = dom.max, ect = dom.min + d, resource = r)
        }
        if (profile.build(eff.cap)) return fallback
        return pointwiseOverloadReason(state, profile.overloadTime, eff, blamed = -1, blamedStart = 0) ?: fallback
    }

    private fun effectiveSnapshot(state: PropagationState): CumulativeEff? {
        val dur = IntArray(n)
        val res = IntArray(n)
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
        t: Int,
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
            val orig = state.problem.intDomains[starts[k]]
            if (t < orig.max) out.add(Lit.make(state.atomVarLe(starts[k], t), false))
            val geThreshold = t - d + 1
            if (geThreshold > orig.min) out.add(Lit.make(state.atomVarGe(starts[k], geThreshold), false))
            citeEnergyBounds(out, state, k, eff)
        }
        if (capacityVar >= 0) {
            val orig = state.problem.intDomains[capacityVar]
            if (eff.cap < orig.max) out.add(Lit.make(state.atomVarLe(capacityVar, eff.cap), false))
        }
        if (out.size == 0) return null
        return out.toIntArray()
    }

    private fun citeEnergyBounds(out: IntArrayList, state: PropagationState, k: Int, eff: CumulativeEff) {
        if (durationVars.isNotEmpty()) {
            val dv = durationVars[k]
            if (eff.dur[k] > state.problem.intDomains[dv].min) out.add(Lit.make(state.atomVarGe(dv, eff.dur[k]), false))
        }
        if (resourceVars.isNotEmpty()) {
            val rv = resourceVars[k]
            if (eff.res[k] > state.problem.intDomains[rv].min) out.add(Lit.make(state.atomVarGe(rv, eff.res[k]), false))
        }
    }

    private fun minTightenReason(
        state: PropagationState,
        i: Int,
        d: Int,
        oldMin: Int,
        newMin: Int,
        eff: CumulativeEff,
    ): IntArray? {
        val orig = state.problem.intDomains[starts[i]]
        val extra = if (oldMin > orig.min) Lit.make(state.atomVarGe(starts[i], oldMin), false) else 0
        if (newMin - oldMin > d) {
            return windowOverloadReason(state, i, oldMin, newMin - 1 + d, eff, extra)
        }
        return pointwiseOverloadReason(state, newMin - 1, eff, blamed = i, blamedStart = extra)
    }

    private fun maxTightenReason(
        state: PropagationState,
        i: Int,
        d: Int,
        oldMax: Int,
        newMax: Int,
        eff: CumulativeEff,
    ): IntArray? {
        val orig = state.problem.intDomains[starts[i]]
        val extra = if (oldMax < orig.max) Lit.make(state.atomVarLe(starts[i], oldMax), false) else 0
        if (oldMax - newMax > d) {
            return windowOverloadReason(state, i, newMax + 1, oldMax + d, eff, extra)
        }
        return pointwiseOverloadReason(state, newMax + d, eff, blamed = i, blamedStart = extra)
    }

    private fun windowOverloadReason(
        state: PropagationState,
        i: Int,
        winLo: Int,
        winHi: Int,
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
            val orig = state.problem.intDomains[starts[k]]
            if (dom.min > orig.min) out.add(Lit.make(state.atomVarGe(starts[k], dom.min), false))
            if (dom.max < orig.max) out.add(Lit.make(state.atomVarLe(starts[k], dom.max), false))
            citeEnergyBounds(out, state, k, eff)
        }
        if (capacityVar >= 0) {
            val orig = state.problem.intDomains[capacityVar]
            if (eff.cap < orig.max) out.add(Lit.make(state.atomVarLe(capacityVar, eff.cap), false))
        }
        if (out.size == 0) return null
        return out.toIntArray()
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        if (n == 0) return true
        val effDur = IntArray(n)
        val effRes = IntArray(n)
        for (i in 0 until n) {
            if (durationVars.isEmpty()) {
                effDur[i] = durations[i]
            } else {
                val d = state.intDomains[durationVars[i]]
                if (d.min != d.max) return true
                effDur[i] = d.min
            }
            if (resourceVars.isEmpty()) {
                effRes[i] = resources[i]
            } else {
                val d = state.intDomains[resourceVars[i]]
                if (d.min != d.max) return true
                effRes[i] = d.min
            }
        }
        val effCap = if (capacityVar < 0) {
            capacity
        } else {
            val d = state.intDomains[capacityVar]
            if (d.min != d.max) return true
            d.min
        }
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
            if (d == 0 || r == 0) continue
            val dom = state.intDomains[starts[i]]
            profile.addTask(lst = dom.max, ect = dom.min + d, resource = r)
        }
        if (!profile.build(effCap)) return false
        val eff = if (sharpReasonEligible) CumulativeEff(effDur, effRes, effCap) else null
        for (i in 0 until n) {
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
            val d = effDur[i]
            val r = effRes[i]
            if (d == 0 || r == 0) continue
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
                val ant = (if (eff != null) minTightenReason(state, i, d, oldMin, newMin, eff) else null)
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
                val ant = (if (eff != null) maxTightenReason(state, i, d, oldMax, newMax, eff) else null)
                    ?: state.composeIntVarAtomAntecedents(intVars)
                if (!state.tightenIntMax(v, newMax, ant)) return false
            }
        }
        return true
    }

    private fun edgeFindingPass(state: PropagationState, effDur: IntArray, effRes: IntArray, effCap: Int): Boolean {
        if (n < 2 || effCap == 0) return true
        val active = IntArrayList()
        for (i in 0 until n) {
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
            if (effDur[i] > 0 && effRes[i] > 0) active.add(i)
        }
        val m = active.size
        if (m < 2) return true

        val taskIds = IntArray(m) { active[it] }
        val ests = IntArray(m) { state.intDomains[starts[taskIds[it]]].min }
        val lcts = IntArray(m) { state.intDomains[starts[taskIds[it]]].max + effDur[taskIds[it]] }
        val energies = LongArray(m) { effDur[taskIds[it]].toLong() * effRes[taskIds[it]].toLong() }
        val cs = IntArray(m) { effRes[taskIds[it]] }

        val estOrder = argsortByIntKey(m) { ests[it] }
        val leafPos = IntArray(m)
        for (leafIdx in 0 until m) leafPos[estOrder[leafIdx]] = leafIdx
        val lctOrder = argsortByIntKey(m) { lcts[it] }

        val tree = CumulativeThetaTree(n = m, capacity = effCap)
        tree.setLeafOrder(leafPos)
        val capL = effCap.toLong()
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
            val capTau = capL * tau.toLong()
            if (envTheta > capTau) return false
            for (ki in k until m) {
                val i = lctOrder[ki]
                val eI = energies[i]
                val cI = cs[i]
                val envWith = tree.envIfActivated(i, ests[i], eI)
                if (envWith <= capTau) continue
                val numerator = envTheta - (effCap - cI).toLong() * tau.toLong()
                if (numerator <= 0L) continue
                val newEstL = (numerator + cI - 1L) / cI.toLong()
                if (newEstL > Int.MAX_VALUE.toLong()) continue
                val newEst = newEstL.toInt()
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
