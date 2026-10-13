package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactContinuationMetrics
import com.eignex.klause.solver.result.SmtStatsSink
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class LpRejectionPairingTest {
    @Test
    fun `first coordinate rejection distinguishes strict endpoints from ordinary violations`() {
        for ((value, lowerStrict, expected) in listOf(
            Triple(0L, true, LpPointRejection.STRICT_COLUMN_ENDPOINT),
            Triple(-1L, true, LpPointRejection.COLUMN_BOUND),
            Triple(2L, false, LpPointRejection.COLUMN_BOUND),
        )) {
            val zero = ExactLpNumber.of(0L)
            val one = ExactLpNumber.of(1L)
            val source = ExactLpModel(
                listOf(emptyList()), emptyList(),
                listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero, lowerStrict), ExactLpSide(one)))),
                emptyList(), ExactLpObjective(listOf(zero)),
            )
            val model = assertNotNull(LpExactState(source).toWorkingModel())

            val result = verifyRationalCertificate(model, sourcePrimal = listOf(BigFraction.ofLong(value)))

            assertNull(result.witness)
            assertEquals(expected, result.pointRejection)
        }
    }

    @Test
    fun `logical side rejection remains distinct from a coordinate bound`() {
        for ((strict, value, expected) in listOf(
            Triple(true, 1L, LpPointRejection.STRICT_LOGICAL_ENDPOINT),
            Triple(false, 2L, LpPointRejection.LOGICAL_BOUND),
        )) {
            val zero = ExactLpNumber.of(0L)
            val one = ExactLpNumber.of(1L)
            val source = ExactLpModel(
                listOf(listOf(ExactLpEntry(0, one))), listOf(one),
                listOf(ExactLpColumn(ExactLpBounds()), ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))),
                listOf(ExactLpRow(strict = strict)), ExactLpObjective(listOf(zero, zero)),
            )
            val model = assertNotNull(LpExactState(source).toWorkingModel())

            val result = verifyRationalCertificate(model, sourcePrimal = listOf(BigFraction.ofLong(value)))

            assertNull(result.witness)
            assertEquals(expected, result.pointRejection)
        }
    }

    @Test
    fun `wide verification reports the completed lane rejection`() {
        val huge = BigFraction.of(BIG_ONE shl 140, BIG_ONE)
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(emptyList()), emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(huge), strict = true)))),
            emptyList(), ExactLpObjective(listOf(zero)),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())

        val result = verifyRationalCertificate(model, sourcePrimal = listOf(huge))

        assertNull(result.witness)
        assertEquals(1, result.metrics.verificationRestarts)
        assertEquals(LpPointRejection.STRICT_COLUMN_ENDPOINT, result.pointRejection)
    }

    @Test
    fun `strict fallback outcome is paired with the rejected point in that operation`() {
        for ((limits, outcome, accepts) in listOf(
            Triple(LpRefinementLimits(), "STRICT_NUMERICAL_WITNESS", true),
            Triple(LpRefinementLimits(maxRounds = 0, maxAuxiliaries = 0), "NO_STRICT_ATTEMPT_DISABLED", false),
        )) {
            val state = strictState()
            val records = HashMap<String, Long>()
            LpScopedSolver(state).use { owner ->
                val result = certifyLpResult(
                    assertNotNull(state.toWorkingModel()), targetSolver(), null,
                    observer = observer(records),
                    refinement = LpRefinementRequest(owner, owner.refinementCache, limits),
                )

                assertEquals(accepts, result.witness != null)
                assertEquals(mapOf(key(outcome) to 1L), records)
            }
        }
    }

    @Test
    fun `cached refinement metrics do not supply another strict attempt`() {
        val state = strictState()
        val records = HashMap<String, Long>()
        LpScopedSolver(state).use { owner ->
            val request = LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits())
            val model = assertNotNull(state.toWorkingModel())
            val observed = observer(records)
            assertNotNull(certifyLpResult(
                model, targetSolver(), null, observer = observed, refinement = request,
            ).witness)

            certifyLpResult(model, targetSolver(), null, observer = observed, refinement = request)

            assertEquals(1L, records[key("STRICT_NUMERICAL_WITNESS")])
            assertEquals(1L, records[key("NO_STRICT_ATTEMPT_REPEATED")])
        }
    }

    @Test
    fun `containing stop cannot be reported as a completed missing strict attempt`() {
        val state = strictState()
        val records = HashMap<String, Long>()
        var cancelled = false
        val stop = Cancellation { cancelled }
        LpScopedSolver(state).use { owner ->
            val result = certifyLpResult(
                assertNotNull(state.toWorkingModel()), targetSolver(), null, cancellation = stop,
                observer = observer(records) { cancelled = true },
                refinement = LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits()),
            )

            assertNull(result.witness)
            assertEquals(mapOf(key("STOP_OBSERVED") to 1L), records)
        }
    }

    @Test
    fun `an aborted observation preserves the numerical callback failure`() {
        val state = strictState()
        val records = HashMap<String, Long>()
        val failure = IllegalStateException("numerical observation")
        val observed = observer(records) { throw failure }
        var caught: Throwable? = null

        try {
            certifyLpResult(assertNotNull(state.toWorkingModel()), targetSolver(), null, observer = observed)
        } catch (problem: IllegalStateException) {
            caught = problem
        }

        assertSame(failure, caught)
        assertEquals(mapOf(key("PAIRING_UNAVAILABLE_ABORTED") to 1L), records)
    }

    @Test
    fun `joint details do not increment continuation invocation totals`() {
        val sink = SmtStatsSink()
        sink.observeContinuation(ExactContinuationMetrics())
        sink.observeContinuationRejection(key("REFINEMENT_NOT_REPORTED"), 2L)

        val stats = sink.snapshot().continuation

        assertEquals(1L, stats.calls)
        assertEquals(2L, stats.declines[key("REFINEMENT_NOT_REPORTED")])
    }

    private fun strictState(): LpExactState {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        return LpExactState(ExactLpModel(
            listOf(emptyList()), emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero, strict = true), ExactLpSide(one)))),
            emptyList(), ExactLpObjective(listOf(zero)),
        ))
    }

    private fun targetSolver(): LpSolver = object : LpSolver {
        override val infeasibleRay: DoubleArray? = null
        override fun solve(warm: Basis?): FloatLpResult? = null
        override fun solvePrimal(warm: Basis?): FloatLpResult? = null
        override fun continuationBasis(model: LpModel) = Basis(intArrayOf(), arrayOf(VarStatus.AT_LOWER))
    }

    private fun observer(records: MutableMap<String, Long>, continued: () -> Unit = {}): LpCertificationObserver =
        object : LpCertificationObserver {
            override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) = Unit
            override fun observeExactInput(accepted: Boolean) = Unit
            override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) = Unit
            override fun observeContinuation(metrics: ExactContinuationMetrics) = continued()
            override fun observeContinuationRejection(key: String, count: Long) {
                records[key] = (records[key] ?: 0L) + count
            }
        }

    private fun key(outcome: String) = "VERIFY_CANDIDATE_FIRST_STRICT_COLUMN_ENDPOINT_$outcome"
}
