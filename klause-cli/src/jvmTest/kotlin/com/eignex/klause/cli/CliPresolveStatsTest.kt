package com.eignex.klause.cli

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.PresolvePipeline
import com.eignex.klause.presolve.effortStats
import com.eignex.klause.solver.result.PresolveStats
import com.eignex.klause.solver.result.SolveStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration

class CliPresolveStatsTest {
    @Test
    fun `summary keys should stay bounded and ordered when the ledger repeats`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)),
            listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 7)))
        val budget = PresolveBudget(Long.MAX_VALUE)
        val config = PresolveConfig.parse("off,+strengthen,+bve")
        PresolvePipeline.run(problem, null, config, false, presolveBudget = budget)
        val effort = config.effortStats(budget, Duration.ZERO)
        val observedWork = effort.invocations.sumOf { it.work } + effort.preparationStages.sumOf { it.work }
        val repeated = effort.copy(
            invocations = List(100) { effort.invocations }.flatten(),
            preparationStages = List(100) { effort.preparationStages }.flatten(),
            work = requireNotNull(effort.work) + 99 * observedWork,
        )

        val once = presolveStatPairs(SolveStats(presolve = PresolveStats(effort = effort))).toMap()
        val many = presolveStatPairs(SolveStats(presolve = PresolveStats(effort = repeated))).toMap()
        val reversed = presolveStatPairs(SolveStats(presolve = PresolveStats(
            effort = repeated.copy(
                invocations = repeated.invocations.reversed(),
                preparationStages = repeated.preparationStages.reversed(),
            ),
        )))

        assertEquals(once.keys, many.keys)
        assertEquals(many.toList(), reversed)
        assertEquals("100", many["presolveInvocation_SOURCE_bve_ELIGIBILITY_SKIP_Calls"])
        assertEquals("0", many["presolveInvocation_SOURCE_bve_ELIGIBILITY_SKIP_InputOccurrenceCalls"])
        assertEquals(null, many["presolveInvocation_SOURCE_bve_ELIGIBILITY_SKIP_InputOccurrences"])
        assertEquals(100 * once.getValue("presolveInvocationWork").toLong(),
            many.getValue("presolveInvocationWork").toLong())
    }
}
