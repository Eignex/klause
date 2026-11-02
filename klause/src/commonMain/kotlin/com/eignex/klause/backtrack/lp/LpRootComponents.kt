package com.eignex.klause.backtrack.lp

import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.RootComponentOrder
import com.eignex.klause.lp.bounding.RootPrimalTargets
import com.eignex.klause.lp.engine.LpProjectionMeter
import com.eignex.klause.lp.engine.LpProjectionStop
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntDisjointSet
import com.eignex.klause.util.argsortBy
import kotlin.math.floor
import kotlin.time.TimeSource

internal fun LpEngine.prepareRootComponentOrder(
    session: PropagationSession,
    cancellation: Cancellation,
): RootComponentOrder? {
    val targets = rootPrimalTargets ?: return null
    if (problem.numBoolVars != 0 || problem.numRealVars != 0 || params.lpPlan.lbTreeSearch) return null
    if (!targets.componentOrderAttempted) {
        val bytes = rootComponentPreparationBytes(problem.numIntVars)
        val minimumWork = 8L * problem.numIntVars + 2L
        val allowed = rootComponentPreparationAllowed(targets, bytes, minimumWork)
        val work = if (allowed) rootComponentPreparationWork(targets, bytes) else 0L
        val started = TimeSource.Monotonic.markNow()
        var outcome = if (allowed) "PENDING" else "ALLOWANCE_DECLINED"
        var preparationWork = 0L
        targets.componentOrderAttempted = !allowed
        if (work >= minimumWork && !cancellation()) {
            targets.componentOrderAttempted = true
            val meter = LpProjectionMeter(work, bytes, cancellation)
            try {
                val prepared = rootComponentOrder(problem, meter, session, targets)
                if (rootPrimalTargets === targets && !cancellation()) {
                    targets.components = prepared
                    outcome = if (prepared == null) "CONNECTED_DECLINED" else "RETAINED"
                } else outcome = "EXPIRED_OR_CANCELLED"
            } catch (_: LpProjectionStop) {
                targets.componentOrderAttempted = meter.work > 0L
                outcome = if (meter.work == 0L) "PENDING" else "PREPARATION_STOPPED"
            } finally {
                preparationWork = meter.work
                noteNodeOverhead(preparationWork)
            }
        }
        sink.lp.observePrimalPhase("PRIMAL_COMPONENT_ORDER", outcome,
            started.elapsedNow().inWholeNanoseconds, preparationWork)
    }
    if (rootPrimalTargets !== targets || cancellation()) return null
    return targets.components
}

internal fun LpEngine.pickRootComponent(session: PropagationSession, cancellation: Cancellation): VarRef? {
    val targets = rootPrimalTargets ?: return null
    val order = prepareRootComponentOrder(session, cancellation) ?: return null
    val variable = firstRootComponentVariable(order, session, cancellation) ?: return null
    if (rootPrimalTargets !== targets || cancellation()) return null
    if (!order.offered) {
        order.offered = true
        sink.lp.observePrimalPhase("PRIMAL_COMPONENT_ORDER", "COMPONENT_PICK_OFFERED", 0L)
    }
    return VarRef.IntVar(variable)
}

internal fun rootComponentPreparationBytes(variables: Int): Long =
    512L + (6L * variables + 2L) * Int.SIZE_BYTES

internal fun rootComponentOrder(
    problem: Problem,
    meter: LpProjectionMeter,
    session: PropagationSession? = null,
    targets: RootPrimalTargets? = null,
): RootComponentOrder? {
    val n = problem.numIntVars
    if (session != null && session.problem !== problem) return null
    if (problem.numBoolVars != 0 || problem.numRealVars != 0 || n < 4 || n == Int.MAX_VALUE) return null
    meter.reserve(8L * n + 2L, rootComponentPreparationBytes(n), matrix = false)
    val components = IntDisjointSet(n)
    for (factor in problem.factors) {
        val variables = factor.intVars
        meter.reserve(8L * variables.size + 1L, 0L, matrix = true)
        var first = -1
        for (index in variables.indices) {
            if (index % 256 == 0) meter.poll()
            val variable = variables[index]
            if (variable !in 0 until n) return null
            if (session != null && !session.needsIntDecision(variable)) continue
            if (first < 0) first = variable else components.union(first, variable)
        }
    }
    val byRoot = IntArray(n) { -1 }
    val sizes = IntArray(n)
    var groups = 0
    for (variable in 0 until n) {
        if (variable % 256 == 0) meter.poll()
        val component = components.find(variable)
        var index = byRoot[component]
        if (index < 0) {
            index = groups++
            byRoot[component] = index
        }
        sizes[index]++
    }
    var coupled = 0
    for (index in 0 until groups) if (sizes[index] > 1) coupled++
    if (coupled < 2) return null
    val offsets = IntArray(groups + 1)
    for (index in 0 until groups) offsets[index + 1] = offsets[index] + sizes[index]
    val writes = offsets.copyOf()
    val members = IntArray(n)
    for (variable in 0 until n) {
        if (variable % 256 == 0) meter.poll()
        val group = byRoot[components.find(variable)]
        members[writes[group]++] = variable
    }
    val prepared = RootComponentOrder(offsets, members, groups)
    val ordered = if (targets == null) prepared else prepared.orderTargets(byRoot, sizes, writes, targets, meter)
    meter.poll()
    return ordered
}

