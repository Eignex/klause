package com.eignex.klause.solver.factor.scheduling

import com.eignex.klause.solver.Invariant
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Move.IntSet
import com.eignex.klause.solver.factor.OptPresence
import com.eignex.klause.solver.factor.compressViolation
import com.eignex.klause.solver.factor.scheduling.internals.CumulativeLsState
import com.eignex.klause.solver.factor.scheduling.internals.applyCumulativeCapacityDelta
import com.eignex.klause.solver.factor.scheduling.internals.applyCumulativeDurDelta
import com.eignex.klause.solver.factor.scheduling.internals.applyCumulativeResDelta
import com.eignex.klause.solver.factor.scheduling.internals.applyCumulativeStartDelta
import com.eignex.klause.solver.factor.scheduling.internals.cumulativeCapacityDelta
import com.eignex.klause.solver.factor.scheduling.internals.simulateCumulativeDurDelta
import com.eignex.klause.solver.factor.scheduling.internals.simulateCumulativeResDelta
import com.eignex.klause.solver.factor.scheduling.internals.simulateCumulativeStartDelta
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.argsortByIntKey
import kotlin.math.max
import kotlin.math.min

/**
 * LS invariant interface for the Cumulative constraint. Default implementations maintain the
 * usage timeline and compute graded overage deltas; concrete classes supply the abstract
 * properties.
 */
internal interface CumulativeInvariant : Invariant {

    val starts: IntArray
    val durations: IntArray
    val resources: IntArray
    val capacity: Int
    val presents: IntArray
    val durationVars: IntArray
    val resourceVars: IntArray
    val capacityVar: Int
    val n: Int
    fun startPosOf(varId: Int): Int
    fun durPosOf(varId: Int): Int
    fun resPosOf(varId: Int): Int

    fun curDur(state: LocalSearchState, i: Int): Int =
        if (durationVars.isEmpty()) durations[i] else state.assignment.intValue(durationVars[i])

    fun curRes(state: LocalSearchState, i: Int): Int =
        if (resourceVars.isEmpty()) resources[i] else state.assignment.intValue(resourceVars[i])

    fun curCap(state: LocalSearchState): Int = if (capacityVar < 0) capacity else state.assignment.intValue(capacityVar)

