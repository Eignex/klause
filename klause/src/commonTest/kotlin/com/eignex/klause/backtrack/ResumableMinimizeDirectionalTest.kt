package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.backtrack.selector.VariableSelector
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.SearchBranchAttemptObserver
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.Cancellation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ResumableMinimizeDirectionalTest {
    @Test
    fun `finite search consumes directional child advice across cooperative slices`() {
        val blocks = 2
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
            while (slices++ < 128 && !search.isDone) {
                val outcomes = search.stats.lp.phases["DIRECTIONAL_BRANCH_NODE"]?.outcomes
                if ((outcomes?.get("DOWN") ?: 0L) >= 2L && (outcomes?.get("UP") ?: 0L) >= 2L &&
                    (outcomes?.get("SIDE_CONSUMED") ?: 0L) > 0L
                ) break
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
    fun `fallback selected variable consumes current basis child advice`() {
        val factors = Array<Factor>(3) { edge ->
            Linear(longArrayOf(1L, 1L), intArrayOf(edge, (edge + 1) % 3), LinearOp.GE, 1L)
        }
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 1) }, factors).bake()
        var fallbackPick: VarRef? = null
        val attempts = ArrayList<SearchDecision>()
        val selector = object : VariableSelector {
            override fun fresh() = this
            override fun pick(session: PropagationSession, rng: Random): VarRef? =
                InputOrder.pick(session, rng).also { if (fallbackPick == null) fallbackPick = it }
        }
        val params = BacktrackParams(
            variableSelector = selector,
            valueSelector = IndomainMin,
            randomSeed = 3L,
            maxDecisions = 1L,
            lpPlan = LpPlan(bounding = true, rootBudgetMillis = 0L, rootCutHarvest = false,
                boundMaxOpsPerNode = 0L),
            lpDirectionalGains = true,
            branchObserver = object : SearchBranchAttemptObserver {
                override fun onAttempt(decision: SearchDecision, context: SearchContext) {
                    attempts.add(decision)
                    context.requestCancellationPoll()
                }
            },
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(1L, 2L, 2L))

        ResumableMinimize(BacktrackSolver(problem), objective, params).use { search ->
            search.runSlice(Cancellation { attempts.isNotEmpty() }, 1000L, 100_000L) {}

            assertEquals(VarRef.IntVar(0), fallbackPick)
            assertEquals(1, attempts.size)
            val observations = search.stats.lp.phases.getValue("DIRECTIONAL_BRANCH_NODE").outcomes
            assertTrue((observations["BASIS_SIDE_CONSUMED"] ?: 0L) > 0L, observations.toString())
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
