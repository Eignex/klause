package com.eignex.klause.factor.scheduling

import com.eignex.klause.factor.compressViolation
import com.eignex.klause.factor.scheduling.internals.CumulativeLsState
import com.eignex.klause.factor.scheduling.internals.applyCumulativeCapacityDelta
import com.eignex.klause.factor.scheduling.internals.applyCumulativeDurDelta
import com.eignex.klause.factor.scheduling.internals.applyCumulativeResDelta
import com.eignex.klause.factor.scheduling.internals.applyCumulativeStartDelta
import com.eignex.klause.factor.scheduling.internals.cumulativeCapacityDelta
import com.eignex.klause.factor.scheduling.internals.firstInDomainAtLeast
import com.eignex.klause.factor.scheduling.internals.simulateCumulativeDurDelta
import com.eignex.klause.factor.scheduling.internals.simulateCumulativeResDelta
import com.eignex.klause.factor.scheduling.internals.simulateCumulativeStartDelta
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.randomValue
import com.eignex.klause.localsearch.ChannelingSink
import com.eignex.klause.localsearch.ConditionalProductRepair
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LiteralIntDefinition
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.Move.IntSet
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.localsearch.OptionalPresence
import com.eignex.klause.localsearch.positiveScaledBinaryDefinition
import com.eignex.klause.solver.objective.FunctionalObjective
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntIntMap
import com.eignex.klause.util.argsortBy
import kotlin.math.max
import kotlin.math.min

/**
 * LS invariant for [Cumulative]. Constructed by the local-search projection and maintains the
 * usage timeline and computes graded overage deltas.
 */
