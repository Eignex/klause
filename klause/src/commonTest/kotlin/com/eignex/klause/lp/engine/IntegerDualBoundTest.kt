package com.eignex.klause.lp.engine

import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.integerDualLowerBoundCeil
import com.eignex.klause.simplex.exact.BigFraction
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

    @Test
    fun `decimal and tiny binary coefficients decline bounded dyadic scaling`() {
        for (coefficient in listOf(0.1, 1e-10, Double.MIN_VALUE, Double.MAX_VALUE)) {
            val model = LpBuilder().apply {
                val x = addRealVar(0.0, 1.0)
                addRealRow(intArrayOf(x), doubleArrayOf(coefficient), Relation.EQ, 0.0)
            }.build(Sense.MINIMIZE)

            assertEquals(null, rationalizeToIntegerModel(model, outwardRealUppers = true))
        }
    }

    @Test
    fun `dyadic scaling preserves exact row coefficients and antecedents`() {
        val model = LpBuilder().apply {
            val x = addRealVar(2.0, 4.0, cost = 0.5, tag = 7)
            addRealRow(
                intArrayOf(x),
                doubleArrayOf(0.25),
                Relation.LE,
                0.75,
                strict = true,
                premiseLits = intArrayOf(9),
            )
        }.build(Sense.MINIMIZE)

        val scaled = assertNotNull(rationalizeToIntegerModel(model, outwardRealUppers = true))
        val integral = scaled.model
        val scale = BigFraction.ofLong(scaled.scale)

        assertEquals(BigFraction.ofDouble(0.25), BigFraction.ofLong(integral.csc.colVal.single()) * scale.reciprocal())
        assertEquals(BigFraction.ofDouble(0.25), BigFraction.ofLong(integral.rhs.single()) * scale.reciprocal())
        assertEquals(BigFraction.ONE, BigFraction.ofLong(integral.objConstant) * scale.reciprocal())
        assertEquals(7, integral.tag.single())
        assertEquals(false, integral.rowGlobal.single())
        assertEquals(true, integral.rowStrict.single())
        assertEquals(9, integral.rowPremises.single()!!.boolLits.single())
    }

    @Test
    fun `slack costs and sides follow logical coordinate scaling`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 0.0)
            addRealRow(intArrayOf(x), doubleArrayOf(0.5), Relation.LE, 0.5)
        }.build(Sense.MINIMIZE)
        model.doubleView!!.cost[1] = 1.0
        model.doubleView.hasUpper[1] = true
        model.doubleView.upper[1] = 0.5

        val scaled = assertNotNull(rationalizeToIntegerModel(model, outwardRealUppers = true))
        val certificate = assertNotNull(integerCertify(scaled.model, doubleArrayOf(1.0), scaleBits = 0))

        assertEquals(2L, scaled.scale)
        assertEquals(1L, scaled.model.cost[1])
        assertEquals(1L, scaled.model.upper[1])
        assertEquals(1L, certificate.objectiveBoundCeil(0L))
        assertEquals(
            BigFraction.ofDouble(0.5),
            BigFraction.ofLong(scaled.model.cost[1] * scaled.model.upper[1]) *
                BigFraction.ofLong(scaled.scale).reciprocal(),
        )
    }

    @Test
    fun `unsupported fractional slack costs decline scaling`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 1.0)
            addRealRow(intArrayOf(x), doubleArrayOf(0.5), Relation.LE, 0.5)
        }.build(Sense.MINIMIZE)
        model.doubleView!!.cost[1] = 0.5

        assertEquals(null, rationalizeToIntegerModel(model, outwardRealUppers = true))
    }

    @Test
    fun `inexact binary objective constant cannot be accepted as zero`() {
        val model = LpBuilder().apply { addRealVar(0.0, 1.0) }.build(Sense.MINIMIZE)
        model.doubleView!!.objConstant = 1e-10

        assertEquals(false, assertNotNull(rationalizeToIntegerModel(model, true)).objConstantExact)
        assertEquals(null, rationalizedDualLowerBoundCeil(model, doubleArrayOf()))
    }

    @Test
    fun `direct certificates cannot use probe sides as finite support`() {
        for (cost in listOf(-1L, 1L)) {
            val model = LpBuilder().apply { addFreeVar(null, null, cost = cost) }.build(Sense.MINIMIZE)

            assertEquals(null, integerCertify(model, doubleArrayOf()))
        }
        val model = LpBuilder().apply {
            val x = addFreeVar(null, 1L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.EQ, -LP_UNBOUNDED_PROBE - 1L)
        }.build(Sense.MINIMIZE)
        assertEquals(null, integerFarkasRay(model, doubleArrayOf(-1.0)))
    }

    @Test
    fun `direct Farkas validation rejects decimal cancellation on an open column`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, null)
            val y = addRealVar(0.0, null)
            addRealRow(intArrayOf(x, y), doubleArrayOf(1.0000000001, -1.0), Relation.EQ, 1.0)
            addRealRow(intArrayOf(x, y), doubleArrayOf(1.0, -1.0), Relation.EQ, 0.0)
        }.build(Sense.MINIMIZE)

        assertEquals(null, integerFarkasRay(model, doubleArrayOf(1.0, -1.0)))
    }

    @Test
    fun `malformed vectors and numeric views decline direct certificates`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 1.0)
            addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.EQ, 0.5)
        }.build(Sense.MINIMIZE)

        assertEquals(null, roundDuals(model, doubleArrayOf()))
        assertEquals(null, integerFarkasRay(model, doubleArrayOf()))
        model.doubleView!!.colVal[0] = Double.NaN
        assertEquals(null, rationalizeToIntegerModel(model, true))
    }

    @Test
    fun `dyadic Farkas ray validates exact source rows and their premises`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 2.0)
            addRealRow(intArrayOf(x), doubleArrayOf(0.5), Relation.LE, 0.0, premiseLits = intArrayOf(7))
            addRealRow(intArrayOf(x), doubleArrayOf(0.5), Relation.GE, 0.5, premiseLits = intArrayOf(9))
        }.build(Sense.MINIMIZE)

        val ray = assertNotNull(integerFarkasRay(model, doubleArrayOf(-1.0, -1.0)))
        val first = BigFraction.ofLong(ray[0])
        val second = BigFraction.ofLong(ray[1])

        assertEquals(BigFraction.ZERO, (first - second) * assertNotNull(BigFraction.ofDouble(0.5)))
        assertTrue(second * assertNotNull(BigFraction.ofDouble(-0.5)) > BigFraction.ZERO)
        assertTrue(first.signum() <= 0 && second.signum() <= 0)
        assertEquals(
            setOf(7, 9),
            ray.indices.filter { ray[it] != 0L }.map { model.rowPremises[it]!!.boolLits.single() }.toSet(),
        )
    }

    @Test
    fun `unscaled binary Farkas candidate is checked beyond the compact scale budget`() {
        for (rhs in listOf(-5e-7, -Double.MIN_VALUE)) {
            val model = LpBuilder().apply {
                val x = addRealVar(0.0, 1.0)
                addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.LE, rhs, premiseLits = intArrayOf(11))
            }.build(Sense.MINIMIZE)

            val ray = assertNotNull(integerFarkasRay(model, doubleArrayOf(-1.0)))

            assertEquals(null, rationalizeToIntegerModel(model, true))
            assertTrue(BigFraction.ofLong(ray.single()) * assertNotNull(BigFraction.ofDouble(rhs)) > BigFraction.ZERO)
            assertTrue(ray.single() < 0L)
            assertEquals(11, model.rowPremises.single()!!.boolLits.single())
        }
    }

    @Test
    fun `finite logical sides participate in exact scale selection`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 1.0)
            addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.LE, 1.0)
        }.build(Sense.MINIMIZE)
        model.doubleView!!.hasUpper[1] = true
        model.doubleView.upper[1] = 0.5

        val scaled = assertNotNull(rationalizeToIntegerModel(model, true))

        assertEquals(2L, scaled.scale)
        assertEquals(1L, scaled.model.upper[1])
    }
}