    override fun initialize(state: LocalSearchState, factorId: Int) {
        val tLow = computeTLow(state)
        val tHigh = computeTHigh(state)
        val size = max(0, tHigh - tLow)
        val usage = IntArray(size)
        for (i in 0 until n) {
            if (!OptPresence.isPresentInAssignment(presents, i, state)) continue
            val s = state.assignment.intValue(starts[i])
            val d = curDur(state, i)
            val r = curRes(state, i)
            if (d <= 0 || r <= 0) continue
            val from = max(0, s - tLow)
            val to = min(size, s + d - tLow)
            for (t in from until to) usage[t] += r
        }
        val cap = curCap(state)
        var ov = 0
        for (t in usage.indices) {
            val u = usage[t]
            if (u > cap) ov += u - cap
        }
        val ls = CumulativeLsState(tLow, usage, ov, cap)
        state.refPayload[factorId] = ls
        state.intPayload[factorId] = ov
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = state.intPayload[factorId] > 0

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        compressViolation(state.intPayload[factorId].toLong(), state.violationSoftCap)

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val ls = state.refPayload[factorId] as CumulativeLsState
        val oldVal = state.assignment.intValue(intVar)
        if (oldVal == newValue) return 0
        val delta = when {
            intVar == capacityVar -> cumulativeCapacityDelta(ls, newValue)

            else -> {
                val sp = startPosOf(intVar)
                if (sp >= 0) {
                    if (!OptPresence.isPresentInAssignment(presents, sp, state)) {
                        0
                    } else {
                        val d = curDur(state, sp)
                        val r = curRes(state, sp)
                        if (d <= 0 || r <= 0) {
                            0
                        } else {
                            simulateCumulativeStartDelta(ls, oldVal, newValue, d, r)
                        }
                    }
                } else {
                    val dp = durPosOf(intVar)
                    if (dp >= 0) {
                        if (!OptPresence.isPresentInAssignment(presents, dp, state)) {
                            0
                        } else {
                            val r = curRes(state, dp)
                            if (r <= 0) {
                                0
                            } else {
                                val s = state.assignment.intValue(starts[dp])
                                simulateCumulativeDurDelta(ls, s, oldVal, newValue, r)
                            }
                        }
                    } else {
                        val rp = resPosOf(intVar)
                        if (rp >= 0) {
                            if (!OptPresence.isPresentInAssignment(presents, rp, state)) {
                                0
                            } else {
                                val d = curDur(state, rp)
                                if (d <= 0) {
                                    0
                                } else {
                                    val s = state.assignment.intValue(starts[rp])
                                    simulateCumulativeResDelta(ls, s, d, oldVal, newValue)
                                }
                            }
                        } else {
                            0
                        }
                    }
                }
            }
        }
        return compressViolation((ls.overage + delta).toLong(), state.violationSoftCap) -
            compressViolation(ls.overage.toLong(), state.violationSoftCap)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val ls = state.refPayload[factorId] as CumulativeLsState
        val newValue = state.assignment.intValue(intVar)
        val before = ls.overage
        if (oldValue == newValue) return 0
        when {
            intVar == capacityVar -> applyCumulativeCapacityDelta(ls, newValue)

            else -> {
                val sp = startPosOf(intVar)
                if (sp >= 0) {
                    if (!OptPresence.isPresentInAssignment(presents, sp, state)) return 0
                    val d = curDur(state, sp)
                    val r = curRes(state, sp)
                    if (d <= 0 || r <= 0) return 0
                    applyCumulativeStartDelta(ls, oldValue, newValue, d, r)
                } else {
                    val dp = durPosOf(intVar)
                    if (dp >= 0) {
                        if (!OptPresence.isPresentInAssignment(presents, dp, state)) return 0
                        val r = curRes(state, dp)
                        if (r <= 0) return 0
                        val s = state.assignment.intValue(starts[dp])
                        applyCumulativeDurDelta(ls, s, oldValue, newValue, r)
                    } else {
                        val rp = resPosOf(intVar)
                        if (rp < 0) return 0
                        if (!OptPresence.isPresentInAssignment(presents, rp, state)) return 0
                        val d = curDur(state, rp)
                        if (d <= 0) return 0
                        val s = state.assignment.intValue(starts[rp])
                        applyCumulativeResDelta(ls, s, d, oldValue, newValue)
                    }
                }
            }
        }
        state.intPayload[factorId] = ls.overage
        return compressViolation(ls.overage.toLong(), state.violationSoftCap) -
            compressViolation(before.toLong(), state.violationSoftCap)
    }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (presents.isEmpty()) return 0
        val ls = state.refPayload[factorId] as CumulativeLsState
        val cap = ls.cap
        var deltaOv = 0
        for (i in presents.indices) {
            if (Lit.variable(presents[i]) != boolVar) continue
            val d = curDur(state, i)
            val r = curRes(state, i)
            if (d <= 0 || r <= 0) continue
            val wasP = OptPresence.isPresentInAssignment(presents, i, state)
            val sign = if (wasP) -1 else +1
            val s = state.assignment.intValue(starts[i])
            val from = max(0, s - ls.tLow)
            val to = min(ls.usage.size, s + d - ls.tLow)
            for (t in from until to) {
                val u = ls.usage[t]
                val nu = u + sign * r
                deltaOv += max(0, nu - cap) - max(0, u - cap)
            }
        }
        return compressViolation((ls.overage + deltaOv).toLong(), state.violationSoftCap) -
            compressViolation(ls.overage.toLong(), state.violationSoftCap)
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (presents.isEmpty()) return 0
        val ls = state.refPayload[factorId] as CumulativeLsState
        val cap = ls.cap
        val before = ls.overage
        var deltaOv = 0
        for (i in presents.indices) {
            if (Lit.variable(presents[i]) != boolVar) continue
            val d = curDur(state, i)
            val r = curRes(state, i)
            if (d <= 0 || r <= 0) continue
            val nowP = OptPresence.isPresentInAssignment(presents, i, state)
            val sign = if (nowP) +1 else -1
            val s = state.assignment.intValue(starts[i])
            val from = max(0, s - ls.tLow)
            val to = min(ls.usage.size, s + d - ls.tLow)
            for (t in from until to) {
                val u = ls.usage[t]
                val nu = u + sign * r
                ls.usage[t] = nu
                deltaOv += max(0, nu - cap) - max(0, u - cap)
            }
        }
        ls.overage += deltaOv
        state.intPayload[factorId] = ls.overage
        return compressViolation(ls.overage.toLong(), state.violationSoftCap) -
            compressViolation(before.toLong(), state.violationSoftCap)
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (state.intPayload[factorId] == 0) return
        val ls = state.refPayload[factorId] as CumulativeLsState
        var peakT = -1
        var peakV = ls.cap
        val usage = ls.usage
        for (t in usage.indices) {
            if (usage[t] > peakV) {
                peakV = usage[t]
                peakT = t
            }
        }
        val tLow = ls.tLow
        val absT = if (peakT >= 0) peakT + tLow else 0
        val peakTasks = if (peakT >= 0) collectPeakTasks(state, absT) else IntArray(0)
        val maxTargets = 4
        for (i in 0 until n) {
            val v = starts[i]
            val cur = state.assignment.intValue(v)
            val d = curDur(state, i)
            val r = curRes(state, i)
            val dom = state.problem.intDomains[v]
            val runsAtPeak = (peakT >= 0 && r > 0 && d > 0 && cur <= absT && absT < cur + d)
            if (runsAtPeak) {
                val afterPeak = absT + 1
                if (afterPeak in dom && afterPeak != cur) sink.addChannelingIntSet(state, v, afterPeak)
                val beforePeak = absT - d
                if (beforePeak in dom && beforePeak != cur) sink.addChannelingIntSet(state, v, beforePeak)
            }
            if (cur < dom.max) sink.addChannelingIntSet(state, v, cur + 1)
            if (cur > dom.min) sink.addChannelingIntSet(state, v, cur - 1)
            if (dom.size <= maxTargets) {
                dom.forEach { target -> if (target != cur) sink.addChannelingIntSet(state, v, target) }
            } else {
                repeat(maxTargets) {
                    val pick = dom.valueAt(state.rng.nextInt(dom.size))
                    if (pick != cur) sink.addChannelingIntSet(state, v, pick)
                }
            }
        }
        if (peakTasks.isNotEmpty()) emitFeasibleSwaps(state, ls, peakTasks, sink)
    }

    fun collectPeakTasks(state: LocalSearchState, absT: Int): IntArray {
        val out = IntArrayList()
        for (i in 0 until n) {
            val r = curRes(state, i)
            val d = curDur(state, i)
            if (r <= 0 || d <= 0) continue
            if (!OptPresence.isPresentInAssignment(presents, i, state)) continue
            val cur = state.assignment.intValue(starts[i])
            if (cur <= absT && absT < cur + d) out.add(i)
        }
        return out.toIntArray()
    }

    fun emitFeasibleSwaps(state: LocalSearchState, ls: CumulativeLsState, peakTasks: IntArray, sink: MoveSink) {
        var swapsAdded = 0
        for (i in peakTasks) {
            if (swapsAdded >= CUMULATIVE_MAX_SWAPS) break
            val iV = starts[i]
            val iCur = state.assignment.intValue(iV)
            val iDom = state.problem.intDomains[iV]
            for (j in 0 until n) {
                if (swapsAdded >= CUMULATIVE_MAX_SWAPS) break
                if (j == i) continue
                val dj0 = curDur(state, j)
                val rj0 = curRes(state, j)
                if (dj0 <= 0 || rj0 <= 0) continue
                if (!OptPresence.isPresentInAssignment(presents, j, state)) continue
                val jV = starts[j]
                val jCur = state.assignment.intValue(jV)
                if (jCur !in iDom || iCur !in state.problem.intDomains[jV]) continue
                if (jCur == iCur) continue
                val di = simulateCumulativeStartDelta(ls, iCur, jCur, curDur(state, i), curRes(state, i))
                val dj = simulateCumulativeStartDelta(ls, jCur, iCur, dj0, rj0)
                if (di + dj >= 0) continue
                sink.addCompound(
                    listOf(
                        IntSet(iV, jCur),
                        IntSet(jV, iCur),
                    ),
                )
                swapsAdded++
            }
        }
    }

    override val providesImplicitNeighbourhood: Boolean get() = true

    override fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (n < 2) return
        var emitted = 0
        var attempts = 0
        while (emitted < CUMULATIVE_STRUCTURED_SWAP_CAP &&
            attempts < CUMULATIVE_STRUCTURED_SWAP_CAP * CUMULATIVE_SWAP_ATTEMPT_STRIDE
        ) {
            attempts++
            val i = state.rng.nextInt(n)
            val j = state.rng.nextInt(n)
            if (i == j || starts[i] == starts[j]) continue
            if (!OptPresence.isPresentInAssignment(
                    presents,
                    i,
                    state,
                ) || !OptPresence.isPresentInAssignment(presents, j, state)
            ) {
                continue
            }
            if (curDur(state, i) != curDur(state, j) || curRes(state, i) != curRes(state, j)) continue
            val si = state.assignment.intValue(starts[i])
            val sj = state.assignment.intValue(starts[j])
            if (si == sj) continue
            if (sj !in state.problem.intDomains[starts[i]] || si !in state.problem.intDomains[starts[j]]) continue
            sink.addCompound(listOf(IntSet(starts[i], sj), IntSet(starts[j], si)))
            emitted++
        }
    }

    override fun seedFeasible(state: LocalSearchState, factorId: Int): Boolean {
        if (starts.isEmpty()) return false
        val cap = curCap(state)
        val order = argsortByIntKey(starts.size) { state.problem.intDomains[starts[it]].min }
        var prevEnd = Int.MIN_VALUE
        for (oi in order.indices) {
            val i = order[oi]
            if (!OptPresence.isPresentInAssignment(presents, i, state)) continue
            if (curRes(state, i) > cap) return false
            val dur = curDur(state, i)
            val v = starts[i]
            if (state.assumptions.isFrozenInt(v)) {
                val s = state.assignment.intValue(v)
                if (s < prevEnd) return false
                prevEnd = s + dur
            } else {
                val d = state.problem.intDomains[v]
                val cand = max(d.min, prevEnd)
                if (cand > d.max) return false
                var s = -1
                d.forEach { if (s < 0 && it >= cand) s = it }
                if (s < 0) return false
                state.assignment.setInt(v, s)
                prevEnd = s + dur
            }
        }
        return true
    }

    fun computeTLow(state: LocalSearchState): Int {
        var lo = Int.MAX_VALUE
        for (i in 0 until n) {
            lo = min(lo, min(state.problem.intDomains[starts[i]].min, state.assignment.intValue(starts[i])))
        }
        return if (lo == Int.MAX_VALUE) 0 else lo
    }

    fun computeTHigh(state: LocalSearchState): Int {
        var hi = Int.MIN_VALUE
        for (i in 0 until n) {
            val dUb = if (durationVars.isEmpty()) {
                durations[i]
            } else {
                max(durations[i], state.problem.intDomains[durationVars[i]].max)
            }
            val cand = max(state.problem.intDomains[starts[i]].max, state.assignment.intValue(starts[i])) + dUb
            hi = max(hi, cand)
        }
        return if (hi == Int.MIN_VALUE) 0 else hi
    }
}

private const val CUMULATIVE_MAX_SWAPS: Int = 4
private const val CUMULATIVE_STRUCTURED_SWAP_CAP: Int = 4
private const val CUMULATIVE_SWAP_ATTEMPT_STRIDE: Int = 8