private fun RootComponentOrder.orderTargets(
    byRoot: IntArray,
    scratch: IntArray,
    groupRanks: IntArray,
    targets: RootPrimalTargets,
    meter: LpProjectionMeter,
): RootComponentOrder {
    val n = members.size
    var levels = 0L
    var width = 1L
    while (width < n) {
        levels++
        width *= 2L
    }
    meter.reserve((2L * levels + 2L) * n, 0L, matrix = false)
    val guidedGroups = rankTargetGroups(groupRanks, targets, meter)
    for (group in 0 until groups) {
        for (index in offsets[group] until offsets[group + 1]) {
            if (index % 256 == 0) meter.poll()
            byRoot[members[index]] = groupRanks[group]
        }
    }
    // Reusing graph scratch keeps sorting within the admitted primitive storage.
    val ordered = argsortBy(n, members, scratch) { a, b ->
        meter.reserve(8L, 0L, matrix = false)
        val groupOrder = byRoot[a].compareTo(byRoot[b])
        if (groupOrder != 0) groupOrder else targets.fractionality(b).compareTo(targets.fractionality(a))
    }
    if (guidedGroups > 0 && guidedGroups < groups) {
        meter.reserve(n.toLong() + 2L * groups + 1L, 0L, matrix = false)
        offsets.fill(0)
        for (index in ordered.indices) {
            if (index % 256 == 0) meter.poll()
            offsets[byRoot[ordered[index]] + 1]++
        }
        for (group in 0 until groups) {
            if (group % 256 == 0) meter.poll()
            offsets[group + 1] += offsets[group]
        }
    }
    return if (ordered === members) this else RootComponentOrder(offsets, ordered, groups)
}

private fun RootComponentOrder.rankTargetGroups(
    groupRanks: IntArray,
    targets: RootPrimalTargets,
    meter: LpProjectionMeter,
): Int {
    var guidedGroups = 0
    for (group in 0 until groups) {
        groupRanks[group] = 1
        for (index in offsets[group] until offsets[group + 1]) {
            if (index % 256 == 0) meter.poll()
            meter.reserve(8L, 0L, matrix = false)
            if (targets.fractionality(members[index]) >= 0.0) {
                groupRanks[group] = 0
                guidedGroups++
                break
            }
        }
    }
    meter.reserve(2L * groups, 0L, matrix = false)
    var guidedRank = 0
    var ordinaryRank = guidedGroups
    for (group in 0 until groups) {
        groupRanks[group] = if (groupRanks[group] == 0) guidedRank++ else ordinaryRank++
    }
    return guidedGroups
}

private fun RootPrimalTargets.fractionality(variable: Int): Double {
    val column = intColumns.getOrNull(variable) ?: return -1.0
    if (knownColumns != null && knownColumns.getOrNull(column) != true) return -1.0
    val coordinate = coordinates.getOrNull(column)?.takeIf { it.isFinite() } ?: return -1.0
    val fraction = coordinate - floor(coordinate)
    return minOf(fraction, 1.0 - fraction)
}

internal fun firstRootComponentVariable(
    order: RootComponentOrder,
    session: PropagationSession,
    cancellation: Cancellation,
): Int? {
    var visited = 0
    for (group in 0 until order.groups) {
        for (index in order.offsets[group] until order.offsets[group + 1]) {
            if (visited++ % 256 == 0 && cancellation()) return null
            session.chargeInprocessingWork(1L)
            val variable = order.members[index]
            if (session.needsIntDecision(variable)) return variable
        }
    }
    return null
}
