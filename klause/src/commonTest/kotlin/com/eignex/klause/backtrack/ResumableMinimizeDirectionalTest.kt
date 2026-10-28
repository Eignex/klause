package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchContext
import kotlin.test.Test
import kotlin.test.assertTrue

class ResumableMinimizeDirectionalTest {
    @Test
    fun `finite search consumes both directional child gains before exhausting component rejected leaves`() {
        val factors = ArrayList<Factor>()
        repeat(3) { block ->
            repeat(3) { edge ->
                factors.add(Cardinality(intArrayOf(Lit.make(block * 3 + edge, true),
                    Lit.make(block * 3 + (edge + 1) % 3, true)), min = 1, max = 2))
            }
        }
        val problem = Problem(9, 0, emptyArray(), factors.toTypedArray()).bake()
        val params = BacktrackParams(
            variableSelector = InputOrder,
            valueSelector = IndomainMin,
            randomSeed = 3L,
            maxDecisions = 1000L,
            lpPlan = LpPlan(bounding = true, rootBudgetMillis = 0L, rootCutHarvest = false,
                boundMaxOpsPerNode = 0L),
            lpDirectionalGains = true,
            componentFactory = { listOf(object : SearchComponent {
                override fun check(context: SearchContext): ComponentCheck = ComponentCheck.Infeasible()
            }) },
        )

        val result = BacktrackSolver(problem).minimize(LinearObjective(boolWeights = LongArray(9) { 1L }), params)

        val observations = result.stats.lp.phases.getValue("DIRECTIONAL_BRANCH_NODE").outcomes
        assertTrue((observations["DOWN"] ?: 0L) >= 2L, observations.toString())
        assertTrue((observations["UP"] ?: 0L) >= 2L, observations.toString())
        assertTrue((observations["CONSUMED"] ?: 0L) > 0L, observations.toString())
    }
}
