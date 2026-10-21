package com.eignex.klause.presolve

import com.eignex.klause.solver.result.PresolveEffortStats
import kotlin.time.Duration

/** Capture preparation effort without resetting the shared phase allowance. */
fun PresolveConfig.effortStats(budget: PresolveBudget?, elapsed: Duration): PresolveEffortStats =
    PresolveEffortStats(
        emphasis = emphasis.id,
        abortFraction = abortFraction,
        maxRounds = maxRounds,
        probeBudgetPerVar = probeBudgetPerVar(),
        probeTotalBudget = probeTotalBudget(),
        elapsed = elapsed,
        work = budget?.spent(),
        allowance = budget?.allowance,
        rounds = budget?.rounds,
        passCalls = budget?.passCalls.orEmpty(),
        probeCalls = budget?.probeCalls,
        rootConstructionEntries = budget?.rootConstructionEntries,
        rootPropagationWork = budget?.rootPropagationWork,
        invocations = budget?.invocations.orEmpty(),
        entrySizeVisits = budget?.entrySizeVisits,
    )
