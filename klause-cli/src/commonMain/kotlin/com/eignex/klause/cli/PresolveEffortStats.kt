package com.eignex.klause.cli

import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.effortStats
import com.eignex.klause.solver.result.PresolveEffortStats
import kotlin.time.Duration

internal fun presolveEffortStats(
    config: PresolveConfig,
    budget: PresolveBudget?,
    elapsed: Duration,
): PresolveEffortStats =
    config.effortStats(budget, elapsed)
