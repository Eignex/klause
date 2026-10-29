package com.eignex.klause.cli

import com.eignex.klause.solver.result.PresolveEffortStats
import com.eignex.klause.solver.result.PresolveSizeStats
import com.eignex.klause.solver.result.PresolveStats
import com.eignex.klause.solver.result.SolveStats

internal fun presolveStatPairs(stats: SolveStats): List<Pair<String, String>> {
    val p = stats.presolve ?: return emptyList()
    if (p.passes.isEmpty() && p.constraintsRemoved == 0 && !p.infeasible && p.effort == null) return emptyList()
    val out = ArrayList<Pair<String, String>>()
    p.effort?.let { e ->
        out += "presolveEmphasis" to e.emphasis
        out += "presolveAbortFraction" to "${e.abortFraction}"
        out += "presolveMaxRounds" to "${e.maxRounds}"
        out += "presolveProbeBudgetPerVar" to "${e.probeBudgetPerVar}"
        out += "presolveProbeTotalBudget" to "${e.probeTotalBudget}"
        out += "presolvePreparationMs" to "${e.elapsed.inWholeMilliseconds}"
        e.work?.let { out += "presolveWork" to "$it" }
        e.allowance?.let { out += "presolveWorkAllowance" to "$it" }
        e.rounds?.let { out += "presolveRoundEntries" to "$it" }
        e.probeCalls?.let { out += "presolveProbeCalls" to "$it" }
        e.rootConstructionEntries?.let { out += "presolveRootConstructionEntries" to "$it" }
        e.rootPropagationWork?.let { out += "presolveRootPropagationWork" to "$it" }
        out += presolveLedgerStatPairs(e)
        for ((id, calls) in e.passCalls.entries.sortedBy { it.key }) out += "presolveCalls_$id" to "$calls"
    }
    if (p.passes.isNotEmpty()) out += "presolvePasses" to p.passes.joinToString(",")
    if (p.constraintsRemoved != 0) out += "presolveConstraintsRemoved" to "${p.constraintsRemoved}"
    if (p.infeasible) out += "presolveInfeasible" to "true"
    // The LP harvest's own contribution, broken out so it can be measured apart from the net counts above.
    p.lpHarvest?.let { lp ->
        if (lp.skipped) out += "lpHarvestSkipped" to "true"
        if (lp.rootInfeasible) out += "lpHarvestRootInfeasible" to "true"
        if (lp.boundsShaved != 0) out += "lpHarvestBoundsShaved" to "${lp.boundsShaved}"
        if (lp.objectiveLbRaised) out += "lpHarvestObjectiveLb" to "true"
        if (lp.constraintsRemoved != 0) out += "lpHarvestConstraintsRemoved" to "${lp.constraintsRemoved}"
        if (lp.equalitiesAdded != 0) out += "lpHarvestEqualitiesAdded" to "${lp.equalitiesAdded}"
        if (lp.relaxationNnz != 0) out += "lpHarvestRelaxationNnz" to "${lp.relaxationNnz}"
    }
    return out
}

private fun presolveLedgerStatPairs(effort: PresolveEffortStats): List<Pair<String, String>> = buildList {
    add("presolveInvocationWork" to "${effort.invocations.sumOf { it.work }}")
    add("presolveStageWork" to "${effort.preparationStages.sumOf { it.work }}")
    effort.unattributedWork?.let { add("presolveUnattributedWork" to "$it") }
    effort.entrySizeVisits?.let { add("presolveEntrySizeVisits" to "$it") }
    add("presolveInvocationNativeWork" to "${effort.invocations.sumOf { it.nativeWork }}")
    add("presolveStageNativeWork" to "${effort.preparationStages.sumOf { it.nativeWork }}")
    val invocations = effort.invocations.groupBy { "${it.lane}_${it.pass}_${it.outcome}" }.entries.sortedBy { it.key }
    for ((key, calls) in invocations) {
        val prefix = "presolveInvocation_${key}_"
        add(prefix + "Calls" to "${calls.size}")
        add(prefix + "Ns" to "${calls.sumOf { it.elapsed.inWholeNanoseconds }}")
        add(prefix + "Work" to "${calls.sumOf { it.work }}")
        add(prefix + "RebuildWork" to "${calls.sumOf { it.rebuildWork }}")
        add(prefix + "NativeWork" to "${calls.sumOf { it.nativeWork }}")
        add(prefix + "NarrowedColumns" to "${calls.sumOf { it.narrowedColumns.toLong() }}")
        add(prefix + "FixedColumns" to "${calls.sumOf { it.fixedColumns.toLong() }}")
        for (key in calls.flatMap { it.structuralImpact.keys }.distinct().sorted()) {
            val values = calls.mapNotNull { it.structuralImpact[key] }
            val value = if (key.endsWith("MaxSupport") || key.endsWith("MaxDepth")) values.maxOrNull() ?: 0L
            else values.sum()
            add(prefix + "Impact" + key to "$value")
        }
        addAll(presolveSizeStatPairs(prefix + "Input", calls.map { it.input }))
        addAll(presolveSizeStatPairs(prefix + "Output", calls.map { it.output }))
    }
    val stages = effort.preparationStages.groupBy { "${it.lane}_${it.stage}_${it.outcome}" }.entries.sortedBy { it.key }
    for ((key, calls) in stages) {
        val prefix = "presolveStage_${key}_"
        add(prefix + "Calls" to "${calls.size}")
        add(prefix + "Ns" to "${calls.sumOf { it.elapsed.inWholeNanoseconds }}")
        add(prefix + "Work" to "${calls.sumOf { it.work }}")
        add(prefix + "NativeWork" to "${calls.sumOf { it.nativeWork }}")
        addAll(presolveSizeStatPairs(prefix + "Input", calls.map { it.input }))
        addAll(presolveSizeStatPairs(prefix + "Output", calls.map { it.output }))
    }
}

private fun presolveSizeStatPairs(
    prefix: String,
    sizes: List<PresolveSizeStats>,
): List<Pair<String, String>> = buildList {
    add(prefix + "Factors" to "${sizes.sumOf { it.factors.toLong() }}")
    add(prefix + "Booleans" to "${sizes.sumOf { it.booleans.toLong() }}")
    add(prefix + "Integers" to "${sizes.sumOf { it.integers.toLong() }}")
    add(prefix + "Reals" to "${sizes.sumOf { it.reals.toLong() }}")
    val observed = sizes.mapNotNull { it.occurrences }
    add(prefix + "OccurrenceCalls" to "${observed.size}")
    if (observed.isNotEmpty()) add(prefix + "Occurrences" to "${observed.sum()}")
}

internal fun printPresolveDiagnostics(stats: PresolveStats?) {
    for ((key, value) in presolveStatPairs(SolveStats(presolve = stats))) errPrintln("  $key=$value")
}
