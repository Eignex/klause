package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ResumableMinimizeDirectionalTest {
    @Test
    fun `finite search consumes directional child advice across cooperative slices`() {
        val blocks = 3
        val variables = blocks * 3
        val factors = ArrayList<Factor>()
        repeat(blocks) { block ->
            repeat(3) { edge ->
                factors.add(Linear(longArrayOf(1L, 1L),
                    intArrayOf(block * 3 + edge, block * 3 + (edge + 1) % 3), LinearOp.GE, 1L))
            }
        }
        val problem = Problem(0, variables, Array(variables) { IntDomain(0, 1) }, factors.toTypedArray()).bake()
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

        val objective = LinearObjective(intCoefficients = LongArray(variables) { if (it % 3 == 0) 1L else 2L })
        ResumableMinimize(BacktrackSolver(problem), objective, params).use { search ->
            var slices = 0
            while (slices++ < 128 && !search.isDone &&
                (search.stats.lp.phases["DIRECTIONAL_BRANCH_NODE"]?.outcomes?.get("SIDE_CONSUMED") ?: 0L) == 0L
            ) {
                search.runSlice(Cancellation.Never, 1000L, 32L) {}
            }

            val observations = search.stats.lp.phases.getValue("DIRECTIONAL_BRANCH_NODE").outcomes
            val diagnostic = "$observations; solves=${search.stats.lp.solves.sum}"
            assertTrue((observations["DOWN"] ?: 0L) >= 2L, diagnostic)
            assertTrue((observations["UP"] ?: 0L) >= 2L, diagnostic)
            assertTrue((observations["SIDE_CONSUMED"] ?: 0L) > 0L, diagnostic)
        }
    }

    @Test
    fun `assumption reseeding clears the finite lp history scope`() {
        val problem = Problem(2, 0, emptyArray(), arrayOf<Factor>(
            Cardinality(intArrayOf(Lit.make(0, true), Lit.make(1, true)), min = 1, max = 2),
        )).bake()
        val params = BacktrackParams(randomSeed = 3L, lpDirectionalGains = true,
            lpPlan = LpPlan(bounding = true, rootBudgetMillis = 0L, rootCutHarvest = false))
        val objective = LinearObjective(boolWeights = longArrayOf(1L, 1L))
        ResumableMinimize(BacktrackSolver(problem), objective, params, rebindable = true).use { search ->
            assertNotNull(search.runSlice(Cancellation.Never, 1000L, 1000L) {})
            val before = search.stats.lp.phases.getValue("DIRECTIONAL_BRANCH_NODE").outcomes["RESET"] ?: 0L

            search.rebind(Assumptions.None.withBool(0, false), 1000L)

            val after = search.stats.lp.phases.getValue("DIRECTIONAL_BRANCH_NODE").outcomes.getValue("RESET")
            assertEquals(before + 1L, after)
        }
    }
}
