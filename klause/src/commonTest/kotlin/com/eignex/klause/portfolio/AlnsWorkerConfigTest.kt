package com.eignex.klause.portfolio

import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The hybrid-ALNS engine (#644): a diverse pool of ALNS arms cycling the curated regimes, mirroring the
 * LS/backtrack catalogs, with [EngineMix.ALNS] composing one arm per requested slot.
 */
class AlnsWorkerConfigTest {

    @Test
    fun `inner search initializes shared definitions before spending moves`() {
        val problem = Problem(
            0, 3, arrayOf(IntDomain(1, 3), IntDomain(1, 3), IntDomain(1, 9)),
            arrayOf<Factor>(Product(0, 1, 2)),
        ).bake()
        val sweep = assertNotNull(DefinitionalSweep.infer(problem))
        for (seed in listOf(0L, 1L, 2L, 3L)) {
            val worker = AlnsWorkerConfig().materialize(
                problem,
                index = 0,
                armId = 0,
                seed = seed,
                lsLambda = 1.0,
                objective = null,
                lsObjective = null,
                definitionalSweep = sweep,
                onEvent = null,
                pools = null,
            )
            try {
                val result = assertIs<SolveResult.Sat>(worker.solve(Cancellation.Never, maxInstructions = 1))

                assertEquals(
                    result.assignment.ints[0] * result.assignment.ints[1],
                    result.assignment.ints[2],
                )
                assertEquals(0.0, result.stats.ls.moves.sum)
            } finally {
                worker.close()
            }
        }
    }

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
