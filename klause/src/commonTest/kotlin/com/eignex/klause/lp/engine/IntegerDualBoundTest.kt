package com.eignex.klause.lp.engine

import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.integerDualLowerBoundCeil
import kotlin.math.ceil
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The integer-multiplier bound ([integerDualLowerBoundCeil]) must be SOUND — never exceeding
 * `ceil(LP optimum)` — and usefully tight (equal to it on the large majority), the same way
 * [SafeObjectiveBoundTest] checks the float bound.
 */
class IntegerDualBoundTest {

    private fun randomModel(m: Int, n: Int, rng: Random): LpModel {
        val b = LpBuilder()
        repeat(n) { b.addVar(0L, rng.nextLong(2, 9), cost = rng.nextLong(-6, 7)) }
        val cols = IntArray(n) { it }
        repeat(m) {
            val vals = LongArray(n) { rng.nextLong(-4, 5) }
            b.addRow(cols, vals, Relation.LE, rng.nextLong(3, 25))
        }
        return b.build(Sense.MINIMIZE)
    }

    @Test
    fun `integer bound is sound and tight against ceil of the LP optimum`() {
        val rng = Random(20260622)
        var total = 0
        var finite = 0
        var matchesCeil = 0
        repeat(1500) {
            val model = randomModel(rng.nextInt(3, 10), rng.nextInt(3, 10), rng)
            val opt = exactLpOptimum(model)
            if (opt.isNaN()) return@repeat
            val rev = RevisedSimplex(model).solve() ?: return@repeat
            total++
            val bound = integerDualLowerBoundCeil(model, rev.duals) ?: return@repeat
            finite++
            // Sound: ceil of a valid LP lower bound never exceeds ceil(LP optimum).
            val ceilOpt = ceil(opt)
            assertTrue(
                bound.toDouble() <= ceilOpt + 1e-6,
                "UNSOUND integer bound $bound > ceil(optimum $opt)",
            )
            // Power-of-two scaling should recover ceil(LP optimum) on the large majority of instances.
            if (bound.toDouble() in (ceilOpt - 0.5)..(ceilOpt + 0.5)) matchesCeil++
        }
        assertTrue(total > 300, "covered only $total instances")
        assertTrue(finite >= total * 4 / 5, "integer bound was finite on only $finite/$total")
        assertTrue(matchesCeil >= finite * 2 / 3, "matched ceil(optimum) on only $matchesCeil/$finite")
    }

    @Test
    fun `a slack multiplier off by float noise still yields an exact bound`() {
        // Mirrors the float bound's own repair test: min -x subject to x <= 4, x in [0, 10]. The row's
        // exact multiplier is -1, and a slack carries no upper bound, so a multiplier a hair the other
        // side of its own reduced cost used to abandon the certificate outright.
        val b = LpBuilder()
        val x = b.addVar(0L, 10L, cost = -1L)
        b.addRow(intArrayOf(x), longArrayOf(1L), Relation.LE, 4L)
        val model = b.build(Sense.MINIMIZE)
        val optimum = exactLpOptimum(model)

        val bound = assertNotNull(
            integerDualLowerBoundCeil(model, doubleArrayOf(1e-12)),
            "a multiplier off by 1e-12 must not cost the whole certificate",
        )

        assertTrue(bound <= ceil(optimum) + 1e-9, "UNSOUND repaired bound $bound > ceil(optimum) ${ceil(optimum)}")
    }

    @Test
    fun `the repair leaves a certificate that already had one untouched`() {
        val b = LpBuilder()
        val x = b.addVar(0L, 10L, cost = -1L)
        b.addRow(intArrayOf(x), longArrayOf(1L), Relation.LE, 4L)
        val model = b.build(Sense.MINIMIZE)

        val exact = assertNotNull(integerDualLowerBoundCeil(model, doubleArrayOf(-1.0)))

        assertEquals(-4L, exact)
    }

