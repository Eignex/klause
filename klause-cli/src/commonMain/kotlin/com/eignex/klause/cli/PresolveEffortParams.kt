package com.eignex.klause.cli

import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.solver.result.PresolveEffortStats
import kotlin.time.Duration

internal fun presolveEffortParams(params: MutableList<String>, base: PresolveConfig): PresolveConfig {
    val keys = setOf("presolve-abort-fraction", "presolve-max-rounds", "presolve-probe-per-var", "presolve-probe-total")
    val values = HashMap<String, String>()
    val iterator = params.iterator()
    while (iterator.hasNext()) {
        val pair = iterator.next()
        val key = pair.substringBefore('=')
        if (key !in keys) continue
        if ('=' !in pair) usageError("presolve param `$key` expects key=value")
        values[key] = pair.substringAfter('=')
        iterator.remove()
    }
    if (values.isEmpty()) return base
    fun nonnegative(key: String, default: Int): Int = values[key]?.let { raw ->
        raw.toIntOrNull()?.takeIf { it >= 0 }
            ?: usageError("presolve param `$key` expects a nonnegative integer, got `$raw`")
    } ?: default
    val fraction = values["presolve-abort-fraction"]?.let { raw ->
        raw.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1.0 }
            ?: usageError("presolve param `presolve-abort-fraction` expects a fraction in [0,1], got `$raw`")
    } ?: base.abortFraction
    return base.withEffort(
        abortFraction = fraction,
        maxRounds = nonnegative("presolve-max-rounds", base.maxRounds),
        probeBudgetPerVar = nonnegative("presolve-probe-per-var", base.probeBudgetPerVar()),
        probeTotalBudget = nonnegative("presolve-probe-total", base.probeTotalBudget()),
    )
}

internal fun presolveEffortStats(
    config: PresolveConfig,
    budget: PresolveBudget?,
    elapsed: Duration,
): PresolveEffortStats =
    PresolveEffortStats(
        emphasis = config.emphasis.id,
        abortFraction = config.abortFraction,
        maxRounds = config.maxRounds,
        probeBudgetPerVar = config.probeBudgetPerVar(),
        probeTotalBudget = config.probeTotalBudget(),
        elapsed = elapsed,
        work = budget?.spent(),
        allowance = budget?.allowance,
        rounds = budget?.rounds,
        passCalls = budget?.passCalls.orEmpty(),
        probeCalls = budget?.probeCalls,
    )
