package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class IntegerUnitShapeCensusTest {
    @Test
    fun `unit shapes should distinguish raw and root cardinality limits`() {
        val row = requireNotNull(IntegerConflictRow.normalized(
            mapOf(0 to 1L, 1 to 1L, 2 to 1L, 3 to -1L), 0L, Lit.NONE))
        val variants = listOf(
            Triple(Array(4) { IntDomain(0, 1) }, 4, 1),
            Triple(arrayOf(IntDomain(0, 0), IntDomain(0, 0), IntDomain(0, 1), IntDomain(0, 1)), 2, 1),
            Triple(arrayOf(IntDomain(1, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1)), 3, 0),
        )
        for ((roots, width, limit) in variants) {
            val census = IntegerUnitShapeCensus()

            census.admit(row) { roots[it] }

            val snapshot = census.snapshot()
            assertEquals(1L, snapshot["learningIntegerCensusUnitRows"])
            assertEquals(1L, snapshot["learningIntegerCensusUnitLimit1"])
            assertEquals(1L, snapshot["learningIntegerCensusUnitAtMostOne"])
            assertEquals(0L, snapshot["learningIntegerCensusUnitSingleClause"])
            assertEquals(1L, snapshot["learningIntegerCensusUnitRootWidth" + width])
            assertEquals(1L, snapshot["learningIntegerCensusUnitRootLimit" + limit])
            assertEquals((4 - width).toLong(), snapshot["learningIntegerCensusUnitRootFixedTerms"])
            assertEquals(if (width == 2) 1L else 0L, snapshot["learningIntegerCensusUnitRootSingleClause"])
            assertEquals(if (limit == 0) 1L else 0L, snapshot["learningIntegerCensusUnitRootAllFalse"])
        }
    }

    @Test
    fun `unit shapes should withhold unavailable root classifications`() {
        val row = requireNotNull(IntegerConflictRow.normalized(mapOf(0 to 1L, 1 to -1L), 0L, Lit.NONE))
        val variants: List<((Int) -> IntDomain?)?> = listOf(
            null,
            { if (it == 0) IntDomain(0, 1) else null },
            { IntDomain(0, 2) },
        )
        for (roots in variants) {
            val census = IntegerUnitShapeCensus()

            census.admit(row, roots)

            val snapshot = census.snapshot()
            assertEquals(1L, snapshot["learningIntegerCensusUnitRows"])
            assertEquals(1L, snapshot["learningIntegerCensusUnitSingleClause"])
            assertEquals(1L, snapshot["learningIntegerCensusUnitRootUnavailable"])
            assertEquals(0L, snapshot["learningIntegerCensusUnitRootRows"])
        }
    }

    @Test
    fun `unit shapes should retain explicit overflow unavailability`() {
        val row = requireNotNull(IntegerConflictRow.normalized(
            mapOf(0 to 1L, 1 to -1L), Long.MAX_VALUE, Lit.NONE))
        val census = IntegerUnitShapeCensus()

        census.admit(row) { IntDomain(0, 1) }

        val snapshot = census.snapshot()
        assertEquals(1L, snapshot["learningIntegerCensusUnitRows"])
        assertEquals(1L, snapshot["learningIntegerCensusUnitLimitUnavailable"])
        assertEquals(1L, snapshot["learningIntegerCensusUnitRootUnavailable"])
        assertEquals(0L, snapshot["learningIntegerCensusUnitRootRows"])
        assertEquals(0L, snapshot["learningIntegerCensusUnitSaturated"])
    }

    @Test
    fun `native unit census should separate root facts from identical branch pins`() {
        for (root in listOf(false, true)) {
            val original = if (root) Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 0)
            else Linear(intArrayOf(1, 1, 1, 1), intArrayOf(0, 1, 2, 3), LinearOp.LE, 3)
            val problem = Problem(0, 4, Array(4) { IntDomain(0, 1) }, arrayOf(original))
            val session = PropagationSession(PropagationProblem(problem), pbLearning = false, attribution = true)
            session.enableIntegerRowLearning()
            if (!root) {
                assertIs<PropagationResult.Implied>(session.pinIntAtMost(0, 0))
                assertIs<PropagationResult.Implied>(session.pinIntAtMost(1, 0))
            }
            val row = requireNotNull(IntegerConflictRow.normalized(
                mapOf(0 to 1L, 1 to 1L, 2 to 1L, 3 to 1L), 1L, Lit.NONE))

            assertIs<PropagationResult.Implied>(session.addLearnedIntegerRow(row, 1))

            val snapshot = session.learnedLifecycle()
            assertEquals(IntDomain(0, 0), session.intDomain(0))
            assertEquals(IntDomain(0, 0), session.intDomain(1))
            assertEquals(1L, snapshot["learningIntegerCensusUnitRows"])
            assertEquals(1L, snapshot["learningIntegerCensusUnitLimit1"])
            assertEquals(if (root) 2L else 0L, snapshot["learningIntegerCensusUnitRootFixedTerms"])
            assertEquals(if (root) 1L else 0L, snapshot["learningIntegerCensusUnitRootSingleClause"])
            assertEquals(1L, snapshot["learningIntegerCensusUnitRootWidth" + if (root) 2 else 4])
        }
    }
}
