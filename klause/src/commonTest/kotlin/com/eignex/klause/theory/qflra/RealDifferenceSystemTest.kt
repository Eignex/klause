package com.eignex.klause.theory.qflra

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.linearRows
import com.eignex.klause.lp.exactForm
import com.eignex.klause.model.PbOp
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RealDifferenceSystemTest {
    @Test
    fun `a rational strict cycle should imply either signed guard without assigning unused real columns`() {
        val third = BigFraction.ofLong(3).reciprocal()
        for (truth in listOf(false, true)) {
            val model = Problem(1, 0, emptyArray(), arrayOf(
                ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(2)),
                    listOf(BigFraction.ONE, BigFraction.MINUS_ONE), LinearOp.LE, third),
                ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(2)),
                    listOf(BigFraction.MINUS_ONE, BigFraction.ONE),
                    if (truth) LinearOp.GE else LinearOp.LE, third.negated(), strict = !truth, activator = 0),
            ), numRealVars = 3, realLower = DoubleArray(3) { Double.NEGATIVE_INFINITY },
                realUpper = DoubleArray(3) { Double.POSITIVE_INFINITY })
            val forms = model.factors.map { factor -> factor.linearRows.map { it.exactForm(3) } }
            val system = assertNotNull(RealDifferenceSystem.prepare(model, forms))
            val context = SearchSession(emptyList())
            assertIs<RealDifferenceSystem.Result.Feasible>(system.check(intArrayOf(-1), Cancellation.Never))

            val result = system.implyGuards(context, Cancellation.Never) {}

            assertIs<ComponentResult.Consistent>(result)
            assertEquals(truth, context.boolValue(0))
            assertContentEquals(intArrayOf(Lit.make(0, truth)), assertNotNull(context.reasonFor(0)).literals)
        }
    }

    @Test
    fun `a strict cycle with positive rational slack should leave its guard open`() {
        val model = Problem(1, 0, emptyArray(), arrayOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1)),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE), LinearOp.LE,
                BigFraction.ofLong(3).reciprocal(), strict = true),
            ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1)),
                listOf(BigFraction.MINUS_ONE, BigFraction.ONE), LinearOp.LE,
                BigFraction.ofLong(6).reciprocal().negated(), activator = 0),
        ), numRealVars = 2, realLower = DoubleArray(2) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY })
        val forms = model.factors.map { factor -> factor.linearRows.map { it.exactForm(2) } }
        val system = assertNotNull(RealDifferenceSystem.prepare(model, forms))
        val context = SearchSession(emptyList())
        assertIs<RealDifferenceSystem.Result.Feasible>(system.check(intArrayOf(-1), Cancellation.Never))

        assertIs<ComponentResult.Consistent>(system.implyGuards(context, Cancellation.Never) {})

        assertNull(context.boolValue(0))
        for (value in intArrayOf(0, 1)) {
            assertIs<RealDifferenceSystem.Result.Feasible>(system.check(intArrayOf(value), Cancellation.Never))
        }
    }

    @Test
    fun `an interrupted guard sweep should withhold its consequence and permit a later check`() {
        val model = Problem(1, 0, emptyArray(), arrayOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE), LinearOp.LE, BigFraction.ZERO),
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE), LinearOp.GE,
                BigFraction.ONE, activator = 0),
        ), numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val forms = model.factors.map { factor -> factor.linearRows.map { it.exactForm(1) } }
        val system = assertNotNull(RealDifferenceSystem.prepare(model, forms))
        assertIs<RealDifferenceSystem.Result.Feasible>(system.check(intArrayOf(-1), Cancellation.Never))
        assertIs<ComponentResult.Consistent>(system.implyGuards(SearchSession(emptyList()), Cancellation.Never) {})
        system.retractGuards()
        assertIs<RealDifferenceSystem.Result.Feasible>(system.check(intArrayOf(-1), Cancellation.Never))
        val context = SearchSession(emptyList())
        var work = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = work > 0L
            override fun workMeter(): WorkMeter = WorkMeter { work += it }
        }

        assertIs<ComponentResult.Indeterminate>(system.implyGuards(context, stop) {})

        assertTrue(work > 0L)
        assertNull(context.boolValue(0))
        assertIs<RealDifferenceSystem.Result.Feasible>(system.check(intArrayOf(-1), Cancellation.Never))
        assertIs<ComponentResult.Consistent>(system.implyGuards(context, Cancellation.Never) {})
        assertEquals(false, context.boolValue(0))
    }

    @Test
    fun `a necessary difference cycle should cite only its original rows`() {
        for (strict in listOf(false, true)) {
            val rows = listOf(
                ExactRationalInequality(intArrayOf(1, 2),
                    listOf(BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ofLong(10)),
                ExactRationalInequality(intArrayOf(0, 1),
                    listOf(BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ZERO),
                ExactRationalInequality(intArrayOf(0, 1, 2), List(3) { BigFraction.ONE }, BigFraction.ZERO),
                ExactRationalInequality(intArrayOf(0, 1),
                    listOf(BigFraction.MINUS_ONE, BigFraction.ONE),
                    if (strict) BigFraction.ZERO else BigFraction.MINUS_ONE, strict = strict),
            )
            var support: IntArray? = null

            val refuted = RealDifferenceSystem.refutesSubset(3, rows, Cancellation.Never) { support = it }

            assertTrue(refuted)
            val selected = assertNotNull(support)
            assertEquals(setOf(1, 3), selected.toSet())
            assertTrue(RealDifferenceSystem.refutesSubset(3, selected.map(rows::get), Cancellation.Never))
        }
    }

    @Test
    fun `a valid general row should not hide a necessary difference refutation`() {
        for (strict in listOf(false, true)) {
            val rows = listOf(
                ExactRationalInequality(intArrayOf(0, 1),
                    listOf(BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ZERO),
                ExactRationalInequality(intArrayOf(0, 1),
                    listOf(BigFraction.MINUS_ONE, BigFraction.ONE),
                    if (strict) BigFraction.ZERO else BigFraction.MINUS_ONE, strict = strict),
                ExactRationalInequality(intArrayOf(0, 1, 2), List(3) { BigFraction.ONE }, BigFraction.ZERO),
            )

            val refuted = RealDifferenceSystem.refutesSubset(3, rows, Cancellation.Never)

            assertTrue(refuted)
            assertNull(RealDifferenceSystem.prepareRows(3, rows, Cancellation.Never))
        }
    }

    @Test
    fun `a feasible necessary difference subset should supply no refutation`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 1),
                listOf(BigFraction.MINUS_ONE, BigFraction.ONE), BigFraction.ONE),
            ExactRationalInequality(intArrayOf(0, 1, 2), List(3) { BigFraction.ONE }, BigFraction.ZERO),
        )

        val refuted = RealDifferenceSystem.refutesSubset(3, rows, Cancellation.Never)

        assertFalse(refuted)
    }

    @Test
    fun `an invalid tail should withhold a necessary difference refutation`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 1),
                listOf(BigFraction.MINUS_ONE, BigFraction.ONE), BigFraction.MINUS_ONE),
            ExactRationalInequality(intArrayOf(3), listOf(BigFraction.ONE), BigFraction.ZERO),
        )

        val refuted = RealDifferenceSystem.refutesSubset(3, rows, Cancellation.Never)

        assertFalse(refuted)
    }

    @Test
    fun `an interrupted necessary difference check should retain work without a refutation`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 1),
                listOf(BigFraction.MINUS_ONE, BigFraction.ONE), BigFraction.MINUS_ONE),
        )
        var spent = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = spent >= 8L
            override fun workMeter(): WorkMeter = WorkMeter { spent += it }
        }

        val refuted = RealDifferenceSystem.refutesSubset(3, rows, stop)

        assertFalse(refuted)
        assertTrue(spent >= 8L)
        assertTrue(RealDifferenceSystem.refutesSubset(3, rows, Cancellation.Never))
    }

    @Test
    fun `a necessary source cycle should retain its signed guards across siblings`() {
        for (strict in listOf(false, true)) {
            val model = Problem(2, 0, emptyArray(), arrayOf(
                ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1)),
                    listOf(BigFraction.ONE, BigFraction.MINUS_ONE), LinearOp.LE, BigFraction.ZERO, activator = 0),
                ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1)),
                    listOf(BigFraction.MINUS_ONE, BigFraction.ONE), LinearOp.LE,
                    if (strict) BigFraction.ZERO else BigFraction.MINUS_ONE, strict = strict, activator = 1),
                ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1), Term.ofRealVar(424)),
                    List(3) { BigFraction.ONE }, LinearOp.LE, BigFraction.ofLong(100)),
            ), numRealVars = 425, realLower = DoubleArray(425) { Double.NEGATIVE_INFINITY },
                realUpper = DoubleArray(425) { Double.POSITIVE_INFINITY })
            val forms = model.factors.map { factor -> factor.linearRows.map { it.exactForm(425) } }
            val subset = assertNotNull(RealDifferenceSystem.prepareNecessary(model, forms, Cancellation.Never))

            val conflict = assertNotNull(subset.conflict(intArrayOf(1, 1), Cancellation.Never))

            assertEquals(setOf(Lit.make(0, false), Lit.make(1, false)), assertNotNull(conflict.clause).toSet())
            assertNull(subset.conflict(intArrayOf(1, 0), Cancellation.Never))
            assertNotNull(subset.conflict(intArrayOf(1, 1), Cancellation.Never))
            assertNull(RealDifferenceSystem.prepare(model, forms))
        }
    }

    @Test
    fun `feasible source differences should supply no verdict for contradictory general rows`() {
        val model = Problem(0, 0, emptyArray(), arrayOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1)),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE), LinearOp.LE, BigFraction.ZERO),
            ExactLinear.of(IntArray(3) { Term.ofRealVar(it) }, List(3) { BigFraction.ONE },
                LinearOp.LE, BigFraction.ZERO),
            ExactLinear.of(IntArray(3) { Term.ofRealVar(it) }, List(3) { BigFraction.ONE },
                LinearOp.GE, BigFraction.ONE),
        ), numRealVars = 3, realLower = DoubleArray(3) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(3) { Double.POSITIVE_INFINITY })
        val forms = model.factors.map { factor -> factor.linearRows.map { it.exactForm(3) } }
        val subset = assertNotNull(RealDifferenceSystem.prepareNecessary(model, forms, Cancellation.Never))

        val conflict = subset.conflict(intArrayOf(), Cancellation.Never)

        assertNull(conflict)
    }

    @Test
    fun `an unselected equality should contribute no necessary disequality direction`() {
        val model = Problem(1, 0, emptyArray(), arrayOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1)),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE), LinearOp.EQ, BigFraction.ZERO, activator = 0),
            ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1)),
                listOf(BigFraction.MINUS_ONE, BigFraction.ONE), LinearOp.LE, BigFraction.MINUS_ONE),
        ), numRealVars = 2, realLower = DoubleArray(2) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY })
        val forms = model.factors.map { factor -> factor.linearRows.map { it.exactForm(2) } }
        val subset = assertNotNull(RealDifferenceSystem.prepareNecessary(model, forms, Cancellation.Never))

        val sibling = subset.conflict(intArrayOf(0), Cancellation.Never)

        assertNull(sibling)
        assertEquals(setOf(Lit.make(0, false)),
            assertNotNull(assertNotNull(subset.conflict(intArrayOf(1), Cancellation.Never)).clause).toSet())
    }

    @Test
    fun `cancelled necessary source preparation should retain work and permit a fresh retry`() {
        val model = Problem(0, 0, emptyArray(), arrayOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                LinearOp.LE, BigFraction.ZERO),
        ), numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val forms = model.factors.map { factor -> factor.linearRows.map { it.exactForm(1) } }
        var spent = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = spent >= 2L
            override fun workMeter(): WorkMeter = WorkMeter { spent += it }
        }

        val stopped = RealDifferenceSystem.prepareNecessary(model, forms, stop)

        assertNull(stopped)
        assertTrue(spent >= 2L)
        assertNotNull(RealDifferenceSystem.prepareNecessary(model, forms, Cancellation.Never))
    }

    @Test
    fun `a stopped necessary source sweep should withhold its conflict and permit a fresh retry`() {
        val model = Problem(0, 0, emptyArray(), arrayOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                LinearOp.LE, BigFraction.ZERO),
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                LinearOp.GE, BigFraction.ONE),
        ), numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val forms = model.factors.map { factor -> factor.linearRows.map { it.exactForm(1) } }
        val subset = assertNotNull(RealDifferenceSystem.prepareNecessary(model, forms, Cancellation.Never))
        var spent = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = spent > 0L
            override fun workMeter(): WorkMeter = WorkMeter { spent += it }
        }

        val stopped = subset.conflict(intArrayOf(), stop)

        assertNull(stopped)
        assertTrue(spent > 0L)
        assertTrue(assertNotNull(assertNotNull(subset.conflict(intArrayOf(), Cancellation.Never)).clause).isEmpty())
    }

    @Test
    fun `fractional strict bounds should retain an exact interior witness`() {
        val model = Problem(
            0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
            factors = arrayOf(
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.LE, 0.0, true),
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(2.0), LinearOp.LE, 1.0, true),
            ),
        )
        val system = assertNotNull(
            RealDifferenceSystem.prepare(
                model,
                model.factors.map { factor -> factor.linearRows.map { it.exactForm(1) } },
            ),
        )

        val point = assertIs<RealDifferenceSystem.Result.Feasible>(
            system.check(intArrayOf(), Cancellation.Never),
        ).point.single()

        assertTrue(point > BigFraction.ZERO)
        assertTrue(point < BigFraction.of(bigIntOf(1), bigIntOf(2)))
    }

    @Test
    fun `fractional equality should retain its exact source coordinate`() {
        for (coefficient in listOf(3.0, -3.0)) {
            val model = Problem(
                0,
                intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
                numRealVars = 1,
                realLower = doubleArrayOf(-0.5),
                realUpper = doubleArrayOf(0.5),
                factors = arrayOf(
                    Linear(
                        intArrayOf(),
                        doubleArrayOf(),
                        intArrayOf(0),
                        doubleArrayOf(coefficient),
                        LinearOp.EQ,
                        1.0,
                    ),
                ),
            )
            val system = assertNotNull(
                RealDifferenceSystem.prepare(
                    model,
                    model.factors.map { factor -> factor.linearRows.map { it.exactForm(1) } },
                ),
            )

            val point = assertIs<RealDifferenceSystem.Result.Feasible>(
                system.check(intArrayOf(), Cancellation.Never),
            ).point.single()

            assertEquals(BigFraction.of(bigIntOf(if (coefficient > 0) 1 else -1), bigIntOf(3)), point)
        }
    }

    @Test
    fun `fractional strict cycles should preserve positive slack and guarded conflicts`() {
        for (reverse in listOf(-0.5, -0.25)) {
            val model = Problem(
                2,
                intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
                numRealVars = 2,
                realLower = DoubleArray(2) { Double.NEGATIVE_INFINITY },
                realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY },
                factors = arrayOf(
                    ReifiedRealLinear(
                        0,
                        intArrayOf(),
                        doubleArrayOf(),
                        intArrayOf(0, 1),
                        doubleArrayOf(1.0, -1.0),
                        LinearOp.LE,
                        0.5,
                        true,
                    ),
                    ReifiedRealLinear(
                        1,
                        intArrayOf(),
                        doubleArrayOf(),
                        intArrayOf(1, 0),
                        doubleArrayOf(1.0, -1.0),
                        LinearOp.LE,
                        reverse,
                    ),
                ),
            )
            val system = assertNotNull(
                RealDifferenceSystem.prepare(
                    model,
                    model.factors.map { factor -> factor.linearRows.map { it.exactForm(2) } },
                ),
            )

            val result = system.check(intArrayOf(1, 1), Cancellation.Never)

            if (reverse == -0.5) {
                val conflict = assertIs<RealDifferenceSystem.Result.Infeasible>(result)
                assertContentEquals(intArrayOf(Lit.make(0, false), Lit.make(1, false)), conflict.clause)
            } else {
                val point = assertIs<RealDifferenceSystem.Result.Feasible>(result).point
                assertTrue(point[0] - point[1] < BigFraction.of(bigIntOf(1), bigIntOf(2)))
                assertTrue(point[1] - point[0] <= BigFraction.of(bigIntOf(-1), bigIntOf(4)))
            }
            assertIs<RealDifferenceSystem.Result.Feasible>(system.check(intArrayOf(1, -1), Cancellation.Never))
            assertIs<RealDifferenceSystem.Result.Feasible>(system.check(intArrayOf(-1, 1), Cancellation.Never))
        }
    }

    @Test
    fun `fractional false guards should retain their strict complement`() {
        val model = Problem(
            1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(0.5),
            factors = arrayOf(
                ReifiedRealLinear(
                    0,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(2.0),
                    LinearOp.LE,
                    1.0,
                ),
            ),
        )
        val system = assertNotNull(
            RealDifferenceSystem.prepare(
                model,
                model.factors.map { factor -> factor.linearRows.map { it.exactForm(1) } },
            ),
        )

        val conflict = assertIs<RealDifferenceSystem.Result.Infeasible>(
            system.check(intArrayOf(0), Cancellation.Never),
        )

        assertContentEquals(intArrayOf(Lit.make(0, true)), conflict.clause)
        assertIs<RealDifferenceSystem.Result.Feasible>(system.check(intArrayOf(1), Cancellation.Never))
        assertIs<RealDifferenceSystem.Result.Feasible>(system.check(intArrayOf(-1), Cancellation.Never))
    }

    @Test
    fun `unsafe common denominators should decline optional graph preparation`() {
        for (coefficients in listOf(doubleArrayOf(3037000499.0, 3037000501.0), doubleArrayOf(4.0e18))) {
            val model = Problem(
                0,
                intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
                numRealVars = coefficients.size,
                realLower = DoubleArray(coefficients.size) { Double.NEGATIVE_INFINITY },
                realUpper = DoubleArray(coefficients.size) { Double.POSITIVE_INFINITY },
                factors = Array(coefficients.size) { column ->
                    Linear(
                        intArrayOf(),
                        doubleArrayOf(),
                        intArrayOf(column),
                        doubleArrayOf(coefficients[column]),
                        LinearOp.LE,
                        1.0,
                    )
                },
            )

            val system = RealDifferenceSystem.prepare(
                model,
                model.factors.map { factor -> factor.linearRows.map { it.exactForm(model.numRealVars) } },
            )

            assertNull(system)
        }
    }

    @Test
    fun `fractional preparation should retain its containing exhausted work allowance`() {
        val model = Problem(
            0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(0.5),
            factors = emptyArray(),
        )
        var spent = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = spent > 0L
            override fun workMeter(): WorkMeter = WorkMeter { spent += it }
        }

        val system = RealDifferenceSystem.prepare(model, emptyList(), stop)

        assertNull(system)
        assertTrue(spent > 0L)
        assertNull(RealDifferenceSystem.prepare(model, emptyList(), stop))
        assertNotNull(RealDifferenceSystem.prepare(model, emptyList()))
    }

    @Test
    fun `cached potentials should follow changed guard polarity`() {
        val model = Problem(
            1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
            factors = arrayOf(
                ReifiedRealLinear(
                    0,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.LE,
                    0.0,
                ),
            ),
        )
        val system = assertNotNull(
            RealDifferenceSystem.prepare(
                model,
                model.factors.map { factor -> factor.linearRows.map { it.exactForm(1) } },
            ),
        )

        for (truth in listOf(1, 0, 1)) {
            val point = assertIs<RealDifferenceSystem.Result.Feasible>(
                system.check(intArrayOf(truth), Cancellation.Never),
            ).point.single()

            assertTrue(if (truth == 1) point <= BigFraction.ZERO else point > BigFraction.ZERO)
        }
    }

    @Test
    fun `negative and strict cycles should require active guards`() {
        for (strict in listOf(false, true)) {
            val model = Problem(
                3,
                intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
                numRealVars = 2,
                realLower = DoubleArray(2) { Double.NEGATIVE_INFINITY },
                realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY },
                factors = arrayOf(
                    ReifiedRealLinear(
                        0,
                        intArrayOf(),
                        doubleArrayOf(),
                        intArrayOf(0, 1),
                        doubleArrayOf(1.0, -1.0),
                        LinearOp.LE,
                        0.0,
                        strict,
                    ),
                    ReifiedRealLinear(
                        1,
                        intArrayOf(),
                        doubleArrayOf(),
                        intArrayOf(1, 0),
                        doubleArrayOf(1.0, -1.0),
                        LinearOp.LE,
                        if (strict) 0.0 else -1.0,
                    ),
                    ReifiedRealLinear(
                        2,
                        intArrayOf(),
                        doubleArrayOf(),
                        intArrayOf(0),
                        doubleArrayOf(1.0),
                        LinearOp.LE,
                        100.0,
                    ),
                ),
            )
            val system = assertNotNull(
                RealDifferenceSystem.prepare(
                    model,
                    model.factors.map { factor -> factor.linearRows.map { it.exactForm(2) } },
                ),
            )

            val result = system.check(intArrayOf(1, 1, 1), Cancellation.Never)

            val conflict = assertIs<RealDifferenceSystem.Result.Infeasible>(result)
            assertContentEquals(intArrayOf(Lit.make(0, false), Lit.make(1, false)), conflict.clause)
            assertIs<RealDifferenceSystem.Result.Feasible>(system.check(intArrayOf(1, -1, 1), Cancellation.Never))
            assertIs<RealDifferenceSystem.Result.Feasible>(system.check(intArrayOf(-1, 1, 1), Cancellation.Never))
        }
    }

    @Test
    fun `contradictory source bounds should need no Boolean premise`() {
        val model = Problem(
            0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            numRealVars = 1,
            realLower = doubleArrayOf(1.0),
            realUpper = doubleArrayOf(0.0),
            factors = emptyArray(),
        )
        val system = assertNotNull(RealDifferenceSystem.prepare(model, emptyList()))

        val conflict = assertIs<RealDifferenceSystem.Result.Infeasible>(system.check(intArrayOf(), Cancellation.Never))

        assertContentEquals(intArrayOf(), conflict.clause)
    }

    @Test
    fun `cancellation after a negative sweep should withhold its conflict`() {
        val model = Problem(
            0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            numRealVars = 1,
            realLower = doubleArrayOf(1.0),
            realUpper = doubleArrayOf(0.0),
            factors = emptyArray(),
        )
        val system = assertNotNull(RealDifferenceSystem.prepare(model, emptyList()))
        var polls = 0

        val result = system.check(intArrayOf(), Cancellation { ++polls > 1 })

        assertIs<RealDifferenceSystem.Result.Interrupted>(result)
        assertIs<RealDifferenceSystem.Result.Infeasible>(system.check(intArrayOf(), Cancellation.Never))
    }

    @Test
    fun `unsupported bounds should decline without rounding`() {
        for (bound in listOf(Double.MIN_VALUE, 1.0e18, Double.MAX_VALUE)) {
            val model = Problem(
                0,
                intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
                numRealVars = 1,
                realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
                realUpper = doubleArrayOf(bound),
                factors = emptyArray(),
            )

            val system = RealDifferenceSystem.prepare(model, emptyList())

            assertNull(system)
        }
    }

    @Test
    fun `Boolean arithmetic should retain the exact LP route`() {
        val model = Problem(
            1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
            factors = arrayOf(PseudoBoolean(longArrayOf(2), intArrayOf(Lit.make(0, true)), PbOp.GE, 1L)),
        )

        val system = RealDifferenceSystem.prepare(
            model,
            model.factors.map { factor -> factor.linearRows.map { it.exactForm(1) } },
        )

        assertNull(system)
    }

    @Test
    fun `shared Boolean factors should leave difference arithmetic admissible`() {
        for (factor in listOf(
            Clause(intArrayOf(Lit.make(0, true))),
            Cardinality(intArrayOf(Lit.make(0, true)), 1, 1),
        )) {
            val model = Problem(
                1,
                intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
                numRealVars = 1,
                realLower = doubleArrayOf(0.0),
                realUpper = doubleArrayOf(1.0),
                factors = arrayOf(factor),
            )

            val system = RealDifferenceSystem.prepare(
                model,
                model.factors.map { source -> source.linearRows.map { it.exactForm(1) } },
            )

            assertNotNull(system)
        }
    }
}