internal class CumulativeInvariant(
    private val starts: IntArray,
    private val durations: LongArray,
    private val resources: LongArray,
    private val capacity: Long,
    private val presents: IntArray,
    private val durationVars: IntArray,
    private val resourceVars: IntArray,
    private val capacityVar: Int,
    private val n: Int,
    private val startPosOf: (Int) -> Int,
    private val durPosOf: (Int) -> Int,
    private val resPosOf: (Int) -> Int,
) : Invariant {

    private val aliasPositionsByVariable = run {
        val positions = mutableMapOf<Int, MutableList<Int>>()
        for (role in arrayOf(starts, durationVars, resourceVars)) {
            for ((task, variable) in role.withIndex()) positions.getOrPut(variable) { ArrayList() }.add(task)
        }
        if (capacityVar >= 0) positions.getOrPut(capacityVar) { ArrayList() }.add(-1)
        positions.filterValues { it.size > 1 }.mapValues { (_, tasks) ->
            tasks.filter { it >= 0 }.distinct().toIntArray()
        }
    }
    private val aliasVariables = aliasPositionsByVariable.keys.toIntArray()
    private val aliasIndex = IntIntMap.build(aliasVariables, IntArray(aliasVariables.size) { it }, absent = -1)
    private val aliasPositions = Array(aliasVariables.size) { aliasPositionsByVariable.getValue(aliasVariables[it]) }
    private val repeatedPresence = presents.map { Lit.variable(it) }.groupingBy { it }.eachCount()
        .filterValues { it > 1 }.keys.toIntArray().let { variables ->
            IntIntMap.build(variables, IntArray(variables.size) { 1 })
        }

    private fun curDur(state: LocalSearchState, i: Int): Long =
        if (durationVars.isEmpty()) durations[i] else state.assignment.intValue(durationVars[i])

    private fun curRes(state: LocalSearchState, i: Int): Long =
        if (resourceVars.isEmpty()) resources[i] else state.assignment.intValue(resourceVars[i])

    private fun curCap(state: LocalSearchState): Long = if (capacityVar < 0) {
        capacity
    } else {
        state.assignment.intValue(
            capacityVar,
        )
    }

    override fun initialize(state: LocalSearchState, factorId: Int) {
        val tLow = computeTLow(state)
        val tHigh = computeTHigh(state)
        val size = max(0L, tHigh - tLow).toInt()
        val usage = LongArray(size)
        for (i in 0 until n) {
            if (!OptionalPresence.isPresentInAssignment(presents, i, state)) continue
            val s = state.assignment.intValue(starts[i])
            val d = curDur(state, i)
            val r = curRes(state, i)
            if (d <= 0 || r <= 0) continue
            val from = max(0L, s - tLow).toInt()
            val to = min(size.toLong(), s + d - tLow).toInt()
            for (t in from until to) usage[t] += r
        }
        val cap = curCap(state)
        var ov = 0L
        for (t in usage.indices) {
            val u = usage[t]
            if (u > cap) ov += u - cap
        }
        val ls = CumulativeLsState(tLow, usage, ov, cap)
        state.refPayload[factorId] = ls
        state.intPayload[factorId] = clampOverage(ov)
    }

    // The int payload mirrors the raw overage for the `isViolated` / repair-guard checks (and ALNS
    // reads it directly); saturate at Int range since a wide profile can exceed 32 bits — overage is
    // non-negative, so `> 0` / `== 0` are preserved exactly.
    private fun clampOverage(ov: Long): Int = ov.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun overageDegree(state: LocalSearchState, overage: Long): Int =
        compressViolation(clampOverage(overage).toLong(), state.violationSoftCap)

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = state.intPayload[factorId] > 0

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        compressViolation(state.intPayload[factorId].toLong(), state.violationSoftCap)

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int {
        val ls = state.refPayload[factorId] as CumulativeLsState
        val oldVal = state.assignment.intValue(intVar)
        if (oldVal == newValue) return 0
        val alias = aliasIndex[intVar]
        val delta: Long = when {
            alias >= 0 -> {
                val simulated = CumulativeLsState(ls.tLow, ls.usage.copyOf(), ls.overage, ls.cap)
                applyAliasedIntSet(state, simulated, intVar, oldVal, newValue, aliasPositions[alias])
                simulated.overage - ls.overage
            }
            intVar == capacityVar -> cumulativeCapacityDelta(ls, newValue)

            else -> {
                val sp = startPosOf(intVar)
                if (sp >= 0) {
                    if (!OptionalPresence.isPresentInAssignment(presents, sp, state)) {
                        0L
                    } else {
                        val d = curDur(state, sp)
                        val r = curRes(state, sp)
                        if (d <= 0 || r <= 0) {
                            0L
                        } else {
                            simulateCumulativeStartDelta(ls, oldVal, newValue, d, r)
                        }
                    }
                } else {
                    val dp = durPosOf(intVar)
                    if (dp >= 0) {
                        if (!OptionalPresence.isPresentInAssignment(presents, dp, state)) {
                            0L
                        } else {
                            val r = curRes(state, dp)
                            if (r <= 0) {
                                0L
                            } else {
                                val s = state.assignment.intValue(starts[dp])
                                simulateCumulativeDurDelta(ls, s, oldVal, newValue, r)
                            }
                        }
                    } else {
                        val rp = resPosOf(intVar)
                        if (rp >= 0) {
                            if (!OptionalPresence.isPresentInAssignment(presents, rp, state)) {
                                0L
                            } else {
                                val d = curDur(state, rp)
                                if (d <= 0) {
                                    0L
                                } else {
                                    val s = state.assignment.intValue(starts[rp])
                                    simulateCumulativeResDelta(ls, s, d, oldVal, newValue)
                                }
                            }
                        } else {
                            0L
                        }
                    }
                }
            }
        }
        return overageDegree(state, ls.overage + delta) - overageDegree(state, ls.overage)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Int {
        val ls = state.refPayload[factorId] as CumulativeLsState
        val newValue = state.assignment.intValue(intVar)
        val before = ls.overage
        if (oldValue == newValue) return 0
        val alias = aliasIndex[intVar]
        when {
            alias >= 0 -> applyAliasedIntSet(state, ls, intVar, oldValue, newValue, aliasPositions[alias])
            intVar == capacityVar -> applyCumulativeCapacityDelta(ls, newValue)

            else -> {
                val sp = startPosOf(intVar)
                if (sp >= 0) {
                    if (!OptionalPresence.isPresentInAssignment(presents, sp, state)) return 0
                    val d = curDur(state, sp)
                    val r = curRes(state, sp)
                    if (d <= 0 || r <= 0) return 0
                    applyCumulativeStartDelta(ls, oldValue, newValue, d, r)
                } else {
                    val dp = durPosOf(intVar)
                    if (dp >= 0) {
                        if (!OptionalPresence.isPresentInAssignment(presents, dp, state)) return 0
                        val r = curRes(state, dp)
                        if (r <= 0) return 0
                        val s = state.assignment.intValue(starts[dp])
                        applyCumulativeDurDelta(ls, s, oldValue, newValue, r)
                    } else {
                        val rp = resPosOf(intVar)
                        if (rp < 0) return 0
                        if (!OptionalPresence.isPresentInAssignment(presents, rp, state)) return 0
                        val d = curDur(state, rp)
                        if (d <= 0) return 0
                        val s = state.assignment.intValue(starts[rp])
                        applyCumulativeResDelta(ls, s, d, oldValue, newValue)
                    }
                }
            }
        }
        state.intPayload[factorId] = clampOverage(ls.overage)
        return overageDegree(state, ls.overage) - overageDegree(state, before)
    }

    private fun applyAliasedIntSet(
        state: LocalSearchState,
        ls: CumulativeLsState,
        intVar: Int,
        oldValue: Long,
        newValue: Long,
        tasks: IntArray,
    ) {
        // Substitute every role from the same before/after assignment; role precedence would strand aliases.
        for (task in tasks) {
            if (!OptionalPresence.isPresentInAssignment(presents, task, state)) continue
            val start = state.assignment.intValue(starts[task])
            val duration = curDur(state, task)
            val resource = curRes(state, task)
            val oldStart = if (starts[task] == intVar) oldValue else start
            val newStart = if (starts[task] == intVar) newValue else start
            val changesDuration = durationVars.isNotEmpty() && durationVars[task] == intVar
            val oldDuration = if (changesDuration) oldValue else duration
            val newDuration = if (changesDuration) newValue else duration
            val changesResource = resourceVars.isNotEmpty() && resourceVars[task] == intVar
            val oldResource = if (changesResource) oldValue else resource
            val newResource = if (changesResource) newValue else resource
            if (oldDuration == newDuration && oldResource == newResource) {
                if (oldDuration > 0 && oldResource > 0) {
                    applyCumulativeStartDelta(ls, oldStart, newStart, oldDuration, oldResource)
                }
            } else {
                if (oldResource > 0) applyCumulativeResDelta(ls, oldStart, oldDuration, oldResource, 0)
                if (newResource > 0) applyCumulativeResDelta(ls, newStart, newDuration, 0, newResource)
            }
        }
        if (intVar == capacityVar) applyCumulativeCapacityDelta(ls, newValue)
    }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (presents.isEmpty()) return 0
        val ls = state.refPayload[factorId] as CumulativeLsState
        val cap = ls.cap
        // Shared presence footprints must see the load changes of earlier tasks in the same flip.
        val simulatedUsage = if (repeatedPresence[boolVar] != 0) ls.usage.copyOf() else null
        val usage = simulatedUsage ?: ls.usage
        var deltaOv = 0L
        for (i in presents.indices) {
            if (Lit.variable(presents[i]) != boolVar) continue
            val d = curDur(state, i)
            val r = curRes(state, i)
            if (d <= 0 || r <= 0) continue
            val wasP = OptionalPresence.isPresentInAssignment(presents, i, state)
            val sign = if (wasP) -1 else +1
            val s = state.assignment.intValue(starts[i])
            val from = max(0L, s - ls.tLow).toInt()
            val to = min(ls.usage.size.toLong(), s + d - ls.tLow).toInt()
            for (t in from until to) {
                val u = usage[t]
                val nu = u + sign * r
                if (simulatedUsage != null) simulatedUsage[t] = nu
                deltaOv += max(0L, nu - cap) - max(0L, u - cap)
            }
        }
        return overageDegree(state, ls.overage + deltaOv) - overageDegree(state, ls.overage)
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (presents.isEmpty()) return 0
        val ls = state.refPayload[factorId] as CumulativeLsState
        val cap = ls.cap
        val before = ls.overage
        var deltaOv = 0L
        for (i in presents.indices) {
            if (Lit.variable(presents[i]) != boolVar) continue
            val d = curDur(state, i)
            val r = curRes(state, i)
            if (d <= 0 || r <= 0) continue
            val nowP = OptionalPresence.isPresentInAssignment(presents, i, state)
            val sign = if (nowP) +1 else -1
            val s = state.assignment.intValue(starts[i])
            val from = max(0L, s - ls.tLow).toInt()
            val to = min(ls.usage.size.toLong(), s + d - ls.tLow).toInt()
            for (t in from until to) {
                val u = ls.usage[t]
                val nu = u + sign * r
                ls.usage[t] = nu
                deltaOv += max(0L, nu - cap) - max(0L, u - cap)
            }
        }
        ls.overage += deltaOv
        state.intPayload[factorId] = clampOverage(ls.overage)
        return overageDegree(state, ls.overage) - overageDegree(state, before)
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
        val absT = if (peakT >= 0) peakT + tLow else 0L
        val peakTasks = if (peakT >= 0) collectPeakTasks(state, absT) else EmptyIntArray
        emitConditionalDurationRepairs(state, ls, peakTasks, sink)
        val maxTargets = 4
        for (i in 0 until n) {
            if (!OptionalPresence.isPresentInAssignment(presents, i, state)) continue
            val v = starts[i]
            val cur = state.assignment.intValue(v)
            val d = curDur(state, i)
            val r = curRes(state, i)
            if (d <= 0 || r <= 0) continue
            val dom = state.rootDomains[v]
            val runsAtPeak = (peakT >= 0 && cur <= absT && absT < cur + d)
            if (runsAtPeak) {
                val afterPeak = absT + 1
                if (afterPeak in dom && afterPeak != cur) sink.addChannelingIntSet(state, v, afterPeak)
                val beforePeak = absT - d
                if (beforePeak in dom && beforePeak != cur) sink.addChannelingIntSet(state, v, beforePeak)
            }
            if (cur < dom.max) sink.addChannelingIntSet(state, v, dom.higher(cur))
            if (cur > dom.min) sink.addChannelingIntSet(state, v, dom.lower(cur))
            val few = dom.spanOrNull(maxTargets.toLong())
            if (few != null) {
                few.forEach { target -> if (target != cur) sink.addChannelingIntSet(state, v, target) }
            } else {
                repeat(maxTargets) {
                    val pick = dom.randomValue(state.rng)
                    if (pick != cur) sink.addChannelingIntSet(state, v, pick)
                }
            }
        }
        if (peakTasks.isNotEmpty()) {
            emitFeasibleSwaps(state, ls, peakTasks, sink)
            emitConditionalChoiceSwaps(state, ls, peakTasks, sink)
        }
    }

    private fun emitConditionalDurationRepairs(
        state: LocalSearchState,
        ls: CumulativeLsState,
        peakTasks: IntArray,
        sink: MoveSink,
    ) {
        if (durationVars.isEmpty() || peakTasks.isEmpty()) return
        var first = 0
        while (first < peakTasks.size && peakTasks[first] < ls.durationRepairCursor) first++
        if (first == peakTasks.size) first = 0
        var added = 0
        for (offset in peakTasks.indices) {
            if (added >= CUMULATIVE_MAX_DURATION_REPAIRS) break
            val i = peakTasks[(first + offset) % peakTasks.size]
            ls.durationRepairCursor = if (i + 1 == n) 0 else i + 1
            added += ConditionalProductRepair(
                state, sink, durationVars[i], CUMULATIVE_MAX_DURATION_REPAIRS - added,
            ).propose()
        }
    }

    private fun collectPeakTasks(state: LocalSearchState, absT: Long): IntArray {
        val out = IntArrayList()
        for (i in 0 until n) {
            val r = curRes(state, i)
            val d = curDur(state, i)
            if (r <= 0 || d <= 0) continue
            if (!OptionalPresence.isPresentInAssignment(presents, i, state)) continue
            val cur = state.assignment.intValue(starts[i])
            if (cur <= absT && absT < cur + d) out.add(i)
        }
        return out.toIntArray()
    }

    private fun emitFeasibleSwaps(state: LocalSearchState, ls: CumulativeLsState, peakTasks: IntArray, sink: MoveSink) {
        var swapsAdded = 0
        for (i in peakTasks) {
            if (swapsAdded >= CUMULATIVE_MAX_SWAPS) break
            val iV = starts[i]
            if (!sink.allowsInt(iV)) continue
            val iCur = state.assignment.intValue(iV)
            val iDom = state.rootDomains[iV]
            for (j in 0 until n) {
                if (swapsAdded >= CUMULATIVE_MAX_SWAPS) break
                if (j == i) continue
                val dj0 = curDur(state, j)
                val rj0 = curRes(state, j)
                if (dj0 <= 0 || rj0 <= 0) continue
                if (!OptionalPresence.isPresentInAssignment(presents, j, state)) continue
                val jV = starts[j]
                if (!sink.allowsInt(jV)) continue
                val jCur = state.assignment.intValue(jV)
                if (jCur !in iDom || iCur !in state.rootDomains[jV]) continue
                if (jCur == iCur) continue
                if (simulateStartSwap(state, ls, i, j) >= 0L) continue
                sink.addChannelingCompound(
                    state,
                    listOf(
                        IntSet(iV, jCur),
                        IntSet(jV, iCur),
                    ),
                )
                swapsAdded++
            }
        }
    }

    private fun simulateStartSwap(state: LocalSearchState, ls: CumulativeLsState, i: Int, j: Int): Long {
        val before = ls.overage
        applyStartSwap(state, ls, i, j, reverse = false)
        return try {
            ls.overage - before
        } finally {
            applyStartSwap(state, ls, i, j, reverse = true)
        }
    }

    private fun applyStartSwap(state: LocalSearchState, ls: CumulativeLsState, i: Int, j: Int, reverse: Boolean) {
        val iV = starts[i]
        val jV = starts[j]
        val iCur = state.assignment.intValue(iV)
        val jCur = state.assignment.intValue(jV)
        fun swapped(variable: Int, value: Long): Long = when (variable) {
            iV -> jCur
            jV -> iCur
            else -> value
        }
        fun applyTask(task: Int) {
            if (!OptionalPresence.isPresentInAssignment(presents, task, state)) return
            val start = state.assignment.intValue(starts[task])
            val duration = curDur(state, task)
            val resource = curRes(state, task)
            val swappedStart = swapped(starts[task], start)
            val swappedDuration = if (durationVars.isEmpty()) duration else swapped(durationVars[task], duration)
            val swappedResource = if (resourceVars.isEmpty()) resource else swapped(resourceVars[task], resource)
            val oldStart = if (reverse) swappedStart else start
            val newStart = if (reverse) start else swappedStart
            if (duration == swappedDuration && resource == swappedResource) {
                if (duration > 0L && resource > 0L) {
                    applyCumulativeStartDelta(ls, oldStart, newStart, duration, resource)
                }
            } else {
                val oldDuration = if (reverse) swappedDuration else duration
                val newDuration = if (reverse) duration else swappedDuration
                val oldResource = if (reverse) swappedResource else resource
                val newResource = if (reverse) resource else swappedResource
                if (oldResource > 0L) applyCumulativeResDelta(ls, oldStart, oldDuration, oldResource, 0L)
                if (newResource > 0L) applyCumulativeResDelta(ls, newStart, newDuration, 0L, newResource)
            }
        }
        // Grade every affected footprint against the combined load without copying the timeline.
        val iAlias = aliasIndex[iV]
        if (iAlias < 0) applyTask(i) else for (task in aliasPositions[iAlias]) applyTask(task)
        fun applyPartner(task: Int) {
            if (starts[task] == iV || durationVars.getOrNull(task) == iV || resourceVars.getOrNull(task) == iV) return
            applyTask(task)
        }
        val jAlias = aliasIndex[jV]
        if (jAlias < 0) {
            applyPartner(j)
        } else {
            for (task in aliasPositions[jAlias]) applyPartner(task)
        }
        if (capacityVar == iV || capacityVar == jV) {
            val cap = curCap(state)
            applyCumulativeCapacityDelta(ls, if (reverse) cap else swapped(capacityVar, cap))
        }
    }

    private fun emitConditionalChoiceSwaps(
        state: LocalSearchState,
        ls: CumulativeLsState,
        peakTasks: IntArray,
        sink: MoveSink,
    ) {
        if (durationVars.isEmpty()) return
        val net = state.invariants ?: return
        var added = 0
        repeat(CUMULATIVE_MAX_SWAPS * CUMULATIVE_SWAP_ATTEMPT_STRIDE) {
            if (added >= CUMULATIVE_MAX_SWAPS) return
            val i = peakTasks.firstOrNull { it >= ls.choiceRepairCursor } ?: peakTasks[0]
            val j = ls.choicePartnerCursor
            ls.choicePartnerCursor = if (j + 1 == n) 0 else j + 1
            if (ls.choicePartnerCursor == 0) ls.choiceRepairCursor = if (i + 1 == n) 0 else i + 1
            if (i == j || curDur(state, j) != 0L || curRes(state, j) <= 0L ||
                !OptionalPresence.isPresentInAssignment(presents, j, state)
            ) return@repeat
            val iChannel = conditionalDurationChannel(state, i, sink) ?: return@repeat
            val jChannel = conditionalDurationChannel(state, j, sink) ?: return@repeat
            if (Lit.isPositive(iChannel.literal) != Lit.isPositive(jChannel.literal)) return@repeat
            val iChoice = net.equalityPredicate(Lit.variable(iChannel.literal)) ?: return@repeat
            val jChoice = net.equalityPredicate(Lit.variable(jChannel.literal)) ?: return@repeat
            if (iChoice.value != jChoice.value || iChoice.input == jChoice.input ||
                !sink.allowsInt(iChoice.input) || !sink.allowsInt(jChoice.input)
            ) return@repeat
            val iValue = state.assignment.intValue(iChoice.input)
            val jValue = state.assignment.intValue(jChoice.input)
            if (iValue == jValue || jValue !in state.rootDomains[iChoice.input] ||
                iValue !in state.rootDomains[jChoice.input]
            ) return@repeat
            val coordinated = ChannelingSink(iChoice.input, jValue)
            coordinated.pin(jChoice.input)
            coordinated.add(IntSet(jChoice.input, iValue))
            for ((variable, value) in listOf(iChoice.input to jValue, jChoice.input to iValue)) {
                val channeling = state.synthesizeChannelingMove(variable, value)
                if (channeling is Move.Compound) {
                    for (part in channeling.parts) {
                        if (part is Move.BoolFlip) coordinated.addIndicatorFlip(part.varId)
                    }
                }
            }
            coordinated.carryBinaryChannels(state, sink)
            val move = coordinated.toMove() as Move.Compound
            val before = sink.size
            sink.addChannelingCompound(state, move.parts)
            if (sink.size > before) added++
        }
    }

    private fun conditionalDurationChannel(state: LocalSearchState, task: Int, sink: MoveSink): LiteralIntDefinition? {
        val output = durationVars[task]
        if (!sink.allowsDefinedInt(output) || 0L !in state.rootDomains[output]) return null
        val net = state.invariants ?: return null
        val candidates = when (val definition = net.intDefinition(output)) {
            is FunctionalObjective.Times -> {
                val a = definition.a.varId
                val b = definition.b.varId
                if (a < 0 || b < 0 || a == b) return null
                listOf(a to state.assignment.intValue(b), b to state.assignment.intValue(a))
            }
            is FunctionalObjective.Lin -> listOf(positiveScaledBinaryDefinition(state, output) ?: return null)
            else -> return null
        }
        for ((flag, duration) in candidates) {
            if (duration <= 0L || duration !in state.rootDomains[output]) continue
            val domain = state.rootDomains[flag]
            if (domain.min < 0L || domain.max > 1L || 0L !in domain || 1L !in domain ||
                !sink.allowsDefinedInt(flag)
            ) continue
            val channel = net.literalChannel(flag) ?: continue
            if (state.assumptions.isFrozenBool(Lit.variable(channel.literal))) continue
            return channel
        }
        return null
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
            if (!OptionalPresence.isPresentInAssignment(
                    presents,
                    i,
                    state,
                ) || !OptionalPresence.isPresentInAssignment(presents, j, state)
            ) {
                continue
            }
            if (curDur(state, i) != curDur(state, j) || curRes(state, i) != curRes(state, j)) continue
            val si = state.assignment.intValue(starts[i])
            val sj = state.assignment.intValue(starts[j])
            if (si == sj) continue
            if (sj !in state.rootDomains[starts[i]] || si !in state.rootDomains[starts[j]]) continue
            sink.addChannelingCompound(state, listOf(IntSet(starts[i], sj), IntSet(starts[j], si)))
            emitted++
        }
    }

    override fun seedFeasible(state: LocalSearchState, factorId: Int): Boolean {
        if (starts.isEmpty()) return false
        val cap = curCap(state)
        val order = argsortBy(starts.size) { a, b ->
            state.rootDomains[starts[a]].min.compareTo(state.rootDomains[starts[b]].min)
        }
        var prevEnd = Long.MIN_VALUE
        for (oi in order.indices) {
            val i = order[oi]
            if (!OptionalPresence.isPresentInAssignment(presents, i, state)) continue
            if (curRes(state, i) > cap) return false
            val dur = curDur(state, i)
            val v = starts[i]
            if (state.assumptions.isFrozenInt(v)) {
                val s = state.assignment.intValue(v)
                if (s < prevEnd) return false
                prevEnd = s + dur
            } else {
                val d = state.rootDomains[v]
                val cand = max(d.min, prevEnd)
                val s = firstInDomainAtLeast(d, cand) ?: return false
                state.assignment.setInt(v, s)
                prevEnd = s + dur
            }
        }
        return true
    }

    private fun computeTLow(state: LocalSearchState): Long {
        var lo = Long.MAX_VALUE
        for (i in 0 until n) {
            lo = min(lo, min(state.rootDomains[starts[i]].min, state.assignment.intValue(starts[i])))
        }
        return if (lo == Long.MAX_VALUE) 0L else lo
    }

    private fun computeTHigh(state: LocalSearchState): Long {
        var hi = Long.MIN_VALUE
        for (i in 0 until n) {
            val dUb = if (durationVars.isEmpty()) {
                durations[i]
            } else {
                max(durations[i], state.rootDomains[durationVars[i]].max)
            }
            val cand = max(state.rootDomains[starts[i]].max, state.assignment.intValue(starts[i])) + dUb
            hi = max(hi, cand)
        }
        return if (hi == Long.MIN_VALUE) 0L else hi
    }
}

private const val CUMULATIVE_MAX_SWAPS: Int = 4
private const val CUMULATIVE_MAX_DURATION_REPAIRS: Int = 16
private const val CUMULATIVE_STRUCTURED_SWAP_CAP: Int = 4
private const val CUMULATIVE_SWAP_ATTEMPT_STRIDE: Int = 8
