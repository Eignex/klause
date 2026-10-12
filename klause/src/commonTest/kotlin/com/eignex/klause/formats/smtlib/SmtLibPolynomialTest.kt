package com.eignex.klause.formats.smtlib

import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.lowering.PolynomialSourceInterrupted
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.CharSource
import com.eignex.klause.util.StringCharSource
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SmtLibPolynomialTest {
    @Test
    fun `pre cancelled lowering does not read its source`() {
        val source = object : CharSource {
            override fun next(): String? = error("cancelled source must not be read")
        }

        assertFailsWith<PolynomialSourceInterrupted> { SmtLibPolynomial.parse(source, Cancellation { true }) }
    }

    @Test
    fun `coefficient work exhaustion interrupts without publishing a formula`() {
        var charged = 0L
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = charged >= 2
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }

        assertFailsWith<PolynomialSourceInterrupted> {
            SmtLibPolynomial.parse("(assert (= (+ 1 2 3 4) 10))", cancellation)
        }
        assertEquals(2L, charged)
    }

    @Test
    fun `constant powers honor the caller coefficient work allowance`() {
        var charged = 0L
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = charged >= 3
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }

        assertFailsWith<PolynomialSourceInterrupted> {
            SmtLibPolynomial.parse("(assert (= (^ 2 128) 1))", cancellation)
        }
        assertEquals(3L, charged)
    }

    @Test
    fun `decimal and rational source coefficients remain exact beyond machine words`() {
        val parsed = SmtLibPolynomial.parse(
            "(assert (= 123456789012345678901234567890.125 (/ 987654312098765431209876543121 8)))",
        )

        val atom = assertIs<PolynomialRealFormula.Atom>(parsed.formula)
        val expected = PolynomialExpression.Constant.of(parseBigInt("987654312098765431209876543121"), bigIntOf(8))
        assertEquals(expected, atom.left)
        assertEquals(expected, atom.right)
    }

    @Test
    fun `products retain repeated variables and declaration identities`() {
        val parsed = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (assert (= (* x x y) 1))",
        )

        val atom = assertIs<PolynomialRealFormula.Atom>(parsed.formula)
        assertEquals(
            PolynomialExpression.Product(
                listOf(
                    PolynomialExpression.Variable(SourceRealId(0)), PolynomialExpression.Variable(SourceRealId(0)),
                    PolynomialExpression.Variable(SourceRealId(1)),
                ),
            ),
            atom.left,
        )
        assertTrue(parsed.hasNonlinearTerms)
        assertEquals(mapOf("x" to 0, "y" to 1), parsed.realVarNames)
    }

    @Test
    fun `nonnegative powers retain their exact exponent`() {
        val parsed = SmtLibPolynomial.parse("(declare-const x Real) (assert (= (^ x 17) 2))")

        val atom = assertIs<PolynomialRealFormula.Atom>(parsed.formula)
        assertEquals(PolynomialExpression.Power(PolynomialExpression.Variable(SourceRealId(0)), 17), atom.left)
        assertTrue(parsed.hasNonlinearTerms)
    }

    @Test
    fun `power exponents resolve exact constants in lexical and helper scopes`() {
        val cases = listOf(
            "(declare-const x Real) (assert (= (^ x (+ 1 1)) 2))",
            "(declare-const x Real) (assert (let ((n 2)) (= (^ x n) 2)))",
            "(declare-const x Real) (define-fun f ((n Real)) Real (^ x n)) (assert (= (f 2) 2))",
        )
        for (source in cases) {
            val parsed = SmtLibPolynomial.parse(source)

            val atom = assertIs<PolynomialRealFormula.Atom>(parsed.formula)
            assertEquals(PolynomialExpression.Power(PolynomialExpression.Variable(SourceRealId(0)), 2), atom.left)
        }
    }

    @Test
    fun `unary signs and division by folded constants preserve rational values`() {
        val parsed = SmtLibPolynomial.parse("(assert (= (/ (- 1) (+ 2 4)) (- 0.166666666666666667)))")

        val atom = assertIs<PolynomialRealFormula.Atom>(parsed.formula)
        assertEquals(PolynomialExpression.Constant.of(bigIntOf(-1), bigIntOf(6)), atom.left)
        assertEquals(
            PolynomialExpression.Constant.of(parseBigInt("-166666666666666667"), parseBigInt("1000000000000000000")),
            atom.right,
        )
    }

    @Test
    fun `constant powers remain exact in rational divisors`() {
        val parsed = SmtLibPolynomial.parse("(assert (= (/ 1 (^ 2 7)) 0.0078125))")

        val atom = assertIs<PolynomialRealFormula.Atom>(parsed.formula)
        assertEquals(PolynomialExpression.Constant.of(bigIntOf(1), bigIntOf(128)), atom.left)
        assertEquals(atom.left, atom.right)
    }

    @Test
    fun `real comparisons preserve strictness and equality polarity`() {
        val cases = listOf(
            "=" to PolynomialRelation.EQ, "distinct" to PolynomialRelation.NE,
            "<" to PolynomialRelation.LT, "<=" to PolynomialRelation.LE,
            ">" to PolynomialRelation.GT, ">=" to PolynomialRelation.GE,
        )
        for ((operator, relation) in cases) {
            val parsed = SmtLibPolynomial.parse("(declare-const x Real) (assert ($operator (* x x) 2))")

            assertEquals(relation, assertIs<PolynomialRealFormula.Atom>(parsed.formula).relation)
        }
    }

    @Test
    fun `chained comparisons constrain consecutive real operands`() {
        val parsed = SmtLibPolynomial.parse("(declare-const x Real) (assert (< 0 x 2))")

        val terms = assertIs<PolynomialRealFormula.And>(parsed.formula).operands
        assertEquals(2, terms.size)
        val first = assertIs<PolynomialRealFormula.Atom>(terms[0])
        val second = assertIs<PolynomialRealFormula.Atom>(terms[1])
        assertEquals(first.right, second.left)
        assertEquals(PolynomialRelation.LT, first.relation)
        assertEquals(PolynomialRelation.LT, second.relation)
    }

    @Test
    fun `distinct constrains every pair rather than adjacent operands`() {
        val parsed = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (assert (distinct x y 0))",
        )

        val terms = assertIs<PolynomialRealFormula.And>(parsed.formula).operands
        assertEquals(3, terms.size)
        assertTrue(terms.all { assertIs<PolynomialRealFormula.Atom>(it).relation == PolynomialRelation.NE })
        val first = assertIs<PolynomialRealFormula.Atom>(terms[0])
        val nonadjacent = assertIs<PolynomialRealFormula.Atom>(terms[1])
        assertEquals(first.left, nonadjacent.left)
        assertEquals(PolynomialExpression.Constant.of(bigIntOf(0)), nonadjacent.right)
    }

    @Test
    fun `Boolean structure retains polynomial atoms under negation and disjunction`() {
        val parsed = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const b Bool) (assert (or b (not (> (* x x) 2))))",
        )

        val disjunction = assertIs<PolynomialRealFormula.Or>(parsed.formula)
        assertEquals(PolynomialRealFormula.BooleanVariable(SourceBoolId(0)), disjunction.operands[0])
        val negation = assertIs<PolynomialRealFormula.Not>(disjunction.operands[1])
        assertEquals(PolynomialRelation.GT, assertIs<PolynomialRealFormula.Atom>(negation.operand).relation)
    }

    @Test
    fun `Boolean equality xor implication and conditionals preserve truth tables`() {
        for (left in listOf(false, true)) {
            for (right in listOf(false, true)) {
                val cases = listOf(
                    "(= $left $right)" to (left == right),
                    "(xor $left $right)" to (left != right),
                    "(=> $left $right)" to (!left || right),
                    "(ite $left $right false)" to (left && right),
                )
                for ((expression, expected) in cases) {
                    val parsed = SmtLibPolynomial.parse("(assert $expression)")

                    assertEquals(PolynomialRealFormula.Constant(expected), parsed.formula)
                }
            }
        }
    }

    @Test
    fun `implication chains associate to the right`() {
        val parsed = SmtLibPolynomial.parse("(assert (=> false true false))")

        assertEquals(PolynomialRealFormula.Constant(true), parsed.formula)
    }

    @Test
    fun `parallel let values read the enclosing scope`() {
        val parsed = SmtLibPolynomial.parse(
            "(declare-const x Real) (assert (let ((x 3) (y x)) (= (* y y) x)))",
        )

        val atom = assertIs<PolynomialRealFormula.Atom>(parsed.formula)
        val variable = PolynomialExpression.Variable(SourceRealId(0))
        assertEquals(PolynomialExpression.Product(listOf(variable, variable)), atom.left)
        assertEquals(PolynomialExpression.Constant.of(bigIntOf(3)), atom.right)
    }

    @Test
    fun `macro free variables use the definition scope rather than the caller scope`() {
        val parsed = SmtLibPolynomial.parse(
            "(declare-const x Real) (define-fun f ((y Real)) Real (* x y)) " +
                "(assert (let ((x 7)) (= (f 2) 14)))",
        )

        val atom = assertIs<PolynomialRealFormula.Atom>(parsed.formula)
        assertEquals(
            PolynomialExpression.Product(
                listOf(PolynomialExpression.Variable(SourceRealId(0)), PolynomialExpression.Constant.of(bigIntOf(2))),
            ),
            atom.left,
        )
    }

    @Test
    fun `interleaved declarations and helpers preserve retained assertion identities`() {
        val source = buildString {
            repeat(32) { index ->
                append("(declare-const x$index Real) ")
                append("(define-fun f$index ((x Real)) Real (* x x$index)) ")
                repeat(4) { append("(assert (let ((x$index 7)) (= (f$index 2) 14))) ") }
            }
        }

        val parsed = SmtLibPolynomial.parse(source)

        val terms = assertIs<PolynomialRealFormula.And>(parsed.formula).operands
        assertEquals(128, terms.size)
        for ((index, term) in terms.withIndex()) {
            val atom = assertIs<PolynomialRealFormula.Atom>(term)
            assertEquals(
                PolynomialExpression.Product(
                    listOf(
                        PolynomialExpression.Constant.of(bigIntOf(2)),
                        PolynomialExpression.Variable(SourceRealId(index / 4)),
                    ),
                ),
                atom.left,
            )
        }
    }

    @Test
    fun `nested local bindings shadow helpers without changing their definition scope`() {
        val parsed = SmtLibPolynomial.parse(
            "(declare-const x Real) (define-fun f () Real (* x x)) " +
                "(define-fun g ((x Real)) Real (let ((y x)) (+ y f))) " +
                "(assert (let ((x 7) (f 9)) (let ((x f)) (= (g x) f))))",
        )

        val atom = assertIs<PolynomialRealFormula.Atom>(parsed.formula)
        val variable = PolynomialExpression.Variable(SourceRealId(0))
        assertEquals(
            PolynomialExpression.Sum(
                listOf(
                    PolynomialExpression.Constant.of(bigIntOf(9)),
                    PolynomialExpression.Product(listOf(variable, variable)),
                ),
            ),
            atom.left,
        )
        assertEquals(PolynomialExpression.Constant.of(bigIntOf(9)), atom.right)
    }

    @Test
    fun `retained scopes reject later declarations and helpers`() {
        val cases = listOf(
            "(assert (= (* x x) 1)) (declare-const x Real)",
            "(assert (= f 1)) (define-fun f () Real 1)",
            "(assert (= (f 1) 1)) (define-fun f ((x Real)) Real x)",
            "(define-fun f () Real x) (declare-const x Real) (assert (= f 1))",
            "(define-fun f () Real g) (define-fun g () Real 1) (assert (= f 1))",
        )

        for (source in cases) {
            assertFailsWith<UnsupportedSmtException>(source) { SmtLibPolynomial.parse(source) }
        }
    }

    @Test
    fun `retained helper expansion honors cancellation during lowering`() {
        var charged = 0L
        var limited = false
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = limited && charged >= 1
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }
        val compiler = SmtLibPolynomialCompiler(cancellation)
        val reader = SExprReader(
            StringCharSource(
                "(declare-const x Real) (define-fun f ((y Real)) Real (* x y)) " +
                    "(assert (let ((x 7)) (= (f (+ 1 1)) 14)))",
            ),
        )
        while (true) compiler.command(reader.readCommandOrNull() ?: break)
        charged = 0
        limited = true

        assertFailsWith<PolynomialSourceInterrupted> { compiler.build() }
        assertEquals(1L, charged)
    }

    @Test
    fun `numeric conditionals retain source declarations separately from auxiliary reals`() {
        val parsed = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const b Bool) (assert (= (ite b (* x x) 3) 2))",
        )

        assertEquals(1, parsed.sourceRealCount)
        assertEquals(2, parsed.realCount)
        assertEquals(1, parsed.sourceBoolCount)
        assertEquals(mapOf("x" to 0), parsed.realVarNames)
        val terms = assertIs<PolynomialRealFormula.And>(parsed.formula).operands
        val assertion = assertIs<PolynomialRealFormula.Atom>(terms[0])
        assertEquals(PolynomialExpression.Variable(SourceRealId(1)), assertion.left)
        val trueGuard = assertIs<PolynomialRealFormula.Or>(terms[1])
        val falseGuard = assertIs<PolynomialRealFormula.Or>(terms[2])
        assertEquals(
            PolynomialRealFormula.Not(PolynomialRealFormula.BooleanVariable(SourceBoolId(0))), trueGuard.operands[0],
        )
        assertEquals(PolynomialRealFormula.BooleanVariable(SourceBoolId(0)), falseGuard.operands[0])
        assertEquals(assertion.left, assertIs<PolynomialRealFormula.Atom>(trueGuard.operands[1]).left)
        assertEquals(assertion.left, assertIs<PolynomialRealFormula.Atom>(falseGuard.operands[1]).left)
    }

    @Test
    fun `Boolean query assumptions constrain the emitted formula`() {
        val parsed = SmtLibPolynomial.parse(
            "(declare-const b Bool) (check-sat-assuming ((not b))) (get-model)",
        )

        assertEquals(PolynomialRealFormula.Not(PolynomialRealFormula.BooleanVariable(SourceBoolId(0))), parsed.formula)
    }

    @Test
    fun `unconstrained declarations retain source names and counts`() {
        val parsed = SmtLibPolynomial.parse("(declare-const x Real) (declare-const b Bool) (check-sat)")

        assertEquals(mapOf("x" to 0), parsed.realVarNames)
        assertEquals(mapOf("b" to 0), parsed.boolVarNames)
        assertEquals(1, parsed.sourceRealCount)
        assertEquals(1, parsed.sourceBoolCount)
        assertEquals(PolynomialRealFormula.Constant(true), parsed.formula)
    }

    @Test
    fun `quoted and unquoted occurrences share their source variable`() {
        val parsed = SmtLibPolynomial.parse("(declare-const |x| Real) (assert (= x |x|))")

        val atom = assertIs<PolynomialRealFormula.Atom>(parsed.formula)
        assertEquals(atom.left, atom.right)
        assertEquals(mapOf("x" to 0), parsed.realVarNames)
    }

    @Test
    fun `quoted numeric symbols cannot change numeral meaning`() {
        val parsed = SmtLibPolynomial.parse("(declare-const |2| Real) (assert (= |2| 2))")

        val atom = assertIs<PolynomialRealFormula.Atom>(parsed.formula)
        assertEquals(PolynomialExpression.Variable(SourceRealId(0)), atom.left)
        assertEquals(PolynomialExpression.Constant.of(bigIntOf(2)), atom.right)
    }

    @Test
    fun `linear real formulas remain distinguishable from nonlinear sources`() {
        val parsed = SmtLibPolynomial.parse("(declare-const x Real) (assert (<= (* (/ 1 3) x) 2))")

        assertFalse(parsed.hasNonlinearTerms)
    }

    @Test
    fun `a false Boolean guard does not admit unsupported arithmetic`() {
        assertFailsWith<UnsupportedSmtException> {
            SmtLibPolynomial.parse("(declare-const x Real) (assert (or true (= (/ 1 x) 2)))")
        }
    }

    @Test
    fun `unsupported polynomial constructs decline explicitly`() {
        val cases = listOf(
            "(declare-const x Int)",
            "(declare-const x Real) (assert (= (/ 1 x) 2))",
            "(assert (= (/ 1 0) 2))",
            "(declare-const x Real) (assert (= (^ x (- 1)) 2))",
            "(declare-const x Real) (assert (= (^ x 0.5) 2))",
            "(declare-const x Real) (declare-const n Real) (assert (= (^ x n) 2))",
            "(declare-const x Real) (define-fun f ((n Real)) Real (^ x n)) (assert (= (f 0.5) 2))",
            "(declare-const x Real) (assert (= (^ x 2147483648) 2))",
            "(declare-const x Real) (assert (= (sin x) 0))",
            "(assert (forall ((x Real)) (= x x)))",
            "(declare-const x Real) (assert (= (to_int x) 1))",
            "(declare-const x Real) (minimize (* x x x))",
            "(push 1)", "(get-value (x))", "(check-sat) (check-sat)", "(exit) (assert false)",
            "(check-sat) (assert true)",
            "(declare-const x Real) (check-sat-assuming ((> x 0)))",
        )
        for (source in cases) {
            assertFailsWith<UnsupportedSmtException>(source) { SmtLibPolynomial.parse(source) }
        }
    }

    @Test
    fun `ill sorted and undeclared polynomial terms are rejected`() {
        val cases = listOf(
            "(assert (= x 1))", "(assert (= x 1)) (declare-const x Real)",
            "(declare-const 2 Real)", "(declare-const \"x\" Real)",
            "(declare-const b Bool) (assert (= (* b b) 1))",
            "(declare-const b Bool) (assert (> b false))",
            "(declare-const b Bool) (assert (= b 1))",
            "(define-fun f ((x Real)) Bool x)",
            "(define-fun f ((x Real)) Real (f x))",
            "(define-fun f ((x Bool)) Bool x) (assert (f 1))",
            "(assert (= (ite true 1 false) 1))",
            "(assert (let ((x 1) (x 2)) (= x 1)))",
        )
        for (source in cases) {
            assertFailsWith<UnsupportedSmtException>(source) { SmtLibPolynomial.parse(source) }
        }
    }

    @Test
    fun `deep polynomial syntax lowers without call stack recursion`() {
        val source = buildString {
            append("(declare-const x Real) (assert (= ")
            repeat(1000) { append("(+ 1 ") }
            append('x')
            repeat(1000) { append(')') }
            append(" 0))")
        }

        val parsed = SmtLibPolynomial.parse(source)

        assertEquals(1, parsed.realCount)
        assertIs<PolynomialRealFormula.Atom>(parsed.formula)
    }
}
