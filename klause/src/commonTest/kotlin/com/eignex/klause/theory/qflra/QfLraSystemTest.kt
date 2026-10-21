package com.eignex.klause.theory.qflra

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.linearRows
import com.eignex.klause.lp.bounding.LpEffortProfile
import com.eignex.klause.lp.bounding.LpPropagator
import com.eignex.klause.lp.bounding.LpSearchPolicy
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpPremises
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.exactForm
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchRealValue
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SourceBoundAtom
import com.eignex.klause.solver.search.SourceBoundTerm
import com.eignex.klause.solver.search.explainAtoms
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.shl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QfLraSystemTest {
    @Test
    fun `sparse shared definitions should preserve exact source equalities`() {
        val factors = arrayOf<Factor>(
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 2), doubleArrayOf(0.5, -1.5), LinearOp.EQ, -2.0),
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(2, 3), doubleArrayOf(2.0, 1.0), LinearOp.EQ, 1.0),
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 3), doubleArrayOf(1.0, 1.0), LinearOp.EQ, 4.5),
        )
        val source = Problem(
            0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            numRealVars = 4,
            realLower = DoubleArray(4) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(4) { Double.POSITIVE_INFINITY },
            factors = factors,
        )
        LpPropagator(object : LpSearchPolicy {}).use { lp ->
            val system = LiveQfLraSystem(source, lp)
            assertTrue(system.install())
            for (factor in factors) {
                val comparison = factor.linearRows.single().exactForm(4).comparison(true) { false }
                assertTrue(system.assertComparison(comparison, null, axiom))
            }

            val result = assertNotNull(lp.solve())

            assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
            val point = assertNotNull(result.exactPrimal)
            assertEquals(BigFraction.ofDouble(18.5), point[0])
            assertEquals(BigFraction.ofDouble(7.5), point[2])
            assertEquals(BigFraction.ofLong(-14), point[3])
        }
    }

    @Test
    fun `repeated and weaker rows should preserve the active bound and its premise`() {
        for (upper in listOf(false, true)) {
            LpPropagator(object : LpSearchPolicy {}).use { lp ->
                val system = LiveQfLraSystem(source(), lp)
                assertTrue(system.install())
                val session = SearchSession(listOf(lp))
                session.initialize()
                session.push(SearchDecision.Bool(Lit.make(0, true)))
                val coefficients = if (upper) listOf(1, 2) else listOf(-1, -2)
                val strong = row(coefficients, if (upper) 3 else -3)
                assertTrue(system.assertRow(strong, premise(0)))
                session.push(SearchDecision.Bool(Lit.make(1, true)))
                val edits = assertNotNull(lp.metrics).editAttempts

                repeat(3) {
                    assertTrue(system.assertRow(strong, premise(1)))
                    assertTrue(system.assertRow(row(coefficients, if (upper) 4 else -2), premise(1)))
                }

                assertEquals(edits, assertNotNull(lp.metrics).editAttempts)
                val active = assertNotNull(lp.state?.activeSide(2, upper))
                assertEquals(BigFraction.ofLong(3), active.side.number.value)
                assertEquals(
                    listOf(Lit.make(0, false)),
                    assertNotNull(session.explainAtoms(lp.boundPremise(active.witness))).literals.toList(),
                )
            }
        }
    }

    @Test
    fun `a weaker row should be asserted after rollback removes its stronger sibling`() {
        LpPropagator(object : LpSearchPolicy {}).use { lp ->
            val system = LiveQfLraSystem(source(), lp)
            assertTrue(system.install())
            val session = SearchSession(listOf(lp))
            session.initialize()
            session.push(SearchDecision.Bool(Lit.make(0, true)))
            assertTrue(system.assertRow(row(listOf(1, 2), 2), premise(0)))
            session.push(SearchDecision.Bool(Lit.make(1, true)))
            assertTrue(system.assertRow(row(listOf(1, 2), 3), premise(1)))
            session.popTo(0)
            session.push(SearchDecision.Bool(Lit.make(1, true)))

            assertTrue(system.assertRow(row(listOf(1, 2), 3), premise(1)))

            val active = assertNotNull(lp.state?.activeSide(2, true))
            assertEquals(BigFraction.ofLong(3), active.side.number.value)
            assertEquals(
                listOf(Lit.make(1, false)),
                assertNotNull(session.explainAtoms(lp.boundPremise(active.witness))).literals.toList(),
            )
        }
    }

    @Test
    fun `a strict row should strengthen an equal nonstrict bound on either side`() {
        for (upper in listOf(false, true)) {
            LpPropagator(object : LpSearchPolicy {}).use { lp ->
                val system = LiveQfLraSystem(source(), lp)
                assertTrue(system.install())
                assertTrue(lp.atLevel(1))
                val plain = row(if (upper) listOf(1) else listOf(-1), if (upper) 3 else -3)
                assertTrue(system.assertRow(plain, axiom))
                val edits = assertNotNull(lp.metrics).editAttempts
                val strict = ExactRationalInequality(plain.columns, plain.coefficients, plain.rhs, strict = true)

                assertTrue(system.assertRow(strict, axiom))
                assertTrue(system.assertRow(plain, axiom))

                assertEquals(edits + 1, assertNotNull(lp.metrics).editAttempts)
                assertTrue(assertNotNull(lp.state?.activeSide(0, upper)).side.strict)
                assertTrue(lp.atLevel(0))
                assertNull(lp.state?.activeSide(0, upper))
            }
        }
    }

    @Test
    fun `prepared comparisons should match fresh rows for every relation and complement`() {
        for (op in LinearOp.entries) {
            val factor = Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(1.0, 2.0), op, 3.0)
            val source = source().withFactors(arrayOf(factor))
            val form = factor.linearRows.single().exactForm(2)
            for (truth in listOf(false, true)) {
                val comparison = form.comparison(truth) { false }
                val directions = if (comparison.op == LinearOp.NE) listOf(LinearOp.LE, LinearOp.GE) else listOf(null)
                for (direction in directions) {
                    LpPropagator(object : LpSearchPolicy {}).use { retained ->
                        LpPropagator(object : LpSearchPolicy {}).use { fresh ->
                            val prepared = LiveQfLraSystem(source, retained, listOf(listOf(form)))
                            val rebuilt = LiveQfLraSystem(source, fresh)
                            assertTrue(prepared.install())
                            assertTrue(rebuilt.install())
                            val rows = ArrayList<ExactRationalInequality>()
                            comparison.rowsInto(rows, direction)

                            repeat(2) { assertTrue(prepared.assertComparison(comparison, direction, axiom)) }
                            for (row in rows) assertTrue(rebuilt.assertRow(row, axiom))

                            val actual = assertNotNull(retained.state).model
                            val expected = assertNotNull(fresh.state).model
                            assertEquals(expected.numVars, actual.numVars)
                            for (column in 0 until expected.numVars) {
                                assertEquals(expected.column(column).bounds, actual.column(column).bounds)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `a root fixing should bypass cached normalization and retain both fixing premises`() {
        val factor = Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(1.0, 1.0), LinearOp.LE, 3.0)
        val source = source().withFactors(arrayOf(factor))
        val form = factor.linearRows.single().exactForm(2)
        LpPropagator(object : LpSearchPolicy {}).use { lp ->
            val system = LiveQfLraSystem(source, lp, listOf(listOf(form)))
            assertTrue(system.install())
            val session = SearchSession(listOf(lp))
            session.initialize()
            for (variable in 0..2) session.publish(SearchDecision.Bool(Lit.make(variable, true)))
            val comparison = form.comparison(true) { false }
            assertTrue(system.assertComparison(comparison, null, premise(0)))
            assertTrue(lp.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2)), premise(1)))
            assertTrue(lp.assertBound(0, true, ExactLpSide(ExactLpNumber.of(2)), premise(2)))

            assertTrue(system.assertComparison(comparison, null, premise(0)))

            val active = assertNotNull(lp.state?.activeSide(1, true))
            assertEquals(BigFraction.ONE, active.side.number.value)
            assertEquals(
                (0..2).map { Lit.make(it, false) },
                assertNotNull(session.explainAtoms(lp.boundPremise(active.witness))).literals.sorted(),
            )
        }
    }

    @Test
    fun `registered negative rational sides should reuse a term across sibling scopes`() {
        LpPropagator(object : LpSearchPolicy {}).use { lp ->
            val system = LiveQfLraSystem(source(), lp)
            assertTrue(system.install())
            val session = SearchSession(listOf(lp), atoms = SearchAtomRegistry(0))
            session.initialize()
            val third = BigFraction.ofLong(3).reciprocal().negated()
            val atom = assertNotNull(
                SourceBoundAtom.rationalSplit(
                    session,
                    listOf(
                        SourceBoundTerm(SearchRealValue(0), third),
                        SourceBoundTerm(SearchRealValue(1), third * BigFraction.ofLong(2)),
                    ),
                    BigFraction.MINUS_ONE,
                ),
            )
            assertTrue(system.assertRow(row(listOf(1, 2), 2), axiom))
            for (side in listOf(atom.positive, atom.negative)) {
                session.popTo(0)
                session.push(SearchDecision.Theory(side))
                val asserted = SearchAtomPremise.Asserted(SearchDecision.Theory(side))
                assertTrue(system.assertAtom(side.payload as SourceBoundAtom, asserted))
                val expected = if (side == atom.positive) LpVerdict.INFEASIBLE else LpVerdict.ATTAINED_OPTIMUM
                assertEquals(expected, assertNotNull(lp.solve()).verdict)
            }
            assertEquals(1, assertNotNull(lp.state).model.m)
        }
    }

    @Test
    fun `declined definition appends should not publish a column mapping`() {
        LpPropagator(object : LpSearchPolicy {}, effort = { LpEffortProfile(maxRows = 0) }).use { lp ->
            val system = LiveQfLraSystem(source(), lp)
            assertTrue(system.install())

            repeat(2) { assertFalse(system.assertRow(row(listOf(1, 1), 1), axiom)) }

            assertEquals(0, assertNotNull(lp.state).model.m)
            assertTrue(system.assertRow(row(listOf(2), 2), axiom))
            assertEquals(LpVerdict.ATTAINED_OPTIMUM, assertNotNull(lp.solve()).verdict)
        }
    }

    @Test
    fun `signed rational atoms should share a definition while preserving distinct bounds`() {
        LpPropagator(object : LpSearchPolicy {}).use { lp ->
            val system = LiveQfLraSystem(source(), lp)
            assertTrue(system.install())
            val session = SearchSession(listOf(lp))
            session.initialize()
            assertTrue(system.assertRow(row(listOf(2, 4), 6), axiom))
            assertTrue(lp.atLevel(1))
            assertTrue(system.assertRow(row(listOf(-3, -6), -12), axiom))

            assertEquals(1, assertNotNull(lp.state).model.m)
            assertEquals(LpVerdict.INFEASIBLE, assertNotNull(lp.solve()).verdict)
            assertTrue(lp.atLevel(0))
            assertEquals(LpVerdict.ATTAINED_OPTIMUM, assertNotNull(lp.solve()).verdict)
            assertEquals(1, assertNotNull(lp.state).model.m)
        }
    }

    @Test
    fun `root substitution should keep both conditional fixing premises`() {
        LpPropagator(object : LpSearchPolicy {}).use { lp ->
            val system = LiveQfLraSystem(source(), lp)
            assertTrue(system.install())
            val session = SearchSession(listOf(lp))
            session.initialize()
            for (variable in 0..3) session.publish(SearchDecision.Bool(Lit.make(variable, true)))
            assertTrue(lp.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2)), premise(0)))
            assertTrue(lp.assertBound(0, true, ExactLpSide(ExactLpNumber.of(2)), premise(1)))
            assertTrue(system.assertRow(row(listOf(1, 1), 3), premise(2)))
            assertEquals(0, assertNotNull(lp.state).model.m)
            assertEquals(BigFraction.ONE, assertNotNull(lp.state?.activeSide(1, true)).side.number.value)
            assertTrue(
                system.assertRow(
                    ExactRationalInequality(intArrayOf(1), listOf(BigFraction.MINUS_ONE), BigFraction.ofLong(-2)),
                    premise(3),
                ),
            )

            val conflict = assertIs<ComponentResult.Conflict>(lp.propagate(session))

            assertEquals((0..3).map { Lit.make(it, false) }, assertNotNull(conflict.explanation).literals.sorted())
        }
    }

    @Test
    fun `nonroot fixings should not change permanent term identities`() {
        LpPropagator(object : LpSearchPolicy {}).use { lp ->
            val system = LiveQfLraSystem(source(), lp)
            assertTrue(system.install())
            assertTrue(lp.atLevel(1))
            assertTrue(lp.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2)), axiom))
            assertTrue(lp.assertBound(0, true, ExactLpSide(ExactLpNumber.of(2)), axiom))
            assertTrue(system.assertRow(row(listOf(1, 1), 3), axiom))
            assertEquals(1, assertNotNull(lp.state).model.m)
            assertTrue(lp.atLevel(0))
            assertTrue(system.assertRow(row(listOf(-2, -2), -8), axiom))

            val result = assertNotNull(lp.solve())

            assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
            val point = assertNotNull(result.exactPrimal)
            assertTrue(point[0] + point[1] >= BigFraction.ofLong(4))
            assertEquals(1, assertNotNull(lp.state).model.m)
        }
    }

    @Test
    fun `scoped fixed remainders should preserve signed rational bounds and strictness`() {
        for (strict in listOf(false, true)) {
            for (negative in listOf(false, true)) {
                LpPropagator(object : LpSearchPolicy {}).use { lp ->
                    val system = LiveQfLraSystem(source(), lp)
                    assertTrue(system.install())
                    assertTrue(lp.atLevel(1))
                    assertTrue(lp.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2)), axiom))
                    assertTrue(lp.assertBound(0, true, ExactLpSide(ExactLpNumber.of(2)), axiom))
                    val inequality = ExactRationalInequality(
                        intArrayOf(0, 1),
                        listOf(BigFraction.ofLong(2), BigFraction.ofLong(if (negative) -3 else 3)),
                        BigFraction.ofLong(5),
                        strict,
                    )

                    assertTrue(system.assertRow(inequality, axiom))

                    val active = assertNotNull(lp.state?.activeSide(1, !negative))
                    val third = BigFraction.ofLong(3).reciprocal()
                    assertEquals(if (negative) third.negated() else third, active.side.number.value)
                    assertEquals(strict, active.side.strict)
                }
            }
        }
    }

    @Test
    fun `a scoped remainder conflict should retain the row and both fixing premises`() {
        LpPropagator(object : LpSearchPolicy {}).use { lp ->
            val system = LiveQfLraSystem(source(), lp)
            assertTrue(system.install())
            val session = SearchSession(listOf(lp))
            session.initialize()
            session.push(SearchDecision.Bool(Lit.make(0, true)))
            assertTrue(lp.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2)), premise(0)))
            session.push(SearchDecision.Bool(Lit.make(1, true)))
            assertTrue(lp.assertBound(0, true, ExactLpSide(ExactLpNumber.of(2)), premise(1)))
            session.push(SearchDecision.Bool(Lit.make(2, true)))
            assertTrue(system.assertRow(row(listOf(1, 1), 3), premise(2)))
            session.push(SearchDecision.Bool(Lit.make(3, true)))
            assertTrue(lp.assertBound(1, false, ExactLpSide(ExactLpNumber.of(2)), premise(3)))

            val conflict = assertIs<ComponentResult.Conflict>(lp.propagate(session))

            assertEquals((0..3).map { Lit.make(it, false) }, assertNotNull(conflict.explanation).literals.sorted())
        }
    }

    @Test
    fun `a negative source scale should preserve the scoped lower bound`() {
        for (fixedColumn in 0..1) {
            LpPropagator(object : LpSearchPolicy {}).use { lp ->
                val system = LiveQfLraSystem(source(), lp)
                assertTrue(system.install())
                assertTrue(lp.atLevel(1))
                assertTrue(lp.assertBound(fixedColumn, false, ExactLpSide(ExactLpNumber.of(2)), axiom))
                assertTrue(lp.assertBound(fixedColumn, true, ExactLpSide(ExactLpNumber.of(2)), axiom))
                val coefficients = List(2) { if (it == fixedColumn) -2 else -3 }

                assertTrue(system.assertRow(row(coefficients, -5), axiom))

                assertEquals(
                    BigFraction.ofLong(3).reciprocal(),
                    lp.state?.activeSide(1 - fixedColumn, false)?.side?.number?.value,
                )
            }
        }
    }

    @Test
    fun `a sibling should derive its remainder from its own fixing`() {
        LpPropagator(object : LpSearchPolicy {}).use { lp ->
            val system = LiveQfLraSystem(source(), lp)
            assertTrue(system.install())
            for (fixed in listOf(2L, -2L)) {
                assertTrue(lp.atLevel(1))
                assertTrue(lp.assertBound(0, false, ExactLpSide(ExactLpNumber.of(fixed)), axiom))
                assertTrue(lp.assertBound(0, true, ExactLpSide(ExactLpNumber.of(fixed)), axiom))

                assertTrue(system.assertRow(row(listOf(1, 1), 3), axiom))

                assertEquals(BigFraction.ofLong(3L - fixed), lp.state?.activeSide(1, true)?.side?.number?.value)
                assertTrue(lp.atLevel(0))
                assertNull(lp.state?.activeSide(1, true))
                assertEquals(1, assertNotNull(lp.state).model.m)
            }
        }
    }

    @Test
    fun `attached engine premises should withhold a scoped remainder`() {
        LpPropagator(object : LpSearchPolicy {}).use { lp ->
            val system = LiveQfLraSystem(source(), lp)
            assertTrue(system.install())
            assertTrue(lp.atLevel(1))
            val side = ExactLpSide(ExactLpNumber.of(2), premises = ExactLpPremises(emptyList()))
            assertTrue(lp.assertBound(0, false, side, axiom))
            assertTrue(lp.assertBound(0, true, side, axiom))

            assertTrue(system.assertRow(row(listOf(1, 1), 3), axiom))

            assertNull(lp.state?.activeSide(1, true))
        }
    }

    @Test
    fun `an unfixed interval should not supply a scoped remainder`() {
        LpPropagator(object : LpSearchPolicy {}).use { lp ->
            val system = LiveQfLraSystem(source(), lp)
            assertTrue(system.install())
            assertTrue(lp.atLevel(1))
            assertTrue(lp.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2), strict = true), axiom))
            assertTrue(lp.assertBound(0, true, ExactLpSide(ExactLpNumber.of(3)), axiom))

            assertTrue(system.assertRow(row(listOf(1, 1), 3), axiom))

            assertNull(lp.state?.activeSide(1, true))
        }
    }

    @Test
    fun `unavailable scoped fixing premises should withhold the conflict explanation`() {
        LpPropagator(object : LpSearchPolicy {}).use { lp ->
            val system = LiveQfLraSystem(source(), lp)
            assertTrue(system.install())
            val session = SearchSession(listOf(lp))
            session.initialize()
            session.push(SearchDecision.Bool(Lit.make(0, true)))
            assertTrue(lp.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2))))
            assertTrue(lp.assertBound(0, true, ExactLpSide(ExactLpNumber.of(2))))
            assertTrue(system.assertRow(row(listOf(1, 1), 3), axiom))
            assertTrue(lp.assertBound(1, false, ExactLpSide(ExactLpNumber.of(2)), axiom))

            val conflict = assertIs<ComponentResult.Conflict>(lp.propagate(session))

            assertNull(conflict.explanation)
        }
    }

    @Test
    fun `a containing stop should retain charged work and withhold the scoped remainder`() {
        for (limit in listOf(1L, 10L, 30L)) {
            var work = 0L
            val stop = object : Cancellation {
                override fun isCancelled(): Boolean = work >= limit
                override fun workMeter(): WorkMeter = WorkMeter { work += it }
            }
            LpPropagator(object : LpSearchPolicy {}).use { lp ->
                val system = LiveQfLraSystem(source(), lp, cancellation = stop)
                assertTrue(system.install())
                assertTrue(lp.atLevel(1))
                assertTrue(lp.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2)), axiom))
                assertTrue(lp.assertBound(0, true, ExactLpSide(ExactLpNumber.of(2)), axiom))

                assertFalse(system.assertRow(row(listOf(1, 1), 3), axiom))

                assertTrue(work >= limit)
                assertNull(lp.state?.activeSide(1, true))
                assertEquals(BigFraction.ofLong(3), lp.state?.activeSide(2, true)?.side?.number?.value)
            }
        }
    }

    @Test
    fun `an unprojectable scoped remainder should preserve the ordinary source assertion`() {
        val wide = BigFraction.of(BIG_ONE shl 1023, BIG_ONE)
        LpPropagator(object : LpSearchPolicy {}).use { lp ->
            val system = LiveQfLraSystem(source(), lp)
            assertTrue(system.install())
            assertTrue(lp.atLevel(1))
            assertTrue(lp.assertBound(0, false, ExactLpSide(ExactLpNumber.of(wide)), axiom))
            assertTrue(lp.assertBound(0, true, ExactLpSide(ExactLpNumber.of(wide)), axiom))
            val inequality = ExactRationalInequality(
                intArrayOf(0, 1), listOf(BigFraction.ONE, wide.reciprocal()), BigFraction.ZERO,
            )

            assertTrue(system.assertRow(inequality, axiom))

            assertNull(lp.state?.activeSide(1, true))
            assertEquals(BigFraction.ZERO, lp.state?.activeSide(2, true)?.side?.number?.value)
        }
    }

    @Test
    fun `attached engine premises should decline substitution`() {
        LpPropagator(object : LpSearchPolicy {}).use { lp ->
            val system = LiveQfLraSystem(source(), lp)
            assertTrue(system.install())
            val side = ExactLpSide(ExactLpNumber.of(2), premises = ExactLpPremises(emptyList()))
            assertTrue(lp.assertBound(0, false, side, axiom))
            assertTrue(lp.assertBound(0, true, side, axiom))

            assertTrue(system.assertRow(row(listOf(1, 1), 3), axiom))

            assertEquals(1, assertNotNull(lp.state).model.m)
            assertNull(lp.state?.activeSide(1, true))
        }
    }

    @Test
    fun `unavailable root fixing premises should withhold the whole conflict`() {
        LpPropagator(object : LpSearchPolicy {}).use { lp ->
            val system = LiveQfLraSystem(source(), lp)
            assertTrue(system.install())
            val session = SearchSession(listOf(lp))
            session.initialize()
            assertTrue(lp.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2))))
            assertTrue(lp.assertBound(0, true, ExactLpSide(ExactLpNumber.of(2))))
            assertTrue(system.assertRow(row(listOf(1, 1), 3), axiom))
            assertTrue(lp.assertBound(1, false, ExactLpSide(ExactLpNumber.of(2)), axiom))

            assertNull(assertIs<ComponentResult.Conflict>(lp.propagate(session)).explanation)
        }
    }

    @Test
    fun `constant strict bounds should retain source premises for exact completion`() {
        for (strict in listOf(false, true)) {
            LpPropagator(object : LpSearchPolicy {}).use { lp ->
                val system = LiveQfLraSystem(source(), lp)
                assertTrue(system.install())
                val session = SearchSession(listOf(lp))
                session.initialize()
                session.publish(SearchDecision.Bool(Lit.make(0, true)))
                val constant = ExactRationalInequality(intArrayOf(), emptyList(), BigFraction.ZERO, strict)
                assertTrue(system.assertRow(constant, premise(0)))

                val result = assertNotNull(lp.solve())

                if (strict) {
                    assertNull(result.exactPrimal)
                } else {
                    assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
                }
                val active = assertNotNull(lp.state?.activeSide(2, true))
                assertEquals(strict, active.side.strict)
                assertEquals(
                    listOf(Lit.make(0, false)),
                    assertNotNull(session.explainAtoms(lp.boundPremise(active.witness))).literals.toList(),
                )
            }
        }
    }

    @Test
    fun `declared fixed columns should retain exact source values and integer identity`() {
        val source = Problem(
            0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(7), longArrayOf(7), null, null),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
            factors = arrayOf(
                Linear(intArrayOf(0), doubleArrayOf(2.0), intArrayOf(0), doubleArrayOf(-4.0), LinearOp.EQ, 2.0),
            ),
        )
        LpPropagator(object : LpSearchPolicy {}).use { lp ->
            val system = LiveQfLraSystem(source, lp)
            assertTrue(system.install())
            assertTrue(system.assertRow(row(listOf(-4, 2), 2), axiom))
            assertTrue(system.assertRow(row(listOf(4, -2), -2), axiom))

            val result = assertNotNull(lp.solve())

            assertEquals(listOf(BigFraction.ofLong(3), BigFraction.ofLong(7)), result.exactPrimal?.take(2))
            assertEquals(0, assertNotNull(lp.state).model.m)
            assertTrue(assertNotNull(lp.state).model.column(1).integral)
            assertFalse(assertNotNull(lp.state).model.column(0).integral)
        }
    }

    @Test
    fun `an unresolved guarded term should retain source columns without a numerical definition`() {
        val factor = ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1),
            doubleArrayOf(1.0, 2.0), LinearOp.LE, 3.0)
        val source = source().withFactors(arrayOf(factor))
        LpPropagator(object : LpSearchPolicy {}).use { lp ->
            val system = LiveQfLraSystem(source, lp)

            assertTrue(system.install())

            assertEquals(2, assertNotNull(lp.state).model.n)
            assertEquals(2, assertNotNull(lp.state).model.numVars)
            assertEquals(0, assertNotNull(lp.state).model.m)
        }
    }

    @Test
    fun `a guarded definition should survive retraction while its strict bound retracts`() {
        val factor = ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1),
            doubleArrayOf(1.0, 2.0), LinearOp.LE, 0.0, strict = true)
        val source = source().withFactors(arrayOf(factor))
        val form = factor.linearRows.single().exactForm(2)
        LpPropagator(object : LpSearchPolicy {}).use { lp ->
            val system = LiveQfLraSystem(source, lp)
            assertTrue(system.install())
            val session = SearchSession(listOf(lp))
            assertIs<ComponentResult.Consistent>(session.initialize())
            session.push(SearchDecision.Bool(Lit.make(0, true)))
            assertTrue(system.assertComparison(form.comparison(true) { false }, null, premise(0)))
            val first = assertNotNull(assertNotNull(lp.solve()).exactPrimal)
            assertTrue(first[0] + BigFraction.ofLong(2) * first[1] < BigFraction.ZERO)

            session.popTo(0)
            assertIs<ComponentResult.Consistent>(session.propagate())
            session.push(SearchDecision.Bool(Lit.make(0, false)))
            assertTrue(system.assertComparison(form.comparison(false) { false }, null,
                SearchAtomPremise.Asserted(SearchDecision.Bool(Lit.make(0, false)))))

            val sibling = assertNotNull(assertNotNull(lp.solve()).exactPrimal)
            assertTrue(sibling[0] + BigFraction.ofLong(2) * sibling[1] >= BigFraction.ZERO)
            assertEquals(2, assertNotNull(lp.state).model.n)
            assertEquals(1, assertNotNull(lp.state).model.m)
            assertNull(lp.state?.activeSide(2, true))
        }
    }

    @Test
    fun `a deferred definition should retain the numerical row limit across siblings`() {
        val factor = ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1),
            doubleArrayOf(1.0, 2.0), LinearOp.LE, 3.0)
        val source = source().withFactors(arrayOf(factor))
        val form = factor.linearRows.single().exactForm(2)
        LpPropagator(object : LpSearchPolicy {}, effort = { LpEffortProfile(maxRows = 0) }).use { lp ->
            val system = LiveQfLraSystem(source, lp)
            assertTrue(system.install())
            for (truth in listOf(true, false)) {
                assertTrue(lp.atLevel(1))

                assertFalse(system.assertComparison(form.comparison(truth) { false }, null, axiom))

                assertEquals(2, assertNotNull(lp.state).model.numVars)
                assertEquals(0, assertNotNull(lp.state).model.m)
                assertTrue(lp.atLevel(0))
            }
        }
    }

    private fun source(): Problem = Problem(
        4,
        intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
        numRealVars = 2,
        realLower = DoubleArray(2) { Double.NEGATIVE_INFINITY },
        realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY },
        factors = arrayOf(),
    )

    private fun row(coefficients: List<Int>, rhs: Int): ExactRationalInequality = ExactRationalInequality(
        coefficients.indices.toList().toIntArray(),
        coefficients.map { BigFraction.ofLong(it.toLong()) },
        BigFraction.ofLong(rhs.toLong()),
    )

    private fun premise(variable: Int): SearchAtomPremise =
        SearchAtomPremise.Asserted(SearchDecision.Bool(Lit.make(variable, true)))

    private val axiom = SearchAtomPremise.All(emptyList())
}
