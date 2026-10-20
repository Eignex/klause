package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.PolynomialSelection
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PolynomialFormulaTest {
    @Test
    fun `fixed numeric truths complete reified Booleans with their required polarity`() {
        for (negated in listOf(false, true)) for (value in listOf(false, true)) {
            val predicate = if (negated) "(not (> x 0))" else "(> x 0)"
            val source = SmtLibPolynomial.parse(
                "(declare-const x Real) (declare-const b Bool) (assert (= b $predicate))",
            )
            val arithmetic = PolynomialArithmetic()
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)
            val facts = IntArray(formula.atoms.size) { if (value) PolynomialFormula.TRUE else PolynomialFormula.FALSE }

            val completed = assertNotNull(formula.completeBooleans(source.boolCount, facts, arithmetic))

            assertEquals(listOf(value != negated), completed.toList())
        }
    }

    @Test
    fun `numeric conflicts reject a candidate before unrelated Boolean choices exhaust its allowance`() {
        val choices = (0 until 32).map { "c$it" }
        val declarations = choices.joinToString(" ") { "(declare-const $it Bool)" }
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real) $declarations (declare-const b Bool)
            (assert (or ${choices.joinToString(" ")}))
            (assert (= b (> x 0))) (assert b)
            """.trimIndent(),
        )
        val formula = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
        var spent = 0L
        val arithmetic = PolynomialArithmetic(object : Cancellation {
            override fun isCancelled(): Boolean = spent >= 32_768
            override fun workMeter(): WorkMeter = WorkMeter { spent += it }
        })

        val completed = formula.completeBooleans(source.boolCount,
            IntArray(formula.atoms.size) { PolynomialFormula.FALSE }, arithmetic)

        assertNull(completed)
    }

    @Test
    fun `Boolean completion unwinds correlated choices and defaults unused declarations to false`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const a Bool) (declare-const b Bool) (declare-const c Bool) (declare-const unused Bool)
            (assert (and (or a b c) (or a b (not c))
              (or a (not b) c) (or a (not b) (not c))))
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val completed = assertNotNull(formula.completeBooleans(source.boolCount, IntArray(0), arithmetic))

        assertEquals(listOf(true, false, false, false), completed.toList())
    }

    @Test
    fun `Boolean completion retains false first source order when formula leaves appear in reverse order`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const a Bool) (declare-const b Bool) (declare-const unused Bool) (assert (or b a))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val completed = assertNotNull(formula.completeBooleans(source.boolCount, IntArray(0), arithmetic))

        assertEquals(listOf(false, true, false), completed.toList())
    }

    @Test
    fun `interrupted Boolean completion leaves later numeric facts independent`() {
        val names = (0 until 24).map { "b$it" }
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) " + names.joinToString(" ") {
                "(declare-const $it Bool) (assert (= $it (> x 0)))"
            },
        )
        val formula = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
        val facts = IntArray(formula.atoms.size) { PolynomialFormula.TRUE }
        var completedWork = 0L
        val counter = PolynomialArithmetic(object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { completedWork += it }
        })
        val completed = assertNotNull(formula.completeBooleans(source.boolCount, facts, counter))
        assertEquals(List(24) { true }, completed.toList())

        for (limit in listOf(completedWork / 2, completedWork - 1)) {
            var spent = 0L
            val interrupted = PolynomialArithmetic(object : Cancellation {
                override fun isCancelled(): Boolean = spent >= limit
                override fun workMeter(): WorkMeter = WorkMeter { spent += it }
            })

            assertFailsWith<PolynomialInterrupted> { formula.completeBooleans(source.boolCount, facts, interrupted) }
        }

        val fresh = assertNotNull(formula.completeBooleans(source.boolCount,
            IntArray(formula.atoms.size) { PolynomialFormula.FALSE }, PolynomialArithmetic()))
        assertEquals(List(24) { false }, fresh.toList())
    }

    @Test
    fun `forced reified predicates retain their required polarity`() {
        val relations = listOf(
            "=" to PolynomialRelation.EQ, "distinct" to PolynomialRelation.NE,
            "<" to PolynomialRelation.LT, "<=" to PolynomialRelation.LE,
            ">" to PolynomialRelation.GT, ">=" to PolynomialRelation.GE,
        )
        val complements = mapOf(
            PolynomialRelation.EQ to PolynomialRelation.NE, PolynomialRelation.NE to PolynomialRelation.EQ,
            PolynomialRelation.LT to PolynomialRelation.GE, PolynomialRelation.LE to PolynomialRelation.GT,
            PolynomialRelation.GT to PolynomialRelation.LE, PolynomialRelation.GE to PolynomialRelation.LT,
        )
        for ((operator, relation) in relations) for (positive in listOf(false, true)) {
            val source = SmtLibPolynomial.parse(
                """
                (declare-const x Real) (declare-const b Bool)
                (assert (= b ($operator x 0)))
                (assert ${if (positive) "b" else "(not b)"})
                """.trimIndent(),
            )
            val arithmetic = PolynomialArithmetic()
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)

            val atoms = assertNotNull(formula.witnessAtoms(arithmetic, 128))

            assertEquals(if (positive) relation else complements.getValue(relation), atoms.single().relation)
            assertNull(formula.conjunctiveAtoms(arithmetic, 128))
        }
    }

    @Test
    fun `unresolved reification supplies no unconditional witness predicates`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real) (declare-const b Bool)
            (assert (= b (> (* x x) 5)))
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val atoms = assertNotNull(formula.witnessAtoms(arithmetic, 128))

        assertEquals(emptyList(), atoms)
    }

    @Test
    fun `constant predicates can force a numeric alternative`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (assert (or (> 0 1) (> x 2)))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val atoms = assertNotNull(formula.witnessAtoms(arithmetic, 128))

        assertEquals(PolynomialRelation.GT, atoms.single { it.polynomial.mainVariable != null }.relation)
        assertNull(formula.conjunctiveAtoms(arithmetic, 128))
    }

    @Test
    fun `propagated witness guidance withholds an oversized predicate set`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real) (declare-const y Real) (declare-const b Bool)
            (assert (= b (and (> x 0) (> y 0))))
            (assert b)
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val atoms = formula.witnessAtoms(arithmetic, 1)

        assertNull(atoms)
    }

    @Test
    fun `interrupted Boolean guidance exposes no partial predicate set`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real) (declare-const b Bool)
            (assert (or (and b (> x 0)) (and (not b) (< x 0))))
            """.trimIndent(),
        )
        val formula = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
        var spent = 0L
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = spent >= 16L
            override fun workMeter(): WorkMeter = WorkMeter { spent += it }
        }
        val arithmetic = PolynomialArithmetic(cancellation)

        assertFailsWith<PolynomialInterrupted> { formula.witnessAtoms(arithmetic, 128) }

        assertEquals(16L, spent)
        assertEquals(emptyList(), formula.witnessAtoms(PolynomialArithmetic(), 128))
    }

    @Test
    fun `witness guidance retains only unconditional signed atoms`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real) (declare-const y Real)
            (assert (and (= y (* x x)) (not (<= x 0))
              (or (= y 5) (= x 6)) (not (and (< x 0) (< y 0)))))
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val atoms = assertNotNull(formula.witnessAtoms(arithmetic, 128))

        assertEquals(2, atoms.size)
        assertEquals(setOf(PolynomialRelation.EQ, PolynomialRelation.GT), atoms.map { it.relation }.toSet())
        assertNull(formula.conjunctiveAtoms(arithmetic, 128))
    }

    @Test
    fun `witness guidance admission withholds partial atoms`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (assert (and (> x 0) (> y 0)))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val atoms = formula.witnessAtoms(arithmetic, 1)

        assertNull(atoms)
    }

    @Test
    fun `cancelled witness guidance exposes no partial atoms`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (> x 0))")
        val formula = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
        val arithmetic = PolynomialArithmetic(Cancellation { true })

        assertFailsWith<PolynomialInterrupted> { formula.witnessAtoms(arithmetic, 128) }
    }

    @Test
    fun `private selections should preserve shared Boolean branch correlation`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real) (declare-const b Bool)
            (assert (= (ite b (* x x) 3) 4))
            (assert (= (ite b 5 (* x x)) 4))
            """.trimIndent(),
        )
        val fixed = mapOf(0 to BigFraction.ofLong(2), 1 to BigFraction.ofLong(4), 2 to BigFraction.ofLong(4))

        val result = solveQfNra(
            source.formula,
            source.boolCount,
            source.realCount,
            condition = PolynomialCondition(lower = fixed, upper = fixed),
        )

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `selection invocations should retract Boolean pins between exact completions`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real) (declare-const r Real) (declare-const b Bool)
            (assert (= r (ite b (* x x) 3)))
            """.trimIndent(),
        )
        val invocation = PolynomialTheoryService(
            source.formula,
            source.boolCount,
            source.realCount,
            mapOf(0 to BigFraction.ofLong(2)),
        ).invocation()

        try {
            for ((branch, expected) in listOf(true to 4L, false to 3L, true to 4L)) {
                val result = invocation.check(PolynomialCondition(bools = mapOf(0 to branch)))

                val assignment = assertIs<QfNraResult.Satisfiable>(result).assignment
                assertEquals(BigFraction.ofLong(expected), assignment.realValue(1).rational)
                assertEquals(branch, assignment.boolValue(0))
            }
        } finally {
            invocation.close()
        }
    }

    @Test
    fun `cancelled private selection encoding should expose no partial normalized formula`() {
        val source = PolynomialSelection(
            SourceRealId(0),
            PolynomialRealFormula.BooleanVariable(SourceBoolId(0)),
            PolynomialExpression.Constant.of(bigIntOf(1)),
            PolynomialExpression.Constant.of(bigIntOf(2)),
        )
        val arithmetic = PolynomialArithmetic(Cancellation { true })

        assertFailsWith<PolynomialInterrupted> { PolynomialFormula.normalize(source, arithmetic) }
    }
}
