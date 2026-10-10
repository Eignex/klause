package com.eignex.klause.portfolio

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.scoring.MoveScoring
import com.eignex.klause.localsearch.strategy.FeasibleDescent
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.EngineParams
import com.eignex.klause.solver.pipeline.resolveLocalSearchRecipes
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Coverage for the LS worker-config catalog (#699 schedule-diversity arms): the new schedule-based
 * SA arms build by label and are in the full pool, and the credit-ranked pool covers every [LocalSearchArm]
 * (so adding an arm to the enum without adding it to `ranked` fails here).
 */
class LocalSearchWorkerConfigTest {

    @Test
    fun `upper bound initialization survives strategy edits`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 1_000_003)), emptyList()).bake()
        val recipe = resolveLocalSearchRecipes(
            EngineParams(listOf("arm=cbls/fixed", "initial-values=max")),
        ).pool!!.single()()
        for (configured in listOf(recipe, recipe.withScoring(MoveScoring.Raw))) {
            val incumbents = ArrayList<Double>()
            val worker = LocalSearchWorkerConfig(configured).materialize(
                problem, index = 0, armId = 0, seed = 1L, lsLambda = 1.0,
                objective = LinearObjective(intCoefficients = longArrayOf(1L)),
                lsObjective = null, definitionalSweep = null,
                onEvent = { _, event -> if (event is SearchEvent.Incumbent) incumbents.add(event.objective) },
                pools = null,
            )

            worker.use { it.improvements({ Double.POSITIVE_INFINITY }, Cancellation.Never, maxInstructions = 2L).last() }

            assertEquals(1_000_003.0, incumbents.first())
        }
    }

    @Test
    fun `a warm start replaces the upper bound initialization`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 1_000_003)), emptyList()).bake()
        val recipe = resolveLocalSearchRecipes(
            EngineParams(listOf("arm=cbls/fixed", "initial-values=max")),
        ).pool!!.single()()
        val incumbents = ArrayList<Double>()
        val worker = LocalSearchWorkerConfig(recipe).materialize(
            problem, index = 0, armId = 0, seed = 1L, lsLambda = 1.0,
            objective = LinearObjective(intCoefficients = longArrayOf(1L)),
            lsObjective = null, definitionalSweep = null,
            onEvent = { _, event -> if (event is SearchEvent.Incumbent) incumbents.add(event.objective) },
            pools = null,
        )

        worker.use {
            it.improvements(
                { Double.POSITIVE_INFINITY }, Cancellation.Never,
                warmStart = Sample(booleanArrayOf(), longArrayOf(123L)), maxInstructions = 2L,
            ).last()
        }

        assertEquals(123.0, incumbents.first())
    }

    @Test
    fun `each arm family declares an explicit feasible-descent mode`() {
        // On a COP `materialize` optimizes every arm per its declared FeasibleDescent — no arm falls into
        // a default descent. Pin the mode each family declares so a regression that silently changes how
        // an arm optimizes (e.g. an SA arm reverting to a plain finder) fails here.
        val byLabel = LocalSearchCatalog.ranked(Kind.COP).associateBy { it.label }
        // CBLS and the SA family both self-own their feasible walk (CBLS descends greedily on its
        // sources, SA anneals); nothing relies on an engine-side descent.
        for (label in listOf("cbls/fixed", "sa/fixed", "sa-reheat/fixed", "sa-phased/fixed")) {
            assertEquals(FeasibleDescent.SelfOwned, byLabel.getValue(label).feasibleDescent, "'$label'")
        }
        // Violation-native finders: ratcheted as a constraint on a COP, pure finders on a CSP.
        assertEquals(FeasibleDescent.RatchetAsConstraint, byLabel.getValue("adaptive-probsat/fixed").feasibleDescent)
        assertEquals(FeasibleDescent.RatchetAsConstraint, byLabel.getValue("walksat-cc/luby").feasibleDescent)
    }
}