    @Test
    fun `fixing steps retain source constants at every certificate scale`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 10L, cost = 2L)
        val model = builder.build(Sense.MINIMIZE)

        for (scaleBits in listOf(0, 7, 30)) {
            val cert = assertNotNull(integerCertify(model, doubleArrayOf(), scaleBits))

            assertEquals(3L, cert.fixSteps(x, improvingMax = 5L, sourceConstant = -1L))
            assertTrue(cert.improvingGapNonNegative(improvingMax = 5L, sourceConstant = -1L))
        }
    }

    @Test
    fun `fixing steps use the upper endpoint for a negative reduced cost`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 10L, cost = -2L)
        val model = builder.build(Sense.MINIMIZE)
        val cert = assertNotNull(integerCertify(model, doubleArrayOf(), scaleBits = 11))

        assertEquals(2L, cert.fixSteps(x, improvingMax = -15L, sourceConstant = 1L))
    }

    @Test
    fun `fixing arithmetic declines an unrepresentable reduced-cost magnitude`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 1L, cost = Long.MIN_VALUE)
        val model = builder.build(Sense.MINIMIZE)
        val cert = assertNotNull(integerCertify(model, doubleArrayOf(), scaleBits = 0))

        assertEquals(null, cert.fixSteps(x, improvingMax = Long.MIN_VALUE, sourceConstant = 0L))
    }

    @Test
    fun `fixing gap handles the minimum source constant without negation overflow`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 1L, cost = 1L)
        val cert = assertNotNull(integerCertify(builder.build(Sense.MINIMIZE), doubleArrayOf(), scaleBits = 0))

        assertTrue(cert.improvingGapNonNegative(Long.MIN_VALUE, Long.MIN_VALUE))
        assertEquals(0L, cert.fixSteps(x, Long.MIN_VALUE, Long.MIN_VALUE))
    }

    @Test
    fun `integer certificate declines sub-unit continuous movement`() {
        val builder = LpBuilder()
        builder.addRealVar(0.0, 0.5, cost = 1.0)
        val model = builder.build(Sense.MINIMIZE)

        assertEquals(null, integerCertify(model, doubleArrayOf(), scaleBits = 20))
    }

    @Test
    fun `inexact objective constant records one rejected rationalization`() {
        val builder = LpBuilder()
        builder.addRealVar(0.0, 1.0)
        val model = builder.build(Sense.MINIMIZE)
        model.doubleView!!.objConstant = Double.MAX_VALUE
        var exactInputAttempts = 0
        var exactInputRejections = 0
        val observer = object : LpCertificationObserver {
            override fun observe(certifier: LpCertifier, success: Boolean) = Unit
            override fun observeExactInput(accepted: Boolean) {
                exactInputAttempts++
                if (!accepted) exactInputRejections++
            }
            override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) = Unit
        }

        val bound = rationalizedDualLowerBoundCeil(model, doubleArrayOf(), observer = observer)

        assertEquals(null, bound)
        assertEquals(1, exactInputAttempts)
        assertEquals(1, exactInputRejections)
    }

    @Test
    fun `an exact Farkas candidate rejected by both signs records one decline`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 1L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.LE, 0L)
        val model = builder.build(Sense.MINIMIZE)
        val basis = Basis(intArrayOf(model.n), Array(model.numVars) { VarStatus.BASIC })
        var attempts = 0
        var successes = 0
        val observer = object : LpCertificationObserver {
            override fun observe(certifier: LpCertifier, success: Boolean) {
                if (certifier == LpCertifier.EXACT_FARKAS) {
                    attempts++
                    if (success) successes++
                }
            }
            override fun observeExactInput(accepted: Boolean) = Unit
            override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) = Unit
        }

        val ray = integerFarkasRay(
            model,
            doubleArrayOf(Double.NaN),
            basis = basis,
            basisRow = 0,
            observer = observer,
        )

        assertEquals(null, ray)
        assertEquals(1, attempts)
        assertEquals(0, successes)
    }
}
