package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.config.DEFAULT_UNBOUNDED_INT_HI
import com.eignex.klause.config.DEFAULT_UNBOUNDED_INT_LO
import com.eignex.klause.ir.Lit
import com.eignex.klause.lowering.PolynomialSourceInterrupted
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SmtLibBooleanDefinitionsTest {
    @Test
    fun `repeated gates should share their definition across operand order`() {
        for (operator in listOf("and", "or", "iff")) {
            val builder = Compiler.Builder(DEFAULT_UNBOUNDED_INT_LO, DEFAULT_UNBOUNDED_INT_HI, strictBounds = false)
            val first = Lit.make(builder.newBool(), true)
            val second = Lit.make(builder.newBool(), true)
            val original = when (operator) {
                "and" -> builder.reifyAnd(listOf(first, second))
                "or" -> builder.reifyOr(listOf(first, second))
                else -> builder.reifyIff(first, second)
            }
            val emitted = builder.factors.size

            val repeated = when (operator) {
                "and" -> builder.reifyAnd(listOf(second, first, second))
                "or" -> builder.reifyOr(listOf(second, first, second))
                else -> builder.reifyIff(Lit.negate(second), Lit.negate(first))
            }

            assertEquals(original, repeated)
            assertEquals(emitted, builder.factors.size)
        }
    }

    @Test
    fun `shared gate operators and signed inputs should preserve their source truth`() {
        for (left in listOf(false, true)) for (right in listOf(false, true)) {
            val source = SmtLib.parse(
                """
                    (declare-const a Bool) (declare-const b Bool) (declare-const x Real)
                    (declare-const p Bool) (declare-const q Bool) (declare-const r Bool)
                    (declare-const s Bool) (declare-const t Bool) (declare-const u Bool)
                    (assert (= a $left)) (assert (= b $right))
                    (assert (= p (and a b))) (assert (= q (or b a))) (assert (= r (= a b)))
                    (assert (= s (let ((a (not a))) (and a b))))
                    (assert (= t (or (not a) b))) (assert (= u (= (not a) b)))
                """.trimIndent(),
            )

            val result = BacktrackSolver.source(source.model).solve()

            val point = assertIs<OpenTheoryResult.Sat>(result).assignment
            for ((name, expected) in listOf("p" to (left && right), "q" to (left || right),
                "r" to (left == right), "s" to (!left && right), "t" to (!left || right), "u" to (left != right))) {
                assertEquals(expected, point.boolValue(source.boolVarNames.getValue(name)))
            }
        }
    }

    @Test
    fun `a full gate table should retain existing definitions and leave new gates independent`() {
        val gates = SmtBooleanGates(Cancellation.Never, maxEntries = 3)
        val inputs = listOf(Lit.make(0, true), Lit.make(1, true))
        var next = 0

        val names = listOf(SmtBooleanGates.Kind.AND, SmtBooleanGates.Kind.OR,
            SmtBooleanGates.Kind.OR, SmtBooleanGates.Kind.AND).map { kind ->
            gates.literal(kind, inputs) { next++ }
        }

        assertEquals(listOf(0, 1, 2, 0), names)
    }

    @Test
    fun `stopped gate lookups should not allocate another definition`() {
        var stopped = false
        var definitions = 0
        val gates = SmtBooleanGates(Cancellation { stopped })
        val inputs = listOf(Lit.make(0, true), Lit.make(1, true))
        gates.literal(SmtBooleanGates.Kind.AND, inputs) { definitions++ }
        stopped = true

        assertFailsWith<PolynomialSourceInterrupted> {
            gates.literal(SmtBooleanGates.Kind.AND, inputs) { definitions++ }
        }

        assertEquals(1, definitions)
    }

    @Test
    fun `gate definitions should belong to one parser builder`() {
        repeat(2) {
            val builder = Compiler.Builder(DEFAULT_UNBOUNDED_INT_LO, DEFAULT_UNBOUNDED_INT_HI, strictBounds = false)
            val first = Lit.make(builder.newBool(), true)
            val second = Lit.make(builder.newBool(), true)

            builder.reifyAnd(listOf(first, second))

            assertEquals(3, builder.factors.size)
        }
    }

    @Test
    fun `cancelled empty Boolean definitions should not emit a constant`() {
        for (conjunction in listOf(false, true)) {
            val builder = Compiler.Builder(DEFAULT_UNBOUNDED_INT_LO, DEFAULT_UNBOUNDED_INT_HI,
                strictBounds = false, sourceCancellation = Cancellation { true })

            assertFailsWith<PolynomialSourceInterrupted> {
                if (conjunction) builder.reifyAnd(emptyList()) else builder.reifyOr(emptyList())
            }

            assertTrue(builder.factors.isEmpty())
            assertEquals(0, builder.nextBool)
        }
    }

    @Test
    fun `one comparison should not allocate a singleton Boolean wrapper`() {
        for (sort in listOf("Int", "Real")) {
            val source = SmtLib.parse("(declare-const x $sort) (assert (not (<= x 0)))")

            assertEquals(1, source.model.numBoolVars)
        }
    }

    @Test
    fun `empty Boolean definitions should reuse the library constant`() {
        for (operation in listOf("and", "or")) {
            val source = SmtLib.parse("(declare-const p Bool) (assert (= p ($operation)))")

            assertEquals(2, source.model.numBoolVars)
        }
    }

    @Test
    fun `neutral and repeated operands should retain the source literal truth`() {
        for (expression in listOf("(and p true p)", "(or p false p)")) {
            for (truth in listOf(false, true)) {
                val source = SmtLib.parse(
                    """
                        (declare-const p Bool)
                        (declare-const q Bool)
                        (declare-const x Real)
                        (assert (= p $truth))
                        (assert (= q $expression))
                    """.trimIndent(),
                )

                val result = BacktrackSolver.source(source.model).solve()

                val point = assertIs<OpenTheoryResult.Sat>(result).assignment
                assertEquals(truth, point.boolValue(source.boolVarNames.getValue("q")))
            }
        }
    }

    @Test
    fun `absorbing and complementary operands should retain constant truth`() {
        val expressions = listOf(
            "(and p false)" to false,
            "(or p true)" to true,
            "(and p (not p))" to false,
            "(or p (not p))" to true,
        )
        for ((expression, expected) in expressions) {
            val source = SmtLib.parse(
                """
                    (declare-const p Bool)
                    (declare-const q Bool)
                    (declare-const x Real)
                    (assert (= q $expression))
                """.trimIndent(),
            )

            val result = BacktrackSolver.source(source.model).solve()

            val point = assertIs<OpenTheoryResult.Sat>(result).assignment
            assertEquals(expected, point.boolValue(source.boolVarNames.getValue("q")))
        }
    }

    @Test
    fun `Boolean equivalence identities should preserve either input polarity`() {
        for (truth in listOf(false, true)) {
            val expressions = listOf(
                "(= p p)" to true,
                "(= p (not p))" to false,
                "(= p true)" to truth,
                "(= p false)" to !truth,
                "(= true p)" to truth,
                "(= false p)" to !truth,
            )
            for ((expression, expected) in expressions) {
                val source = SmtLib.parse(
                    """
                        (declare-const p Bool)
                        (declare-const q Bool)
                        (declare-const x Real)
                        (assert (= p $truth))
                        (assert (= q $expression))
                    """.trimIndent(),
                )

                val result = BacktrackSolver.source(source.model).solve()

                val point = assertIs<OpenTheoryResult.Sat>(result).assignment
                assertEquals(expected, point.boolValue(source.boolVarNames.getValue("q")))
            }
        }
    }

    @Test
    fun `Boolean simplification should retain child source validation`() {
        assertFailsWith<UnsupportedSmtException> {
            SmtLib.parse("(declare-const b Bool) (assert (= b (or true (> (/ 1.0 0.0) 0.0))))")
        }
    }
}
