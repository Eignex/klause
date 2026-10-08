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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The integer-multiplier bound ([integerDualLowerBoundCeil]) must be SOUND — never exceeding
 * `ceil(LP optimum)` — and usefully tight (equal to it on the large majority), the same way
 * [SafeObjectiveBoundTest] checks the float bound.
 */
class IntegerDualBoundTest {
    @Test
    fun `malformed legacy row premises decline numerical certification and source conversion`() {
        val premises = listOf(
            LpRowPremises(intArrayOf(0), booleanArrayOf(), longArrayOf(0L)),
            LpRowPremises(intArrayOf(0), booleanArrayOf(false), longArrayOf()),
            LpRowPremises(intArrayOf(-1), booleanArrayOf(false), longArrayOf(0L)),
            LpRowPremises(intArrayOf(), booleanArrayOf(), longArrayOf(), intArrayOf(-1)),
        )
        for (premise in premises) {
            val model = LpBuilder().apply {
                val x = addVar(0L, 9L, cost = 1L)
                addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 3L)
            }.build(Sense.MINIMIZE)
            model.rowGlobal[0] = false
            model.rowPremises[0] = premise

            assertNull(integerCertify(model, doubleArrayOf(-1.0)))
            assertNull(model.authoritativeModel())
        }
    }

    @Test
    fun `legacy certificates reject mutations to numeric authority and source premises`() {
        val edits = listOf<Pair<String, (LpModel) -> Unit>>(
            "cost" to { it.cost[0] = 0L },
            "rhs" to { it.rhs[0] = 0L },
            "upper" to { it.upper[0] = 4L },
            "upper presence" to { it.hasUpper[0] = false },
            "coefficient" to { it.csc.colVal[0] = -2L },
            "matrix row" to { it.csc.rowIdx[0] = 1 },
            "matrix column" to { it.csc.colPtr[1] = 0 },
            "origin" to { it.loShift[0] = 1L },
            "tag" to { it.tag[0] = 4 },
            "source rhs" to { it.flippedRhs[0] = -2L },
            "lower probe" to { it.probeClampedLo[0] = true },
            "upper probe" to { it.probeClampedHi[0] = true },
            "global row" to { it.rowGlobal[0] = true },
            "strict row" to { it.rowStrict[0] = true },
            "premise variable" to { assertNotNull(it.rowPremises[0]).vars[0] = 1 },
            "premise side" to { assertNotNull(it.rowPremises[0]).isUpper[0] = true },
            "premise bound" to { assertNotNull(it.rowPremises[0]).thresholds[0] = 2L },
            "activator" to { assertNotNull(it.rowPremises[0]).boolLits[0] = 9 },
            "premises" to { it.rowPremises[0] = null },
        )
        for ((name, edit) in edits) {
            val model = LpBuilder().apply {
                val x = addVar(0L, 9L, cost = 1L)
                val y = addVar(0L, 9L, cost = 1L)
                addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 3L)
                addRow(intArrayOf(y), longArrayOf(1L), Relation.GE, 4L)
            }.build(Sense.MINIMIZE)
            model.rowGlobal[0] = false
            model.rowPremises[0] = LpRowPremises(intArrayOf(0), booleanArrayOf(false), longArrayOf(0L), intArrayOf(7))
            val certificate = assertNotNull(integerCertify(model, doubleArrayOf(-1.0, -1.0)))
            assertTrue(certificate.belongsTo(model), name)

            edit(model)

            assertFalse(certificate.belongsTo(model), name)
        }
    }

    @Test
    fun `retained certificates bind immutable authority across equivalent views and reject later scopes`() {
        val source = assertNotNull(LpBuilder().apply { addVar(3L, 9L, cost = 1L) }
            .build(Sense.MINIMIZE).authoritativeModel())
        val trail = LpBoundTrail(source)
        assertTrue(trail.push())
        assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2L)), 7L))
        val certificate = assertNotNull(integerCertify(assertNotNull(trail.state.toWorkingModel()), doubleArrayOf()))

        assertTrue(certificate.belongsTo(assertNotNull(trail.state.toWorkingModel())))
        assertFalse(certificate.belongsTo(assertNotNull(LpExactState(trail.state.model).toWorkingModel())))
        assertTrue(trail.push())
        assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(3L)), 8L))
        assertFalse(certificate.belongsTo(assertNotNull(trail.state.toWorkingModel())))
        assertTrue(trail.pop(0))
        val parent = assertNotNull(trail.state.toWorkingModel())
        assertFalse(certificate.belongsTo(parent))
        assertEquals(3.0, tightObjectiveLowerBound(parent, doubleArrayOf(), certificate))
    }

    @Test
    fun `retained integral IEEE data certifies without losing its source representation`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 3L, cost = 1L)
            addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 2.0)
        }.build(Sense.MINIMIZE)
        val authority = assertNotNull(source.authoritativeModel())
        val model = assertNotNull(LpExactState(authority).toWorkingModel())

        val certificate = assertNotNull(integerCertify(model, doubleArrayOf(-1.0)))

        assertEquals(2L, certificate.objectiveBoundCeil(0L))
        assertNotNull(authority.entries(0).single().number.ieeeBits)
        assertNull(authority.entries(0).single().number.legacyLong())
        assertEquals(-1L, authority.entries(0).single().number.exactLong())
    }

    @Test
    fun `retained certificates use the live shifted lower endpoint`() {
        val source = LpBuilder().apply { addVar(3L, 9L, cost = 2L) }.build(Sense.MINIMIZE)
        val trail = LpBoundTrail(assertNotNull(source.authoritativeModel()))
        assertTrue(trail.push())
        assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2L)), 7L))
        val retained = assertNotNull(trail.state.toWorkingModel())

        val certificate = assertNotNull(integerCertify(retained, doubleArrayOf(), scaleBits = 0))

        assertEquals(10L, certificate.objectiveBoundCeil(0L))
        assertEquals(1L, certificate.fixSteps(0, 13L, 0L))
        assertTrue(trail.pop(0))
        val parent = assertNotNull(integerCertify(assertNotNull(trail.state.toWorkingModel()), doubleArrayOf()))
        assertEquals(6L, parent.objectiveBoundCeil(0L))
    }

    @Test
    fun `retained certificates use the live shifted upper endpoint`() {
        val source = LpBuilder().apply { addVar(-3L, 9L, cost = -2L) }.build(Sense.MINIMIZE)
        val trail = LpBoundTrail(assertNotNull(source.authoritativeModel()))
        assertTrue(trail.push())
        assertTrue(trail.assertBound(0, true, ExactLpSide(ExactLpNumber.of(8L)), 8L))

        val certificate = assertNotNull(integerCertify(assertNotNull(trail.state.toWorkingModel()), doubleArrayOf()))

        assertEquals(-10L, certificate.objectiveBoundCeil(0L))
        assertEquals(1L, certificate.fixSteps(0, -7L, 0L))
    }

    @Test
    fun `retained certificates remove inactive row weight and recover parent support after pop`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 4L, cost = 1L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        }.build(Sense.MINIMIZE)
        val trail = LpBoundTrail(assertNotNull(source.authoritativeModel()))
        assertTrue(trail.push())
        assertTrue(trail.suspend(setOf(0L)))
        val row = LpScopedRow(
            1L, listOf(0 to ExactLpNumber.of(-1L)), ExactLpNumber.of(-2L),
            ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(0L)))),
        )
        assertTrue(trail.append(row, true))

        val child = assertNotNull(integerCertify(assertNotNull(trail.state.toWorkingModel()), doubleArrayOf(999.0, -1.0)))

        assertEquals(2L, child.objectiveBoundCeil(0L))
        assertFalse(child.dualNonzeroRow(0))
        assertTrue(child.dualNonzeroRow(1))
        assertTrue(trail.pop(0))
        val parent = assertNotNull(integerCertify(assertNotNull(trail.state.toWorkingModel()), doubleArrayOf(-1.0, 999.0)))
        assertEquals(1L, parent.objectiveBoundCeil(0L))
        assertTrue(parent.dualNonzeroRow(0))
        assertFalse(parent.dualNonzeroRow(1))
    }

    @Test
    fun `retained certificates repair upper only logicals against their actual cost`() {
        val model = LpBuilder().apply {
            val x = addVar(0L, 2L, cost = -1L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.LE, 1L)
        }.build(Sense.MINIMIZE)
        val source = assertNotNull(model.authoritativeModel())
        val zero = ExactLpNumber.of(0L)
        val columns = MutableList(source.numVars) { source.column(it) }
        columns[1] = columns[1].copy(bounds = ExactLpBounds(upper = ExactLpSide(zero)))
        val state = LpExactState(source.copy(columns = columns))

        val certificate = assertNotNull(integerCertify(assertNotNull(state.toWorkingModel()), doubleArrayOf(-999.0)))

        assertEquals(-2L, certificate.objectiveBoundCeil(0L))
        assertFalse(certificate.dualNonzeroRow(0))
    }

    @Test
    fun `retained integral certification declines fractional source coefficients and continuous columns`() {
        val fractional = LpBuilder().apply {
            val x = addVar(0L, 2L)
            addRealRow(intArrayOf(x), doubleArrayOf(0.5), Relation.LE, 0.5)
        }.build(Sense.MINIMIZE)
        val continuous = LpBuilder().apply { addRealVar(0.0, 2.0, cost = 1.0) }.build(Sense.MINIMIZE)

        val fractionalModel = assertNotNull(LpExactState(assertNotNull(fractional.authoritativeModel())).toWorkingModel())
        val continuousModel = assertNotNull(LpExactState(assertNotNull(continuous.authoritativeModel())).toWorkingModel())
        assertNull(integerCertify(fractionalModel, doubleArrayOf(0.0)))
        assertNull(integerCertify(continuousModel, doubleArrayOf()))
    }

    @Test
    fun `retained logical multiplier repair handles the minimum signed cost without negating it`() {
        val source = assertNotNull(LpBuilder().apply {
            addVar(0L, 0L)
            addRow(intArrayOf(), longArrayOf(), Relation.LE, 0L)
        }.build(Sense.MINIMIZE).authoritativeModel())
        val columns = List(source.numVars) {
            if (it == source.n) source.column(it).copy(bounds = ExactLpBounds()) else source.column(it)
        }
        val objective = ExactLpObjective(listOf(ExactLpNumber.of(0L), ExactLpNumber.of(Long.MIN_VALUE)))
        val retained = assertNotNull(LpExactState(source.copy(columns = columns, objective = objective)).toWorkingModel())

        val certificate = assertNotNull(integerCertify(retained, doubleArrayOf(1.0), scaleBits = 0))

        assertEquals(0L, certificate.objectiveBoundCeil(0L))
        assertEquals(0, certificate.reducedCostSign(1))
    }

    @Test
    fun `retained strict Farkas rays certify a zero margin contradiction`() {
        val source = assertNotNull(LpBuilder().apply {
            val x = addVar(0L, 2L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.LE, 0L)
        }.build(Sense.MINIMIZE).authoritativeModel())
        val model = assertNotNull(LpExactState(source.copy(rows = listOf(ExactLpRow(strict = true)))).toWorkingModel())

        val ray = assertNotNull(integerFarkasRay(model, doubleArrayOf(-1.0)))

        assertTrue(sourceFarkasValid(model, ray))
    }

    @Test
    fun `retained Farkas rays use exact rational rows and reject inactive support after pop`() {
        val source = LpBuilder().apply { addRealVar(0.0, 2.0) }.build(Sense.MINIMIZE)
        val trail = LpBoundTrail(assertNotNull(source.authoritativeModel()))
        assertTrue(trail.push())
        val coefficient = ExactLpNumber.of(BigFraction.ofLong(3L).reciprocal())
        val logical = ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(0L))))
        assertTrue(trail.append(emptyList(), listOf(
            LpScopedRow(0L, listOf(0 to coefficient), ExactLpNumber.of(0L), logical),
            LpScopedRow(
                1L, listOf(0 to ExactLpNumber.of(coefficient.value.negated())),
                ExactLpNumber.of(coefficient.value.negated()), logical,
            ),
        ), true))
        val child = assertNotNull(trail.state.toWorkingModel())

        val ray = assertNotNull(integerFarkasRay(child, doubleArrayOf(-1.0, -1.0)))

        assertTrue(sourceFarkasValid(child, ray))
        assertTrue(trail.pop(0))
        val parent = assertNotNull(trail.state.toWorkingModel())
        assertFalse(sourceFarkasValid(parent, ray))
        assertNull(integerFarkasRay(parent, doubleArrayOf(-1.0, -1.0)))
    }

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
        repeat(300) {
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
        assertTrue(total > 60, "covered only $total instances")
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
    fun `integer certification charges arithmetic and mutable authority snapshots`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 4L, cost = 1L)
        val y = builder.addVar(0L, 4L, cost = 1L)
        builder.addRow(intArrayOf(x, y), longArrayOf(1L, 1L), Relation.GE, 2L)
        val model = builder.build(Sense.MINIMIZE)
        val costs = ArrayList<LpCertifierCost>()
        val observer = object : LpCertificationObserver {
            override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) {
                costs += cost
            }
            override fun observeExactInput(accepted: Boolean) = Unit
            override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) = Unit
        }

        val legacy = assertNotNull(integerCertify(model, doubleArrayOf(1.0), observer = observer))
        assertTrue(legacy.belongsTo(model, observer))
        val retained = assertNotNull(LpExactState(assertNotNull(model.authoritativeModel())).toWorkingModel())
        val immutable = assertNotNull(integerCertify(retained, doubleArrayOf(1.0), observer = observer))
        assertTrue(immutable.belongsTo(retained, observer))

        assertEquals(listOf<LpCertifierCost>(LpCertifierCost.Metered(39L), LpCertifierCost.Metered(33L),
            LpCertifierCost.Metered(6L)), costs)
    }

    @Test
    fun `a pass over wider operands costs the square of its word length`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 4L, cost = 1L)
        val y = builder.addVar(0L, 4L, cost = 1L)
        builder.addRow(intArrayOf(x, y), longArrayOf(1L, 1L), Relation.GE, 2L)
        val model = builder.build(Sense.MINIMIZE)
        val cases = listOf(Long.SIZE_BITS to 6L, 2 * Long.SIZE_BITS to 6L * 4L, 3 * Long.SIZE_BITS to 6L * 9L)
        for ((bits, expected) in cases) {
            val scans = LpScanCount(model).apply { scan(bits) }

            assertEquals(LpCertifierCost.Metered(expected), scans.cost(), "$bits-bit operands")
        }
    }

    @Test
    fun `inexact objective constant records one rejected rationalization`() {
        val builder = LpBuilder()
        builder.addRealVar(1e-10, 1.0, cost = 1.0)
        val model = builder.build(Sense.MINIMIZE)
        var exactInputAttempts = 0
        var exactInputRejections = 0
        val observer = object : LpCertificationObserver {
            override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) = Unit
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
            override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) {
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
        val model = LpBuilder().apply { addRealVar(1e-10, 1.0, cost = 1.0) }.build(Sense.MINIMIZE)

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
