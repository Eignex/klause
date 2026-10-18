package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.search.ClauseSearchComponent
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchConflictResolution
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchIntegerBound
import com.eignex.klause.solver.search.SearchLearnedConflictResult
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.explainAtoms
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolynomialTheoryServiceTest {
    @Test
    fun `learned Boolean consequences should invalidate a feasible polynomial condition`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const b Bool) (declare-const c Bool) (declare-const x Real) " +
                "(assert (or b (= (* x x) (- 1))))",
        )
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)
        service.invocation().use { invocation ->
            assertIs<QfNraResult.Satisfiable>(invocation.check())
            val polynomial = PolynomialSearchComponent(service, invocation)
            val session = SearchSession(listOf(
                ClauseSearchComponent(listOf(Clause(intArrayOf(1, 2)), Clause(intArrayOf(1, 3)))), polynomial,
            ))
            assertIs<ComponentResult.Consistent>(session.initialize())
            val conflict = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(0)))
            val learned = assertIs<SearchConflictResolution.Backjump>(
                session.explainedConflict(conflict.explanation),
            ).conflict
            assertContentEquals(intArrayOf(1), learned.guardLiterals)
            session.popTo(learned.decisionLevel)

            val result = learned.apply(session)

            assertEquals(SearchLearnedConflictResult.Chronological, result)
            assertEquals(false, session.boolValue(0))
            assertContentEquals(intArrayOf(1), assertNotNull(session.reasonFor(0)).literals)
            assertIs<ComponentCheck.Indeterminate>(session.check())
            assertIs<QfNraResult.Unsatisfiable>(invocation.check(PolynomialCondition(bools = mapOf(0 to false))))
        }
    }

    @Test
    fun `learned integer bounds should condition polynomial queries and retract to a checked witness`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const g Bool) (declare-const b Bool) (declare-const x Real) (declare-const k Real) " +
                "(assert (= (* x x) k))",
        )
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)
        service.invocation().use { invocation ->
            val polynomial = PolynomialSearchComponent(service, invocation, mapOf(0 to 1))
            lateinit var clauses: ClauseSearchComponent
            var literal = -1
            val peer = object : SearchComponent {
                override fun initialize(context: SearchContext): ComponentResult {
                    val bound = assertNotNull(SearchIntegerBound.of(SearchDecision.IntAtMost(0, -1)))
                    literal = assertNotNull(context.registerAtom(bound, bound.complement())).positive.literal
                    clauses = ClauseSearchComponent(listOf(
                        Clause(intArrayOf(0, literal, 2)), Clause(intArrayOf(0, literal, 3)),
                    ))
                    return clauses.initialize(context)
                }
                override fun assert(decision: SearchDecision, context: SearchContext) =
                    clauses.assert(decision, context)
                override fun propagate(context: SearchContext) = clauses.propagate(context)
                override fun retract(decisionLevel: Int) = clauses.retract(decisionLevel)
            }
            val session = SearchSession(listOf(peer, polynomial), atoms = SearchAtomRegistry(source.boolCount))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(1)))
            val conflict = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(literal xor 1)))
            val learned = assertIs<SearchConflictResolution.Backjump>(
                session.explainedConflict(conflict.explanation),
            ).conflict
            assertContentEquals(intArrayOf(0, literal), learned.guardLiterals.sortedArray())
            session.popTo(learned.decisionLevel)

            val result = learned.apply(session)

            assertEquals(SearchLearnedConflictResult.Chronological, result)
            assertEquals(-1L, session.intUpperBound(0))
            assertContentEquals(intArrayOf(literal xor 1),
                session.explainAtoms(session.intUpperBoundPremise(0))?.literals)
            val spent = invocation.stats.workSpent
            session.popTo(0)
            assertNull(session.intUpperBound(0))
            assertNull(session.boolValue(literal ushr 1))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(0)))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(3)))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.IntEqual(0, 2)))
            assertIs<ComponentCheck.Feasible>(session.check())
            val witness = assertNotNull(session.model().valueOf(polynomial.witnessKey))
            assertEquals(0, AlgebraicArithmetic().signAt(RationalPolynomial.of(-2, 0, 1), witness.realValue(0)))
            assertEquals(BigFraction.ofLong(2), witness.realValue(1).rational)
            assertTrue(invocation.stats.workSpent >= spent)
        }
    }

    @Test
    fun `a polynomial owner stop should preserve learned bounds for resumption`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const g Bool) (declare-const b Bool) (declare-const x Real) (declare-const k Real) " +
                "(assert (= (* x x) k))",
        )
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)
        service.invocation().use { invocation ->
            val polynomial = PolynomialSearchComponent(service, invocation, mapOf(0 to 1))
            lateinit var session: SearchSession
            lateinit var clauses: ClauseSearchComponent
            var literal = -1
            var stopped = false
            val peer = object : SearchComponent {
                override fun initialize(context: SearchContext): ComponentResult {
                    val bound = assertNotNull(SearchIntegerBound.of(SearchDecision.IntAtMost(0, -1)))
                    literal = assertNotNull(context.registerAtom(bound, bound.complement())).positive.literal
                    clauses = ClauseSearchComponent(listOf(
                        Clause(intArrayOf(0, literal, 2)), Clause(intArrayOf(0, literal, 3)),
                    ))
                    return clauses.initialize(context)
                }
                override fun assert(decision: SearchDecision, context: SearchContext) =
                    clauses.assert(decision, context)
                override fun propagate(context: SearchContext) = clauses.propagate(context)
                override fun retract(decisionLevel: Int) = clauses.retract(decisionLevel)
            }
            val token = Cancellation { stopped && literal >= 0 && session.boolValue(literal ushr 1) == true }
            session = SearchSession(listOf(peer, polynomial), atoms = SearchAtomRegistry(source.boolCount),
                cancellation = token)
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(1)))
            val conflict = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(literal xor 1)))
            val learned = assertIs<SearchConflictResolution.Backjump>(
                session.explainedConflict(conflict.explanation),
            ).conflict
            assertContentEquals(intArrayOf(0, literal), learned.guardLiterals.sortedArray())
            session.popTo(learned.decisionLevel)
            val spent = invocation.stats.workSpent
            stopped = true

            val result = learned.apply(session)

            assertEquals(SearchLearnedConflictResult.Indeterminate, result)
            assertEquals(true, session.boolValue(literal ushr 1))
            assertEquals(-1L, session.intUpperBound(0))
            assertEquals(spent, invocation.stats.workSpent)
            assertIs<ComponentCheck.Indeterminate>(session.check())
            stopped = false
            val level = session.decisionLevel
            session.popTo(level)
            assertIs<ComponentResult.Conflict>(session.propagate())
            assertEquals(-1L, session.intUpperBound(0))
            assertEquals(level, session.decisionLevel)
            assertTrue(invocation.stats.workSpent >= spent)
            session.popTo(0)
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(0)))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(3)))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.IntEqual(0, 2)))
            assertIs<ComponentCheck.Feasible>(session.check())
            val witness = assertNotNull(session.model().valueOf(polynomial.witnessKey))
            assertEquals(0, AlgebraicArithmetic().signAt(RationalPolynomial.of(-2, 0, 1), witness.realValue(0)))
            assertNull(session.executionControls.stopCause)
        }
    }

    @Test
    fun `completed query should have no further elements`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (= x 1))")
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)
        service.invocation().use { invocation ->
            val pending = invocation.prepareCheck()
            var result: QfNraResult? = null
            while (result == null) result = pending.next()

            assertFalse(pending.hasNext())
            assertFailsWith<NoSuchElementException> { pending.next() }
        }
    }

    @Test
    fun `projection continuation should retain native charges and refutation`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (declare-const y Real)
            (assert (= y (* x x)))
            (assert (= (* x x) 2))
            (assert (= (* y y) 3))
            """.trimIndent(),
        )
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)
        val complete = service.invocation().use { assertIs<QfNraResult.Unsatisfiable>(it.check()) }
        assertTrue(complete.stats.projectedPolynomials > 0)

        service.invocation(QfNraParams(maximumWork = complete.stats.workSpent + 1)).use { invocation ->
            val pending = invocation.prepareCheck()
            var result: QfNraResult? = null
            var pauses = 0
            while (result == null) {
                result = pending.next()
                if (result == null) pauses++
            }

            assertIs<QfNraResult.Unsatisfiable>(result)
            assertTrue(pauses > 2)
            assertEquals(complete.stats, result.stats)
            assertEquals(complete.stats, invocation.stats)
            assertEquals(1L, invocation.queries)
        }
    }

    @Test
    fun `paused query should retain native work without repeating preparation`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (= (* x x) 2))")
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)
        val required = service.invocation().use { it.check().stats.workSpent }

        service.invocation(QfNraParams(maximumWork = required + 1)).use { invocation ->
            val pending = invocation.prepareCheck()
            assertNull(pending.next())
            val preparedWork = invocation.stats.workSpent
            assertTrue(preparedWork > 0)
            var result: QfNraResult? = null
            while (result == null) result = pending.next()

            assertIs<QfNraResult.Satisfiable>(result)
            assertEquals(required, invocation.stats.workSpent)
            assertEquals(1L, invocation.queries)
            assertEquals(QfNraReason.WorkLimit, assertIs<QfNraResult.Unknown>(invocation.check()).reason)
        }
    }

    @Test
    fun `closing paused query should retain charges and reject continuation`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (= x 1))")
        val invocation = PolynomialTheoryService(source.formula, source.boolCount, source.realCount).invocation()
        val pending = invocation.prepareCheck()
        assertNull(pending.next())
        val charged = invocation.stats.workSpent

        invocation.close()
        invocation.close()

        assertEquals(charged, invocation.stats.workSpent)
        assertFailsWith<IllegalStateException> { pending.next() }
    }

    @Test
    fun `solve cancellation should terminate paused query without a partial witness`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (= x 1))")
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)
        var cancelled = false
        service.invocation(QfNraParams(cancellation = Cancellation { cancelled })).use { invocation ->
            val pending = invocation.prepareCheck()
            assertNull(pending.next())
            cancelled = true

            val result = pending.next()

            assertEquals(QfNraReason.Interrupted, assertIs<QfNraResult.Unknown>(result).reason)
        }
    }

    @Test
    fun `query bounds should not replace fixed source coordinates`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert true)")
        val service = PolynomialTheoryService(source.formula, 0, 1, mapOf(0 to BigFraction.ONE))
        val contradictory = PolynomialCondition(
            lower = mapOf(0 to BigFraction.ofLong(2)),
            upper = mapOf(0 to BigFraction.ofLong(2)),
        )

        service.invocation().use { invocation ->
            assertIs<QfNraResult.Unsatisfiable>(invocation.check(contradictory))
            val witness = assertIs<QfNraResult.Satisfiable>(invocation.check()).assignment
            assertEquals(BigFraction.ONE, witness.realValue(0).rational)
        }
    }

    @Test
    fun `conditioned queries should isolate finite pins`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (declare-const k Real)
            (assert (= (* x x) k))
            """.trimIndent(),
        )
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)

        service.invocation().use { invocation ->
            val negative = PolynomialCondition(
                lower = mapOf(1 to BigFraction.MINUS_ONE),
                upper = mapOf(1 to BigFraction.MINUS_ONE),
            )
            val positive = PolynomialCondition(
                lower = mapOf(1 to BigFraction.ofLong(2)),
                upper = mapOf(1 to BigFraction.ofLong(2)),
            )
            assertIs<QfNraResult.Unsatisfiable>(invocation.check(negative))
            val witness = assertIs<QfNraResult.Satisfiable>(invocation.check(positive)).assignment
            assertEquals(0, AlgebraicArithmetic().signAt(RationalPolynomial.of(-2, 0, 1), witness.realValue(0)))
            assertIs<QfNraResult.Unsatisfiable>(invocation.check(negative))
        }
    }

    @Test
    fun `Boolean query conditions should preserve correlation`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const b Bool)
            (declare-const x Real)
            (assert (or b (= (* x x) (- 1))))
            """.trimIndent(),
        )
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)

        service.invocation().use { invocation ->
            assertIs<QfNraResult.Unsatisfiable>(invocation.check(PolynomialCondition(bools = mapOf(0 to false))))
            val witness = assertIs<QfNraResult.Satisfiable>(
                invocation.check(PolynomialCondition(bools = mapOf(0 to true))),
            ).assignment
            assertEquals(true, witness.boolValue(0))
        }
    }

    @Test
    fun `native work should reach caller meter once`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (= x 1))")
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)
        var charged = 0L
        val token = object : Cancellation, WorkMeter {
            override fun isCancelled() = false
            override fun workMeter(): WorkMeter = this
            override fun charge(units: Long) {
                charged += units
            }
        }

        service.invocation(QfNraParams(cancellation = token)).use { invocation ->
            invocation.check()
            invocation.check()
            assertEquals(invocation.stats.workSpent, charged)
        }
    }

    @Test
    fun `closing an invocation should preserve published coordinates`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (= (* x x) 2))")
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)
        val invocation = service.invocation()
        val witness = assertIs<QfNraResult.Satisfiable>(invocation.check()).assignment

        invocation.close()
        invocation.close()
        assertFailsWith<IllegalStateException> { invocation.check() }
        assertEquals(0, AlgebraicArithmetic().signAt(RationalPolynomial.of(-2, 0, 1), witness.realValue(0)))
    }

    @Test
    fun `cancelled query should not contaminate a fresh invocation`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (= x 1))")
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)

        service.invocation().use { invocation ->
            assertEquals(
                QfNraReason.Interrupted,
                assertIs<QfNraResult.Unknown>(invocation.check(cancellation = Cancellation { true })).reason,
            )
        }
        service.invocation().use { assertIs<QfNraResult.Satisfiable>(it.check()) }
    }
}
