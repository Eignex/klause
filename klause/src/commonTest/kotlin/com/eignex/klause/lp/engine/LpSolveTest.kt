package com.eignex.klause.lp.engine

import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.solveAndCertify
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.BigRationalConflict
import com.eignex.klause.simplex.exact.ExactSimplexBound
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpSolveTest {
    @Test
    fun `retained rational bounds omit inactive row weight and recover ancestor support after pop`() {
        val source = assertNotNull(LpBuilder().apply {
            val x = addRealVar(0.0, 4.0, cost = 1.0)
            addRealRow(intArrayOf(x), doubleArrayOf(0.5), Relation.GE, 0.5)
        }.build(Sense.MINIMIZE).authoritativeModel())
        val trail = LpBoundTrail(source)
        assertTrue(trail.push())
        assertTrue(trail.suspend(setOf(0L)))
        assertTrue(trail.append(
            LpScopedRow(
                1L, listOf(0 to ExactLpNumber.of(assertNotNull(BigFraction.ofDouble(-0.5)))), ExactLpNumber.of(-1L),
                ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(0L)))),
            ),
            true,
        ))
        val child = assertNotNull(trail.state.toWorkingModel())

        val childBound = assertNotNull(certifyLpBound(child, doubleArrayOf(999.0, -2.0)))

        assertEquals(BigFraction.ofLong(2L), childBound.value)
        assertEquals(listOf(1), assertNotNull(childBound.support).rows.map { it.first })
        assertEquals(2.0, safeObjectiveLowerBound(child, doubleArrayOf(999.0, -2.0)))
        assertTrue(trail.pop(0))
        val parent = assertNotNull(trail.state.toWorkingModel())
        val parentBound = assertNotNull(certifyLpBound(parent, doubleArrayOf(-2.0, 999.0)))
        assertEquals(BigFraction.ONE, parentBound.value)
        assertEquals(listOf(0), assertNotNull(parentBound.support).rows.map { it.first })
    }

    @Test
    fun `native continuation preserves constant objective bounds in minimized source units`() {
        for (sense in Sense.entries) {
            for (fractional in listOf(false, true)) {
                val zero = ExactLpNumber.of(0L)
                val one = ExactLpNumber.of(1L)
                val constant = if (fractional) {
                    BigFraction.ofLong(7L) * BigFraction.ofLong(3L).reciprocal()
                } else {
                    BigFraction.ofLong(4L)
                }
                val external = if (fractional) {
                    BigFraction.ofLong(5L) * BigFraction.ofLong(4L).reciprocal()
                } else {
                    BigFraction.ZERO
                }
                val scale = if (fractional) 2L else 1L
                val source = ExactLpModel(
                    listOf(emptyList()),
                    emptyList(),
                    listOf(
                        ExactLpColumn(
                            ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)),
                            origin = ExactLpNumber.of(7L),
                        ),
                    ),
                    emptyList(),
                    ExactLpObjective(
                        listOf(zero),
                        ExactLpNumber.of(constant),
                        ExactLpNumber.of(scale),
                        ExactLpNumber.of(external),
                        sense,
                    ),
                )
                val state = LpExactState(source)
                val model = assertNotNull(state.toWorkingModel())
                val solver = object : LpSolver {
                    override val infeasibleRay: DoubleArray? = null
                    override fun solve(warm: Basis?): FloatLpResult? = null
                    override fun solvePrimal(warm: Basis?): FloatLpResult? = null
                    override fun continuationBasis(model: LpModel) = Basis(intArrayOf(), arrayOf(VarStatus.AT_LOWER))
                }

                val result = certifyLpResult(model, solver, null)

                val expected = constant * BigFraction.ofLong(scale).reciprocal() + external
                assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
                assertEquals(expected, result.lowerBound)
                assertEquals(expected, assertNotNull(result.witness).objective)
                assertEquals(
                    if (sense == Sense.MINIMIZE) expected else expected.negated(),
                    source.objective.sourceValue(listOf(BigFraction.ZERO)),
                )
                assertEquals(if (fractional) null else 4L, result.integerObjectiveLowerBound)
                val support = assertNotNull(assertNotNull(result.bound).support)
                assertSame(state, support.state)
                assertTrue(support.rows.isEmpty())
                assertTrue(support.sides.isEmpty())
            }
        }
    }

    @Test
    fun `native continuation does not invent bounds for nonconstant structural or logical costs`() {
        for (costColumn in listOf(0, 1)) {
            val zero = ExactLpNumber.of(0L)
            val one = ExactLpNumber.of(1L)
            val source = ExactLpModel(
                listOf(listOf(ExactLpEntry(0, one))),
                listOf(one),
                List(2) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one))) },
                listOf(ExactLpRow()),
                ExactLpObjective(List(2) { if (it == costColumn) one else zero }),
            )
            val model = assertNotNull(LpExactState(source).toWorkingModel())
            val solver = object : LpSolver {
                override val infeasibleRay: DoubleArray? = null
                override fun solve(warm: Basis?): FloatLpResult? = null
                override fun solvePrimal(warm: Basis?): FloatLpResult? = null
                override fun continuationBasis(model: LpModel) =
                    Basis(intArrayOf(1), arrayOf(VarStatus.AT_LOWER, VarStatus.BASIC))
            }

            val result = certifyLpResult(model, solver, null)

            assertEquals(LpVerdict.FEASIBLE, result.verdict)
            assertNotNull(result.witness)
            assertNull(result.bound)
        }
    }

    @Test
    fun `constant continuation bounds and witnesses honor independent policy rejection`() {
        for (rejected in listOf(LpCertifier.INTEGER, LpCertifier.EXACT_BASIS)) {
            val source = assertNotNull(LpBuilder().apply { addVar(0L, 1L) }.build(Sense.MINIMIZE).authoritativeModel())
            val model = assertNotNull(LpExactState(source).toWorkingModel())
            val solver = object : LpSolver {
                override val infeasibleRay: DoubleArray? = null
                override fun solve(warm: Basis?): FloatLpResult? = null
                override fun solvePrimal(warm: Basis?): FloatLpResult? = null
                override fun continuationBasis(model: LpModel) = Basis(intArrayOf(), arrayOf(VarStatus.AT_LOWER))
            }
            val policy = LpCertificationPolicy { route, success -> success && route != rejected }

            val result = certifyLpResult(model, solver, null, policy = policy)

            if (rejected == LpCertifier.INTEGER) {
                assertEquals(LpVerdict.FEASIBLE, result.verdict)
                assertNotNull(result.witness)
                assertNull(result.bound)
            } else {
                assertEquals(LpVerdict.CERTIFIED_BOUND, result.verdict)
                assertNull(result.witness)
                assertEquals(BigFraction.ZERO, result.lowerBound)
                assertEquals(0L, result.integerObjectiveLowerBound)
            }
        }
    }

    @Test
    fun `source conflict support aggregates repeated rows before selecting strict sides`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val premise = ExactLpPremises(emptyList(), listOf(13))
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one)), listOf(ExactLpEntry(1, one))),
            listOf(zero, zero),
            List(4) { column ->
                ExactLpColumn(
                    ExactLpBounds(ExactLpSide(zero, strict = column == 0, premises = premise)),
                    integral = false,
                )
            },
            List(2) { ExactLpRow() },
            ExactLpObjective(List(4) { zero }),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        for (second in listOf(1L, -1L)) {
            val proof = BigRationalConflict(
                intArrayOf(0, 0, 1, 1),
                listOf(1L, 1L, 1L, second).map(BigFraction::ofLong),
                List(4) { ExactSimplexBound(it, false) },
            )
            assertTrue(checkedLpConflict(model, proof))

            val support = assertNotNull(model.exactConflictSupport(proof))

            assertEquals(if (second == 1L) listOf(0, 1) else listOf(0), support.rows.map { it.first })
            assertEquals(if (second == 1L) listOf(0, 1, 2, 3) else listOf(0, 2), support.sides.map { it.column })
            assertTrue(support.sides.first().side.strict)
            assertEquals(premise, support.sides.first().side.premises)
        }
    }

    @Test
    fun `crossed bound witnesses honor proof acceptance policy`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(upper = ExactLpSide(zero)))),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val trail = LpBoundTrail(source)
        assertTrue(trail.assertBound(0, false, ExactLpSide(zero, strict = true), 7L))
        val working = assertNotNull(trail.state.toWorkingModel())
        val decline = LpSolveContext(certificationPolicy = LpCertificationPolicy { _, _ -> false })

        val accepted = solveAndCertify(working)
        val rejected = solveAndCertify(working, context = decline)

        assertEquals(LpVerdict.INFEASIBLE, accepted.verdict)
        assertEquals(7L, assertNotNull(accepted.boundConflict).lower.witness)
        assertEquals(-2L, accepted.boundConflict.upper.witness)
        assertEquals(trail.state, assertNotNull(accepted.conflictSupport).state)
        assertEquals(LpVerdict.INDETERMINATE, rejected.verdict)
        assertNull(rejected.boundConflict)
        assertNull(rejected.conflictSupport)
    }

    @Test
    fun `exact objective units and source origins survive certification`() {
        val third = BigFraction.of(BIG_ONE, bigIntOf(3))
        val model = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(
                ExactLpColumn(
                    ExactLpBounds(ExactLpSide(ExactLpNumber.of(third)), ExactLpSide(ExactLpNumber.of(2L))),
                    origin = ExactLpNumber.of(10L),
                    integral = false,
                    tag = 17,
                ),
            ),
            emptyList(),
            ExactLpObjective(
                listOf(ExactLpNumber.of(6L)),
                constant = ExactLpNumber.of(9L),
                scale = ExactLpNumber.of(3L),
                externalConstant = ExactLpNumber.of(4L),
                sense = Sense.MAXIMIZE,
            ),
        )

        val result = solveAndCertify(model)

        assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
        assertEquals(BigFraction.ofLong(10L) + third, assertNotNull(result.exactPrimal).single())
        assertEquals(BigFraction.ofLong(7L) + third + third, result.lowerBound)
        assertNull(result.integerObjectiveLowerBound)
        assertNull(result.certificate)
        assertEquals(Sense.MAXIMIZE, assertNotNull(result.bound?.support).state.model.objective.sense)
    }

    @Test
    fun `binary and parsed equations retain different acceptance authority`() {
        val tenth = ExactLpNumber.of(BigFraction.of(BIG_ONE, bigIntOf(10)))
        val zero = ExactLpNumber.of(0L)
        for (rhs in listOf(tenth, ExactLpNumber.ofIeee(0.1))) {
            val model = ExactLpModel(
                listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(1L)))),
                listOf(rhs),
                listOf(
                    ExactLpColumn(ExactLpBounds(ExactLpSide(tenth), ExactLpSide(tenth))),
                    ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))),
                ),
                listOf(ExactLpRow()),
                ExactLpObjective(listOf(zero, zero)),
            )

            val result = solveAndCertify(model)

            if (rhs == tenth) {
                assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
                assertEquals(listOf(tenth.value), result.exactPrimal)
            } else {
                assertNull(result.witness)
                assertTrue(result.verdict != LpVerdict.ATTAINED_OPTIMUM)
            }
        }
    }

    @Test
    fun `a feasible LP certifies an optimum matching the float optimum`() {
        // minimize x + y  subject to  x + y >= 3,  0 <= x, y <= 5.
        val b = LpBuilder()
        b.addVar(0L, 5L, cost = 1L)
        b.addVar(0L, 5L, cost = 1L)
        b.addRow(intArrayOf(0, 1), longArrayOf(1L, 1L), Relation.GE, 3L)
        val model = b.build(Sense.MINIMIZE)

        val result = solveAndCertify(model)

        assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
        assertEquals(BigFraction.ofLong(3L), result.lowerBound)
        assertNotNull(checkedLpWitness(model, assertNotNull(result.exactPrimal)))
        assertNull(result.farkasRay)
        assertEquals(3L, result.integerObjectiveLowerBound)
        val float = assertNotNull(result.float)
        val safe = assertNotNull(result.safeLowerBound)
        assertTrue(safe <= float.objective + 1e-6, "safe bound $safe exceeds the optimum ${float.objective}")
    }

    @Test
    fun `an infeasible LP is certified infeasible by a Farkas ray`() {
        // 0 <= x <= 1 with x >= 2 has no feasible point.
        val b = LpBuilder()
        b.addVar(0L, 1L, cost = 1L)
        b.addRow(intArrayOf(0), longArrayOf(1L), Relation.GE, 2L)
        val model = b.build(Sense.MINIMIZE)

        val result = solveAndCertify(model)

        assertEquals(LpVerdict.INFEASIBLE, result.verdict)
        assertTrue(checkedLpConflict(model, assertNotNull(result.rationalConflict)))
        assertNull(result.witness)
        assertNull(result.lowerBound)
    }

    @Test
    fun `a nonoptimal feasible point retains its exact witness and a separate bound`() {
        val model = LpBuilder().apply { addVar(0L, 5L, cost = 1L) }.build(Sense.MINIMIZE)
        val hint = FloatLpResult(
            Basis(intArrayOf(), arrayOf(VarStatus.AT_UPPER)),
            0.0,
            doubleArrayOf(),
            doubleArrayOf(5.0),
        )

        val result = newLpSolver(model).use { certifyLpResult(model, it, hint) }

        assertEquals(LpVerdict.FEASIBLE, result.verdict)
        assertEquals(listOf(BigFraction.ofLong(5L)), result.exactPrimal)
        assertEquals(BigFraction.ofLong(5L), result.witness?.objective)
        assertEquals(BigFraction.ZERO, result.lowerBound)
    }

    @Test
    fun `a bound on an infeasible relaxation proves neither a point nor attainment`() {
        val model = LpBuilder().apply {
            val x = addVar(0L, 1L, cost = 1L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 2L)
        }.build(Sense.MINIMIZE)
        val hint = FloatLpResult(
            Basis(intArrayOf(1), arrayOf(VarStatus.AT_LOWER, VarStatus.BASIC)),
            2.0,
            doubleArrayOf(-1.0),
            doubleArrayOf(0.0),
        )

        val result = newLpSolver(model).use { certifyLpResult(model, it, hint) }

        assertEquals(LpVerdict.CERTIFIED_BOUND, result.verdict)
        assertEquals(BigFraction.ofLong(2L), result.lowerBound)
        assertNull(result.exactPrimal)
        assertEquals(LpVerdict.INFEASIBLE, solveAndCertify(model).verdict)
    }

    @Test
    fun `a strict infimum retains an interior witness without claiming attainment`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 1.0, cost = 1.0)
            addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 0.0, strict = true)
        }.build(Sense.MINIMIZE)

        val result = solveAndCertify(model)
        val point = assertNotNull(result.exactPrimal).single()

        assertTrue(point > BigFraction.ZERO && point <= BigFraction.ONE)
        assertEquals(point, result.witness?.objective)
        assertEquals(BigFraction.ZERO, result.lowerBound)
        assertEquals(LpVerdict.FEASIBLE, result.verdict)
    }

    @Test
    fun `an exact feasible counter suppresses a repeated infeasibility candidate`() {
        val model = LpBuilder().apply { addVar(0L, 1L) }.build(Sense.MINIMIZE)
        val cache = LpCounterResults()
        val first = solveAndCertify(model, counterResults = cache)
        val repeated = object : LpSolver {
            override val infeasibleRay: DoubleArray? get() = error("an exact point already refutes this claim")
            override fun solve(warm: Basis?): FloatLpResult? = null
            override fun solvePrimal(warm: Basis?): FloatLpResult? = null
        }

        val result = certifyLpResult(model, repeated, null, Cancellation { true }, counterResults = cache)

        assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
        assertEquals(first.exactPrimal, result.exactPrimal)
        assertEquals(BigFraction.ZERO, result.witness?.objective)
    }

    @Test
    fun `objective replacement releases a retained bound for unboundedness`() {
        val model = LpBuilder().apply { addOpenAboveVar(0L) }.build(Sense.MINIMIZE)
        val cache = LpCounterResults()
        val first = solveAndCertify(model, counterResults = cache)
        assertEquals(LpVerdict.ATTAINED_OPTIMUM, first.verdict)
        assertNotNull(cache.read(model, ProductionLpCertificationPolicy)?.bound)
        val next = model.withSingleColumnObjective(0, -1L)
        val solver = object : LpSolver {
            override val infeasibleRay: DoubleArray? = null
            override val recessionDirection = doubleArrayOf(1.0)
            override fun solve(warm: Basis?): FloatLpResult? = null
            override fun solvePrimal(warm: Basis?): FloatLpResult? = null
            override fun continuationBasis(model: LpModel) = Basis(intArrayOf(), arrayOf(VarStatus.AT_LOWER))
        }

        val result = certifyLpResult(next, solver, null, counterResults = cache)

        assertEquals(LpVerdict.UNBOUNDED, result.verdict)
        val proof = assertNotNull(result.unboundedness)
        assertNotNull(checkedLpWitness(next, proof.witness.primal))
        assertTrue(proof.direction.single() > BigFraction.ZERO)
        assertNull(result.lowerBound)
    }

    @Test
    fun `sibling bounds cannot reuse an exact point from another node`() {
        val model = LpBuilder().apply {
            val x = addVar(0L, 3L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 2L)
        }.build(Sense.MINIMIZE)
        val cache = LpCounterResults()
        assertNotNull(solveAndCertify(model, counterResults = cache).witness)
        val sibling = model.rebind(longArrayOf(0L), longArrayOf(1L))

        assertNull(cache.read(sibling, ProductionLpCertificationPolicy))
        assertEquals(LpVerdict.INFEASIBLE, solveAndCertify(sibling, counterResults = cache).verdict)
    }

    @Test
    fun `unboundedness requires a feasible point and an improving recession direction`() {
        val model = LpBuilder().apply { addOpenAboveVar(0L, cost = -1L) }.build(Sense.MINIMIZE)
        val solver = object : LpSolver {
            override fun continuationBasis(model: LpModel): Basis = Basis(intArrayOf(), arrayOf(VarStatus.AT_LOWER))
            override val infeasibleRay: DoubleArray? = null
            override val recessionDirection = doubleArrayOf(1.0)
            override fun solve(warm: Basis?): FloatLpResult? = null
            override fun solvePrimal(warm: Basis?): FloatLpResult? = null
        }

        val result = certifyLpResult(model, solver, null)
        val proof = assertNotNull(result.unboundedness)

        assertEquals(LpVerdict.UNBOUNDED, result.verdict)
        assertTrue(proof.witness.primal.single() >= BigFraction.ZERO)
        assertTrue(proof.direction.single() > BigFraction.ZERO)
        assertTrue(BigFraction.MINUS_ONE * proof.direction.single() < BigFraction.ZERO)
        assertNull(checkedLpUnboundedness(model, proof.witness, doubleArrayOf(-1.0)))
    }

    @Test
    fun `a ray over guessed decimal rows cannot refute the original source system`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, null)
            val y = addRealVar(0.0, null)
            addRealRow(intArrayOf(x, y), doubleArrayOf(1.0000000001, -1.0), Relation.EQ, 1.0)
            addRealRow(intArrayOf(x, y), doubleArrayOf(1.0, -1.0), Relation.EQ, 0.0)
        }.build(Sense.MINIMIZE)
        val candidate = doubleArrayOf(1.0, -1.0)
        val solver = object : LpSolver {
            override fun continuationBasis(model: LpModel): Basis = Basis(
                intArrayOf(2, 3),
                arrayOf(VarStatus.AT_LOWER, VarStatus.AT_LOWER, VarStatus.BASIC, VarStatus.BASIC),
            )
            override val infeasibleRay = candidate
            override fun solve(warm: Basis?): FloatLpResult? = null
            override fun solvePrimal(warm: Basis?): FloatLpResult? = null
        }

        assertNull(certifyLpFarkas(model, candidate))
        val result = certifyLpResult(model, solver, null)
        val point = assertNotNull(result.exactPrimal)

        assertEquals(LpVerdict.FEASIBLE, result.verdict)
        assertTrue(point.all { it >= BigFraction.ZERO })
        assertEquals(point[0], point[1])
        assertEquals(BigFraction.ONE, checkNotNull(BigFraction.ofDouble(1.0000000001)) * point[0] - point[1])
    }

    @Test
    fun `strict infeasibility carries exact row multipliers and all blocking sides`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 1.0)
            addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.LE, 0.0, strict = true)
        }.build(Sense.MINIMIZE)

        val result = solveAndCertify(model)
        val conflict = assertNotNull(result.rationalConflict)

        assertEquals(LpVerdict.INFEASIBLE, result.verdict)
        assertEquals(listOf(0), conflict.rows.toList())
        assertEquals(listOf(BigFraction.ONE), conflict.multipliers)
        assertTrue(conflict.bounds.any { it.column == 0 && !it.upper })
        assertTrue(conflict.bounds.any { it.column == 1 && !it.upper })
        assertTrue(checkedLpConflict(model, conflict))
    }

    @Test
    fun `policy rejection withholds all reconstructed proof packages`() {
        val model = LpBuilder().apply { addVar(0L, 1L, cost = 1L) }.build(Sense.MINIMIZE)
        val reject = LpSolveContext(certificationPolicy = LpCertificationPolicy { _, _ -> false })

        val result = solveAndCertify(model, context = reject)

        assertEquals(LpVerdict.INDETERMINATE, result.verdict)
        assertNull(result.witness)
        assertNull(result.bound)
        assertTrue(assertNotNull(result.reconstruction).pointSuccesses > 0)
        assertTrue(result.reconstruction.dualSuccesses > 0)
    }

    @Test
    fun `completed exact reconstruction survives cancellation after policy acceptance`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)))),
            emptyList(),
            ExactLpObjective(listOf(one)),
        )
        var cancelled = false
        val context = LpSolveContext(
            certificationPolicy = LpCertificationPolicy { route, success ->
                if (route == LpCertifier.RATIONAL && success) cancelled = true
                success
            },
        )

        val state = LpExactState(source)
        val candidate = FloatLpResult(
            Basis(intArrayOf(), arrayOf(VarStatus.AT_LOWER)),
            0.0,
            doubleArrayOf(),
            doubleArrayOf(0.0),
            exactState = state,
        )
        val solver = object : LpSolver {
            override val solvedExactState = state
            override val infeasibleRay: DoubleArray? = null
            override fun solve(warm: Basis?) = candidate
            override fun solvePrimal(warm: Basis?) = candidate
        }
        val result = certifyLpResult(
            assertNotNull(state.toWorkingModel()),
            solver,
            candidate,
            Cancellation { cancelled },
            policy = context.certificationPolicy,
        )

        assertTrue(cancelled)
        assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
        assertEquals(BigFraction.ZERO, result.lowerBound)
        assertEquals(listOf(BigFraction.ZERO), result.exactPrimal)
        assertNotNull(result.bound?.support)
    }

    @Test
    fun `stale exact candidate cannot enter reconstruction after a trail edit`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)))),
            emptyList(),
            ExactLpObjective(listOf(one)),
        )
        val trail = LpBoundTrail(source)
        val old = trail.state
        val candidate = FloatLpResult(
            Basis(intArrayOf(), arrayOf(VarStatus.AT_LOWER)),
            0.0,
            doubleArrayOf(),
            doubleArrayOf(0.0),
            exactState = old,
        )
        val solver = object : LpSolver {
            override val solvedExactState = old
            override val infeasibleRay: DoubleArray? = null
            override fun solve(warm: Basis?) = candidate
            override fun solvePrimal(warm: Basis?) = candidate
        }
        assertTrue(trail.assertBound(0, false, ExactLpSide(one), 5L))

        val result = certifyLpResult(assertNotNull(trail.state.toWorkingModel()), solver, candidate)

        assertEquals(LpVerdict.INDETERMINATE, result.verdict)
        assertNull(result.reconstruction)
        assertNull(result.witness)
    }

}
