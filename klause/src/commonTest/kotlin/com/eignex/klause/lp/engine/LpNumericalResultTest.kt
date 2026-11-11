package com.eignex.klause.lp.engine

import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpNumericalResultTest {
    @Test
    fun `budgeted point refusal should spend the remaining allowance and retain its bound`() {
        val model = LpBuilder().apply { addVar(2L, 3L, cost = 1L) }.build(Sense.MINIMIZE)
        val boundWork = assertNotNull(solveAndCertify(model, context = LpSolveContext.Numerical).numerical).work
        var offered = -1L
        val acceptance = object : BudgetedLpPointAcceptance {
            override fun accept(result: FloatLpResult, workLimit: Long): LpPointAcceptanceResult {
                offered = workLimit
                return LpPointAcceptanceResult(false, workLimit, LpFloatingBoundStop.WORK)
            }
        }

        val result = solveAndCertify(model, context = LpSolveContext.Numerical, floatAccept = acceptance,
            refinementLimits = LpRefinementLimits(maxWork = boundWork + 7L))

        val numerical = assertNotNull(result.numerical)
        assertEquals(7L, offered)
        assertEquals(boundWork + 7L, numerical.work)
        assertEquals(2.0, assertNotNull(numerical.bound).value)
        assertEquals(LpFloatingBoundStop.WORK, numerical.stop)
        assertNull(numerical.point)
        assertNull(result.witness)
    }

    @Test
    fun `budgeted point construction should report its work once without exact credit`() {
        val model = LpBuilder().apply { addVar(2L, 3L, cost = 1L) }.build(Sense.MINIMIZE)
        val phases = mutableListOf<LpPhaseMetrics>()
        val observer = object : LpCertificationObserver {
            override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) {
                error("exact certification was invoked")
            }
            override fun observeExactInput(accepted: Boolean) = Unit
            override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) = Unit
            override fun observePhase(metrics: LpPhaseMetrics) { phases += metrics }
        }
        val acceptance = object : BudgetedLpPointAcceptance {
            override fun accept(result: FloatLpResult, workLimit: Long): LpPointAcceptanceResult =
                if (workLimit >= 3L) LpPointAcceptanceResult(true, 3L)
                else LpPointAcceptanceResult(false, 0L, LpFloatingBoundStop.WORK)
        }

        val result = solveAndCertify(model, context = LpSolveContext.Numerical,
            floatAccept = acceptance, observer = observer)

        val numerical = assertNotNull(result.numerical)
        assertNotNull(numerical.point)
        assertEquals(3L, phases.single { it.phase == LpSolvePhase.SOURCE_FLOAT_ACCEPTANCE }.work)
        assertEquals(numerical.work, phases.single { it.phase == LpSolvePhase.CLAIM_ATTAINED_OPTIMUM }.work)
        assertNull(result.witness)
        assertNull(result.bound)
    }

    @Test
    fun `numerical recession should require the source point without exact certification`() {
        val model = LpBuilder().apply { addOpenAboveVar(0L, cost = -1L) }.build(Sense.MINIMIZE)
        for (accepted in listOf(false, true)) {
            var claim: LpPhaseMetrics? = null
            val observer = object : LpCertificationObserver {
                override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) {
                    error("exact certification was invoked")
                }
                override fun observeExactInput(accepted: Boolean) = Unit
                override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) = Unit
                override fun observePhase(metrics: LpPhaseMetrics) {
                    if (metrics.phase == LpSolvePhase.CLAIM_ATTAINED_OPTIMUM) claim = metrics
                }
            }
            val result = solveAndCertify(model, context = LpSolveContext.Numerical,
                observer = observer, floatAccept = { accepted })

            val numerical = assertNotNull(result.numerical)
            assertEquals(accepted, numerical.point != null)
            assertEquals(accepted, numerical.recession?.belongsTo(model) == true)
            assertFalse(numerical.optimumWithinTolerance)
            assertNull(result.witness)
            assertNull(result.unboundedness)
            assertNull(result.bound)
            assertNull(result.continuation)
            assertTrue(numerical.work > 0L)
            assertEquals(numerical.work, assertNotNull(claim).work)
        }
    }

    @Test
    fun `an exhausted recession allowance should skip source acceptance`() {
        val model = LpBuilder().apply { addOpenAboveVar(0L, cost = -1L) }.build(Sense.MINIMIZE)

        val result = solveAndCertify(model, context = LpSolveContext.Numerical,
            refinementLimits = LpRefinementLimits(maxWork = 0L),
            floatAccept = { error("source acceptance ran without work") })

        val numerical = assertNotNull(result.numerical)
        assertEquals(LpFloatingBoundStop.WORK, numerical.stop)
        assertNull(numerical.point)
        assertNull(numerical.recession)
    }

    @Test
    fun `a numerical solve should establish a bound without an exact certificate`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 3L, cost = 1L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 2L)

        val result = solveAndCertify(builder.build(Sense.MINIMIZE), context = LpSolveContext.Numerical)

        assertEquals(2.0, assertNotNull(result.numerical?.bound).value)
        assertNull(result.bound)
        assertNull(result.witness)
        assertNull(result.continuation)
        assertNull(result.reconstruction)
        assertNull(result.numerical?.point)
    }

    @Test
    fun `a numerical contradiction should carry no exact refutation`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 1L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 2L)

        val result = solveAndCertify(builder.build(Sense.MINIMIZE), context = LpSolveContext.Numerical)

        assertTrue(assertNotNull(result.numerical?.conflict).margin > FLOAT_EVIDENCE_TOLERANCE)
        assertNull(result.farkasRay)
        assertNull(result.rationalConflict)
        assertNull(result.conflictSupport)
    }

    @Test
    fun `numerical point acceptance should skip every exact certifier`() {
        val model = LpBuilder().apply { addVar(2L, 3L, cost = 1L) }.build(Sense.MINIMIZE)
        val observer = object : LpCertificationObserver {
            override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) {
                error("exact certifier was invoked")
            }
            override fun observeExactInput(accepted: Boolean) = Unit
            override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) = Unit
        }

        val result = solveAndCertify(model, context = LpSolveContext.Numerical, observer = observer,
            floatAccept = { it.primal.contentEquals(doubleArrayOf(2.0)) })

        val numerical = assertNotNull(result.numerical)
        assertNotNull(numerical.point)
        assertTrue(numerical.optimumWithinTolerance)
        assertNull(result.witness)
        assertNull(result.bound)
        assertTrue(numerical.work > 0L)
    }

    @Test
    fun `source point refusal should preserve the independent numerical lower bound`() {
        val model = LpBuilder().apply { addVar(2L, 3L, cost = 1L) }.build(Sense.MINIMIZE)

        val result = solveAndCertify(model, context = LpSolveContext.Numerical, floatAccept = { false })

        assertNull(result.numerical?.point)
        assertEquals(2.0, assertNotNull(result.numerical?.bound).value)
    }

    @Test
    fun `source acceptance cancellation should withhold numerical authority and retain work`() {
        var cancelled = false
        val model = LpBuilder().apply { addVar(2L, 3L, cost = 1L) }.build(Sense.MINIMIZE)

        val result = solveAndCertify(model, context = LpSolveContext.Numerical,
            cancellation = Cancellation { cancelled }, floatAccept = {
                cancelled = true
                true
            })

        val numerical = assertNotNull(result.numerical)
        assertNull(numerical.bound)
        assertNull(numerical.point)
        assertEquals(LpFloatingBoundStop.CANCELLED, numerical.stop)
        assertTrue(numerical.work > 0L)
    }

    @Test
    fun `a spent deduction allowance should not acquire a point or exact fallback`() {
        val model = LpBuilder().apply { addVar(2L, 3L, cost = 1L) }.build(Sense.MINIMIZE)

        val result = solveAndCertify(model, context = LpSolveContext.Numerical,
            refinementLimits = LpRefinementLimits(maxWork = 0L),
            floatAccept = { error("source acceptance was invoked after spending the allowance") })

        val numerical = assertNotNull(result.numerical)
        assertNull(numerical.bound)
        assertNull(numerical.point)
        assertEquals(LpFloatingBoundStop.WORK, numerical.stop)
        assertEquals(0L, numerical.work)
        assertNull(result.reconstruction)
    }

    @Test
    fun `exact solve contexts should retain their exact result surface`() {
        val model = LpBuilder().apply { addVar(2L, 3L, cost = 1L) }.build(Sense.MINIMIZE)

        val result = solveAndCertify(model, context = LpSolveContext.Production)

        assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
        assertEquals(2L, result.integerObjectiveLowerBound)
        assertNotNull(result.witness)
        assertNull(result.numerical)
    }

    @Test
    fun `numerical claims should report their cost without exact claim satisfaction`() {
        val phases = ArrayList<LpPhaseMetrics>()
        val model = LpBuilder().apply { addVar(2L, 3L, cost = 1L) }.build(Sense.MINIMIZE)
        val observer = object : LpCertificationObserver {
            override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) = Unit
            override fun observeExactInput(accepted: Boolean) = Unit
            override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) = Unit
            override fun observePhase(metrics: LpPhaseMetrics) { phases += metrics }
        }

        val result = solveAndCertify(model, context = LpSolveContext.Numerical, observer = observer,
            floatAccept = { true })

        val claim = phases.single { it.phase == LpSolvePhase.CLAIM_ATTAINED_OPTIMUM }
        assertEquals("NUMERICAL_OPTIMUM", claim.outcome)
        assertEquals(assertNotNull(result.numerical).work, claim.work)
        assertTrue(claim.work > 0L)
    }
}
