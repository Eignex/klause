package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.config.DEFAULT_UNBOUNDED_INT_HI
import com.eignex.klause.config.DEFAULT_UNBOUNDED_INT_LO
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.lowering.PolynomialSourceInterrupted
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.search.ClauseSearchComponent
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SmtLibAffineRealTablesTest {
    @Test
    fun `source Real tag exclusions propagate after conditional column compaction`() {
        val builder = Compiler.Builder(DEFAULT_UNBOUNDED_INT_LO, DEFAULT_UNBOUNDED_INT_HI, strictBounds = false)
        SExprReader(
            """
            (declare-const x Real)
            (declare-const a Bool) (declare-const b Bool)
            (declare-const zero Bool) (declare-const one Bool) (declare-const two Bool)
            """.trimIndent(),
        ).readAll().forEach(builder::command)
        val unusedConjunction = builder.compileBool(SExprReader("(and a b)").readAll().single())
        builder.assert(SExprReader("(let ((unused (ite (and a b) 1 0))) true)").readAll().single())
        val firstTag = builder.compileBool(SExprReader("(= x 0)").readAll().single())
        for (assertion in listOf("(= zero (= x 0))", "(= one (= x 1))", "(= two (= x 2))", "zero")) {
            builder.assert(SExprReader(assertion).readAll().single())
        }
        val booleansBeforeCompaction = builder.nextBool

        val source = builder.build()
        val session = SearchSession(
            listOf(ClauseSearchComponent(source.model.factors.filterIsInstance<Clause>())),
            atoms = SearchAtomRegistry(source.model.numBoolVars),
        )

        val result = session.initialize()

        assertIs<ComponentResult.Consistent>(result)
        assertEquals(0, source.model.numIntVars)
        assertTrue(Lit.variable(unusedConjunction) < Lit.variable(firstTag))
        assertEquals(booleansBeforeCompaction - 1, source.model.numBoolVars)
        assertEquals(true, session.boolValue(Lit.variable(firstTag) - 1))
        for (name in listOf("one", "two")) assertEquals(false, session.boolValue(source.boolVarNames.getValue(name)))
    }

    @Test
    fun `source Real tag exclusions preserve listed and unlisted witnesses`() {
        for (value in 0..3) {
            val source = SmtLib.parse(
                """
                (declare-const x Real)
                (declare-const zero Bool) (declare-const one Bool) (declare-const two Bool)
                (assert (= zero (= x 0))) (assert (= one (= x 1))) (assert (= two (= x 2)))
                (assert (= x $value))
                """.trimIndent(),
            )

            val result = BacktrackSolver.source(source.model).solve()

            val point = assertIs<OpenTheoryResult.Sat>(result).assignment
            assertEquals(value.toString(), point.realValue(source.realVarNames.getValue("x")))
            for ((tag, name) in listOf("zero", "one", "two").withIndex()) {
                assertEquals(value == tag, point.boolValue(source.boolVarNames.getValue(name)))
            }
        }
    }

    @Test
    fun `scalar exclusions require distinct literals and values on the same declared coordinate`() {
        val capture = SmtLibAffineRealTables(Cancellation.Never)
        for ((literal, variable, value) in listOf(Triple(0, 7, 0), Triple(0, 7, 0), Triple(2, 7, 0),
            Triple(4, 7, 1), Triple(4, 7, 1), Triple(6, 8, 0), Triple(8, 8, 1),
            Triple(0, 9, 0), Triple(4, 9, 1))) {
            capture.equality(literal,
                RealComb(emptyMap(), mapOf(variable to BigFraction.ONE), BigFraction.ofLong(-value.toLong())))
        }

        val clauses = capture.infer(listOf(7, 9)).filterIsInstance<Clause>()

        assertEquals(setOf(setOf(1, 5), setOf(3, 5)), clauses.map { it.literals.toSet() }.toSet())
        assertEquals(2, clauses.size)
    }

    @Test
    fun `an exhausted scalar exclusion allowance emits no unfinished factor`() {
        val capture = SmtLibAffineRealTables(Cancellation.Never, entryLimit = 28)
        for (value in 0..1) {
            capture.equality(2 * value,
                RealComb(emptyMap(), mapOf(7 to BigFraction.ONE), BigFraction.ofLong(-value.toLong())))
        }

        val factors = capture.infer(listOf(7))

        assertTrue(factors.isEmpty())
    }

    @Test
    fun `completed scalar exclusions survive exhaustion while a later pair is visited`() {
        val capture = SmtLibAffineRealTables(Cancellation.Never, entryLimit = 44)
        for (value in 0..2) {
            capture.equality(2 * value,
                RealComb(emptyMap(), mapOf(7 to BigFraction.ONE), BigFraction.ofLong(-value.toLong())))
        }

        val clauses = capture.infer(listOf(7)).filterIsInstance<Clause>()

        assertEquals(1, clauses.size)
    }

    @Test
    fun `scalar exclusion inference forwards charges and observes containing cancellation`() {
        var charged = 0L
        var armed = false
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = armed && charged >= 26L
            override fun charge(units: Long) { charged += units }
        }
        val capture = SmtLibAffineRealTables(stop)
        for (value in 0..1) {
            capture.equality(2 * value,
                RealComb(emptyMap(), mapOf(7 to BigFraction.ONE), BigFraction.ofLong(-value.toLong())))
        }
        armed = true

        assertFailsWith<PolynomialSourceInterrupted> { capture.infer(listOf(7)) }

        assertTrue(charged >= 26L)
    }

    @Test
    fun `a complete affine table links its scalar Boolean channels without rounding`() {
        val source = SmtLib.parse(
            """
            (declare-const a Bool) (declare-const b Bool)
            (declare-const x Real) (declare-const y Real) (declare-const t Real)
            (assert (=> a (= x 6))) (assert (=> (not a) (= x 4)))
            (assert (=> b (= y 15))) (assert (=> (not b) (= y 10)))
            (assert (=> (and (not a) (not b)) (= t 5)))
            (assert (=> (and a (not b)) (= t 7)))
            (assert (=> (and (not a) b) (= t 8)))
            (assert (=> (and a b) (= t 10)))
            """.trimIndent(),
        )

        val row = source.model.factors.filterIsInstance<ExactLinear>()
            .single { it.activator == LinearRow.ALWAYS && it.size == 3 }

        assertEquals(LinearOp.EQ, row.relation)
        assertEquals(listOf("10", "-10", "-6"), (0 until row.size).map { row.constants.exactCoeff(it).toString() })
        assertEquals("-50", row.constants.exactBound.toString())
        assertEquals(0, source.model.numIntVars)
        assertEquals(3, source.model.numRealVars)
    }

    @Test
    fun `every original affine table branch retains its witness`() {
        for ((a, b, expected) in listOf(Triple(false, false, "5"), Triple(true, false, "7"),
            Triple(false, true, "8"), Triple(true, true, "10"))) {
            val source = SmtLib.parse(
                """
                (declare-const a Bool) (declare-const b Bool)
                (declare-const x Real) (declare-const y Real) (declare-const t Real)
                (assert (=> a (= x 6))) (assert (=> (not a) (= x 4)))
                (assert (=> b (= y 15))) (assert (=> (not b) (= y 10)))
                (assert (=> (and (not a) (not b)) (= t 5)))
                (assert (=> (and a (not b)) (= t 7)))
                (assert (=> (and (not a) b) (= t 8)))
                (assert (=> (and a b) (= t 10)))
                (assert (= a $a)) (assert (= b $b))
                """.trimIndent(),
            )

            val result = BacktrackSolver.source(source.model).solve()

            assertEquals(expected, assertIs<OpenTheoryResult.Sat>(result).assignment
                .realValue(source.realVarNames.getValue("t")))
        }
    }

    @Test
    fun `a missing conflicting or nonlinear table entry withholds the affine relation`() {
        for (last in listOf("", "(assert (=> (and a b) (= t 11)))",
            "(assert (=> (and a b) (= t 10))) (assert (=> (and a b) (= t 12)))")) {
            val source = SmtLib.parse(
                """
                (declare-const a Bool) (declare-const b Bool)
                (declare-const x Real) (declare-const y Real) (declare-const t Real)
                (assert (=> a (= x 6))) (assert (=> (not a) (= x 4)))
                (assert (=> b (= y 15))) (assert (=> (not b) (= y 10)))
                (assert (=> (and (not a) (not b)) (= t 5)))
                (assert (=> (and a (not b)) (= t 7)))
                (assert (=> (and (not a) b) (= t 8))) $last
                """.trimIndent(),
            )

            val rows = source.model.factors.filterIsInstance<ExactLinear>()
                .filter { it.activator == LinearRow.ALWAYS && it.size == 3 }

            assertTrue(rows.isEmpty(), last)
        }
    }

    @Test
    fun `a complete table without every scalar channel supplies no affine relation`() {
        val source = SmtLib.parse(
            """
            (declare-const a Bool) (declare-const b Bool)
            (declare-const x Real) (declare-const t Real)
            (assert (=> a (= x 6))) (assert (=> (not a) (= x 4)))
            (assert (=> (and (not a) (not b)) (= t 5)))
            (assert (=> (and a (not b)) (= t 7)))
            (assert (=> (and (not a) b) (= t 8)))
            (assert (=> (and a b) (= t 10)))
            """.trimIndent(),
        )

        val rows = source.model.factors.filterIsInstance<ExactLinear>().filter { it.activator == LinearRow.ALWAYS }

        assertTrue(rows.isEmpty())
    }

    @Test
    fun `nested source conjunctions and shared equality bindings retain complete table coverage`() {
        val source = SmtLib.parse(
            """
            (declare-const a Bool) (declare-const b Bool)
            (declare-const x Real) (declare-const y Real) (declare-const t Real)
            (assert (=> a (= x 1))) (assert (=> (not a) (= x 0)))
            (assert (=> b (= y 1))) (assert (=> (not b) (= y 0)))
            (assert (let ((na (and (not a) true)) (pa (and a true))
                          (nb (and (not b) true)) (pb (and b true)) (middle (= t 1)))
                (and (=> (and na nb) (= t 0)) (=> (and pa nb) middle)
                     (=> (and na pb) middle) (=> (and pa pb) (= t 2)))))
            """.trimIndent(),
        )

        val row = source.model.factors.filterIsInstance<ExactLinear>()
            .single { it.activator == LinearRow.ALWAYS && it.size == 3 }

        assertEquals(listOf("1", "-1", "-1"), (0 until row.size).map { row.constants.exactCoeff(it).toString() })
        assertEquals("0", row.constants.exactBound.toString())
    }

    @Test
    fun `a source arithmetic predicate cannot masquerade as a declared Boolean table input`() {
        val source = SmtLib.parse(
            """
            (declare-const a Bool) (declare-const z Real)
            (declare-const x Real) (declare-const y Real) (declare-const t Real)
            (assert (=> a (= x 1))) (assert (=> (not a) (= x 0)))
            (assert (=> (<= z 0) (= y 1))) (assert (=> (> z 0) (= y 0)))
            (assert (=> (and (not a) (> z 0)) (= t 0)))
            (assert (=> (and a (> z 0)) (= t 1)))
            (assert (=> (and (not a) (<= z 0)) (= t 1)))
            (assert (=> (and a (<= z 0)) (= t 2)))
            """.trimIndent(),
        )

        val rows = source.model.factors.filterIsInstance<ExactLinear>()
            .filter { it.activator == LinearRow.ALWAYS && it.size == 3 }

        assertTrue(rows.isEmpty())
    }

    @Test
    fun `exhausted optional capture supplies no partial table equation`() {
        val capture = SmtLibAffineRealTables(Cancellation.Never, entryLimit = 1)
        capture.declarations(listOf(0, 1))
        capture.equality(4, RealComb(emptyMap(), mapOf(0 to BigFraction.ONE), BigFraction.ZERO))
        capture.implication(intArrayOf(0, 4), trueLiteral = -1)

        assertTrue(capture.infer().isEmpty())
    }

    @Test
    fun `scalar capture forwards overridden caller charges and respects containing cancellation`() {
        var charged = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = charged >= 64
            override fun charge(units: Long) { charged += units }
        }
        val capture = SmtLibAffineRealTables(stop)
        capture.declarations(listOf(0, 1))

        assertFailsWith<PolynomialSourceInterrupted> {
            capture.equality(4, RealComb(emptyMap(), mapOf(0 to BigFraction.ofLong(2)), BigFraction.ZERO))
        }
        assertTrue(charged >= 64)
    }

    @Test
    fun `containing cancellation is observed after an optional capture decline`() {
        var cancelled = false
        val capture = SmtLibAffineRealTables(Cancellation { cancelled }, entryLimit = 0)
        capture.declarations(listOf(0, 1))
        cancelled = true

        assertFailsWith<PolynomialSourceInterrupted> { capture.infer() }
    }


    @Test
    fun `a completed table survives exhaustion while a later table is still being prepared`() {
        val capture = SmtLibAffineRealTables(Cancellation.Never, entryLimit = 20_000)
        capture.declarations((0 until 8).toList())
        for (value in 0..2) {
            capture.equality(200 + 2 * value,
                RealComb(emptyMap(), mapOf(8 to BigFraction.ONE), BigFraction.ofLong(-value.toLong())))
        }
        for (assignment in 0 until 4) {
            val cube = (0 until 2).map { variable ->
                2 * variable + if ((assignment and (1 shl variable)) == 0) 1 else 0
            }
            capture.conjunction(1000 + 2 * assignment, cube, trueLiteral = -1)
            capture.implication(intArrayOf(1000 + 2 * assignment, 200 + 2 * assignment.countOneBits()), -1)
        }
        for (variable in 0 until 8) for (value in 0..1) {
            val equality = 400 + 4 * variable + 2 * value
            capture.equality(equality,
                RealComb(emptyMap(), mapOf(variable to BigFraction.ONE), BigFraction.ofLong(-value.toLong())))
            capture.implication(intArrayOf(2 * variable + 1 - value, equality), trueLiteral = -1)
        }
        capture.equality(900, RealComb(emptyMap(), mapOf(9 to BigFraction.ONE), BigFraction.ZERO))
        for (assignment in 0 until 256) {
            val cube = (0 until 8).map { variable ->
                2 * variable + if ((assignment and (1 shl variable)) == 0) 1 else 0
            }
            capture.conjunction(2000 + 2 * assignment, cube, trueLiteral = -1)
            capture.implication(intArrayOf(2000 + 2 * assignment, 900), trueLiteral = -1)
        }

        val rows = capture.infer().filterIsInstance<ExactLinear>()

        assertEquals(1, rows.size)
        assertEquals(3, rows.single().size)
        assertEquals("0", rows.single().constants.exactBound.toString())
    }

    @Test
    fun `a small capture allowance still completes a unit coefficient table`() {
        val capture = SmtLibAffineRealTables(Cancellation.Never, entryLimit = 512)
        capture.declarations(listOf(0, 1))
        for (variable in 0 until 2) for (value in 0..1) {
            val equality = 100 + 4 * variable + 2 * value
            capture.equality(equality,
                RealComb(emptyMap(), mapOf(variable to BigFraction.ONE), BigFraction.ofLong(-value.toLong())))
            capture.implication(intArrayOf(2 * variable + 1 - value, equality), trueLiteral = -1)
        }
        for (value in 0..2) {
            capture.equality(200 + 2 * value,
                RealComb(emptyMap(), mapOf(2 to BigFraction.ONE), BigFraction.ofLong(-value.toLong())))
        }
        for (assignment in 0 until 4) {
            val cube = (0 until 2).map { variable ->
                2 * variable + if ((assignment and (1 shl variable)) == 0) 1 else 0
            }
            capture.conjunction(1000 + 2 * assignment, cube, trueLiteral = -1)
            capture.implication(intArrayOf(1000 + 2 * assignment, 200 + 2 * assignment.countOneBits()), -1)
        }

        val row = capture.infer(listOf(0, 1, 2)).filterIsInstance<ExactLinear>().single()

        assertEquals(3, row.size)
        assertEquals(listOf("1", "-1", "-1"), (0 until row.size).map { row.constants.exactCoeff(it).toString() })
        assertEquals("0", row.constants.exactBound.toString())
    }

    @Test
    fun `channel endpoint differences remain exact beyond a signed word`() {
        val source = SmtLib.parse(
            """
            (declare-const a Bool) (declare-const b Bool)
            (declare-const x Real) (declare-const y Real) (declare-const t Real)
            (assert (=> a (= x 9223372036854775807.0)))
            (assert (=> (not a) (= x (- 9223372036854775808.0))))
            (assert (=> b (= y 2.0))) (assert (=> (not b) (= y 0.0)))
            (assert (=> (and (not a) (not b)) (= t 0.0)))
            (assert (=> (and a (not b)) (= t 1.0)))
            (assert (=> (and (not a) b) (= t 1.0)))
            (assert (=> (and a b) (= t 2.0)))
            """.trimIndent(),
        )

        val row = source.model.factors.filterIsInstance<ExactLinear>()
            .single { it.activator == LinearRow.ALWAYS && it.size == 3 }

        assertEquals(listOf("36893488147419103230", "-2", "-18446744073709551615"),
            (0 until row.size).map { row.constants.exactCoeff(it).toString() })
        assertEquals("18446744073709551616", row.constants.exactBound.toString())
    }
}
