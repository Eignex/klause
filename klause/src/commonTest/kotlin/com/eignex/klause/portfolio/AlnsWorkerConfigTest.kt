package com.eignex.klause.portfolio

import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class AlnsWorkerConfigTest {

    @Test
    fun `inner search should initialize shared definitions before spending moves`() {
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(1, 3), IntDomain(1, 3), IntDomain(1, 9)),
            arrayOf<Factor>(Product(0, 1, 2)),
        ).bake()
        val sweep = assertNotNull(DefinitionalSweep.infer(problem))
        for (seed in listOf(0L, 1L)) {
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
    fun `diverse should cycle the curated regimes and wrap past the pool`() {
        val curated = AlnsProfile.Curated.map { it.label }
        assertEquals(
            curated,
            AlnsWorkerConfig.diverse(curated.size).map { it.profile.label },
        )
        assertEquals(
            curated + curated.take(2),
            AlnsWorkerConfig.diverse(curated.size + 2).map { it.profile.label },
        )
    }
}
