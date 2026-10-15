package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LpRejectionPairingTest {
    @Test
    fun `the first coordinate rejection should distinguish strict endpoints from ordinary violations`() {
        for ((value, lowerStrict, expected) in listOf(
            Triple(0L, true, LpPointRejection.STRICT_COLUMN_ENDPOINT),
            Triple(-1L, true, LpPointRejection.COLUMN_BOUND),
            Triple(2L, false, LpPointRejection.COLUMN_BOUND),
        )) {
            val zero = ExactLpNumber.of(0L)
            val one = ExactLpNumber.of(1L)
            val source = ExactLpModel(
                listOf(emptyList()),
                emptyList(),
                listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero, lowerStrict), ExactLpSide(one)))),
                emptyList(),
                ExactLpObjective(listOf(zero)),
            )
            val model = assertNotNull(LpExactState(source).toWorkingModel())

            val result = verifyRationalCertificate(model, sourcePrimal = listOf(BigFraction.ofLong(value)))

            assertNull(result.witness)
            assertEquals(expected, result.pointRejection)
        }
    }

    @Test
    fun `logical side rejection should remain distinct from a coordinate bound`() {
        for ((strict, value, expected) in listOf(
            Triple(true, 1L, LpPointRejection.STRICT_LOGICAL_ENDPOINT),
            Triple(false, 2L, LpPointRejection.LOGICAL_BOUND),
        )) {
            val zero = ExactLpNumber.of(0L)
            val one = ExactLpNumber.of(1L)
            val source = ExactLpModel(
                listOf(listOf(ExactLpEntry(0, one))),
                listOf(one),
                listOf(ExactLpColumn(ExactLpBounds()), ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))),
                listOf(ExactLpRow(strict = strict)),
                ExactLpObjective(listOf(zero, zero)),
            )
            val model = assertNotNull(LpExactState(source).toWorkingModel())

            val result = verifyRationalCertificate(model, sourcePrimal = listOf(BigFraction.ofLong(value)))

            assertNull(result.witness)
            assertEquals(expected, result.pointRejection)
        }
    }

    @Test
    fun `wide verification should report the completed lane rejection`() {
        val huge = BigFraction.of(BIG_ONE shl 140, BIG_ONE)
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(huge), strict = true)))),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())

        val result = verifyRationalCertificate(model, sourcePrimal = listOf(huge))

        assertNull(result.witness)
        assertEquals(1, result.metrics.verificationRestarts)
        assertEquals(LpPointRejection.STRICT_COLUMN_ENDPOINT, result.pointRejection)
    }

    @Test
    fun `the strict fallback outcome should pair with the rejected point of the same operation`() {
        for ((limits, outcome, accepts) in listOf(
            Triple(LpRefinementLimits(), "STRICT_NUMERICAL_WITNESS", true),
            Triple(LpRefinementLimits(maxRounds = 0, maxAuxiliaries = 0), "NO_STRICT_ATTEMPT_DISABLED", false),
        )) {
            val state = strictState()
            val records = HashMap<String, Long>()
            LpScopedSolver(state).use { owner ->
                val result = certifyLpResult(
                    assertNotNull(state.toWorkingModel()),
                    targetSolver(),
                    null,
                    observer = observer(records),
                    refinement = LpRefinementRequest(owner, owner.refinementCache, limits),
                )

                assertEquals(accepts, result.witness != null)
                assertEquals(mapOf(key(outcome) to 1L), records.filterKeys { it.startsWith("VERIFY_CANDIDATE_FIRST_") })
            }
        }
    }

    private fun strictState(): LpExactState {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        return LpExactState(
            ExactLpModel(
                listOf(emptyList()),
                emptyList(),
                listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero, strict = true), ExactLpSide(one)))),
                emptyList(),
                ExactLpObjective(listOf(one)),
            ),
        )
    }

    private fun targetSolver(): LpSolver = object : LpSolver {
        override val infeasibleRay: DoubleArray? = null
        override fun solve(warm: Basis?): FloatLpResult? = null
        override fun solvePrimal(warm: Basis?): FloatLpResult? = null
        override fun continuationBasis(model: LpModel) = Basis(intArrayOf(), arrayOf(VarStatus.AT_LOWER))
    }

    private fun observer(records: MutableMap<String, Long>): LpCertificationObserver =
        object : LpCertificationObserver {
            override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) = Unit
            override fun observeExactInput(accepted: Boolean) = Unit
            override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) = Unit
            override fun observeContinuationRejection(key: String, count: Long) {
                records[key] = (records[key] ?: 0L) + count
            }
        }

    private fun key(outcome: String) = "VERIFY_CANDIDATE_FIRST_STRICT_COLUMN_ENDPOINT_$outcome"
}
