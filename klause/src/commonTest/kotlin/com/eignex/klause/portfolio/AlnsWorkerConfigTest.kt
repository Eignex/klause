package com.eignex.klause.portfolio

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The hybrid-ALNS engine (#644): a diverse pool of ALNS arms cycling the curated regimes, mirroring the
 * LS/backtrack catalogs, with [EngineMix.ALNS] composing one arm per requested slot.
 */
class AlnsWorkerConfigTest {

    @Test
    fun `diverse cycles the curated regimes and wraps past the pool`() {
        val curated = AlnsProfile.Curated.map { it.label }
        assertEquals(
            curated,
            AlnsWorkerConfig.diverse(curated.size).map { it.profile.label },
            "one arm per curated regime",
        )
        assertEquals(
            curated + curated.take(2),
            AlnsWorkerConfig.diverse(curated.size + 2).map { it.profile.label },
            "past the pool size, regimes repeat in order",
        )
    }

    @Test
    fun `the ALNS engine composes one hybrid-ALNS arm per requested slot`() {
        val arms = PortfolioComposition.compose(
            PortfolioScenario.parallel(cores = 4, kind = Kind.COP, engine = EngineMix.ALNS, arms = 4),
        )
        assertEquals(4, arms.size)
        assertTrue(arms.all { it is AlnsWorkerConfig }, "every ALNS-engine arm is an AlnsWorkerConfig")
        assertEquals(
            AlnsProfile.Curated.map { "alns-${it.label}" },
            arms.map { it.label },
            "the arms cycle the curated regimes",
        )
    }

    @Test
    fun `the standalone arm uses the default regime`() {
        assertEquals("alns-${AlnsProfile.Default.label}", AlnsWorkerConfig().label)
    }

    @Test
    fun `a materialized ALNS arm accepts the counted instruction budget`() {
        val factor = Cardinality.exactlyOne(
            intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)),
        )
        val problem = Problem(4, 0, emptyArray(), listOf<Factor>(factor))
        val worker = AlnsWorkerConfig().materialize(
            problem.bake(),
            index = 0,
            armId = 0,
            seed = 0L,
            lsLambda = 1.0,
            objective = LinearObjective(boolWeights = longArrayOf(10L, 5L, 8L, 3L)),
            lsObjective = null,
            definitionalSweep = null,
            onEvent = null,
            pools = null,
        )
        assertTrue(worker.acceptsInstructionBudget, "ALNS arms must schedule like counted LS segments")
        worker.close()
    }
}
