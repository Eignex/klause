package com.eignex.klause.cli

import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.solver.result.PresolveEffortStats
import kotlin.time.Duration

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
        rootConstructionEntries = budget?.rootConstructionEntries,
        rootPropagationWork = budget?.rootPropagationWork,
    )
