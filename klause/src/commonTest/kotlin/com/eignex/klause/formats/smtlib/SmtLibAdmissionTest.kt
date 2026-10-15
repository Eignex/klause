package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialSelection
import com.eignex.klause.ir.QuadraticCoefficient
import com.eignex.klause.ir.QuadraticObjectiveTerm
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.solver.pipeline.pipelineRequest
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.CharSource
import com.eignex.klause.util.StringCharSource
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SmtLibAdmissionTest {
    @Test
    fun `an unrelated open linear integer should preserve a bounded mixed product source`() {
        val input = SmtLibAdmission.parse(
            "(declare-const n Int) (declare-const q Int) (declare-const x Real) (declare-const y Real) " +
                "(assert (and (>= n 1) (<= n 2) (>= q 0) (= x 2) (= y 4))) " +
                "(assert (= (* (to_real n) x) y)) (check-sat)",
        )
        val source = assertIs<SmtLibInput.Linear>(input).problem

        val result = BacktrackSolver.source(source.model).solve()

        val witness = assertIs<OpenTheoryResult.Sat>(result).assignment
        assertTrue(source.model.factors.any { it is RealProduct })
        assertEquals("2", witness.intValue(source.intVarNames.getValue("n")))
        assertTrue(witness.intValue(source.intVarNames.getValue("q")).toLong() >= 0)
        assertTrue(source.model.intBounds.isOpenUpper(source.intVarNames.getValue("q")))
    }

    @Test
    fun `polynomial fallback should retain numeric selection guards and ordered branches`() {
        for (sort in listOf("Real", "Int")) {
            val input = SmtLibAdmission.parse(
                """
                (declare-const x $sort) (declare-const b Bool)
                (define-fun selected () $sort (ite b (* x x) 3))
                (assert (= selected 4)) (check-sat)
                """.trimIndent(),
            )
            val formula = if (sort == "Real") {
                assertIs<SmtLibInput.Polynomial>(input).problem.formula
            } else {
                assertIs<SmtLibInput.NonlinearInteger>(input).problem.formula
            }

            val selection = assertIs<PolynomialRealFormula.And>(formula).operands
                .filterIsInstance<PolynomialSelection>().single()

            assertEquals(SourceRealId(1), selection.result)
            assertEquals(PolynomialRealFormula.BooleanVariable(SourceBoolId(0)), selection.condition)
            val x = PolynomialExpression.Variable(SourceRealId(0))
            assertEquals(PolynomialExpression.Product(listOf(x, x)), selection.whenTrue)
        }
    }

    @Test
    fun `proved operand bounds should retain native products and finite distinctness before lowering`() {
        val input = SmtLibAdmission.parse(
            "(declare-const n Int) (declare-const m Int) (declare-const k Int) " +
                "(declare-const x Real) (declare-const y Real) " +
                "(assert (and (>= m 0) (<= m 1) (>= k 0) (<= k 2) (or (= k 0) (= k 2)) (= n (+ m 1)))) " +
                "(assert (distinct n m k)) (assert (= x 2)) (assert (= y 4)) " +
                "(assert (= (* (to_real n) x) y)) (check-sat)",
        )
        val source = assertIs<SmtLibInput.Linear>(input).problem

        val witness = assertIs<OpenTheoryResult.Sat>(BacktrackSolver.source(source.model).solve()).assignment

        assertTrue(source.model.factors.any { it is RealProduct })
        assertTrue(source.model.factors.any { it is AllDifferent })
        assertEquals("2", witness.intValue(source.intVarNames.getValue("n")))
        assertEquals("1", witness.intValue(source.intVarNames.getValue("m")))
        assertEquals("0", witness.intValue(source.intVarNames.getValue("k")))
    }

    @Test
    fun `genuine open nonlinear integers should retain the original exact source`() {
        val input = SmtLibAdmission.parse(
            "(declare-const n Int) (declare-const x Real) (declare-const y Real) " +
                "(assert (>= n 0)) (assert (= x 2)) (assert (= (* (to_real n) x) y)) (check-sat)",
        )

        val source = assertIs<SmtLibInput.NonlinearInteger>(input).problem

        assertEquals(mapOf("n" to 0), source.intVarNames)
        assertEquals(mapOf("x" to 0, "y" to 1), source.realVarNames)
    }

    @Test
    fun `cancellation during nonlinear bound closure should withhold source admission`() {
        var work = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = work > 0
            override fun workMeter(): WorkMeter = WorkMeter { work += it }
        }

        val input = SmtLibAdmission.parse(
            "(declare-const n Int) (declare-const m Int) (declare-const x Real) " +
                "(assert (and (>= m 0) (<= m 1) (= n (+ m 1)))) " +
                "(assert (= (* (to_real n) x) 4)) (check-sat)",
            cancellation = stop,
        )

        assertEquals(SmtLibInput.Interrupted, input)
    }

    @Test
    fun `unused helper arguments should retain their log domains`() {
        val input = SmtLibAdmission.parse(
            "(define-fun f ((x Real)) Real 1) (assert (= (f (log 0)) 1)) (check-sat)",
        )

        val source = assertIs<SmtLibInput.Transcendental>(input).problem
        val execution = OpenTheoryPipeline.execute(source.pipelineRequest(), engine = FiniteEngine.BACKTRACK)

        assertIs<OpenTheoryResult.Unsat>(execution.result)
    }

    @Test
    fun `unused let bindings should retain their log domains`() {
        val input = SmtLibAdmission.parse("(assert (let ((x (log 0))) true)) (check-sat)")

        val source = assertIs<SmtLibInput.Transcendental>(input).problem
        val execution = OpenTheoryPipeline.execute(source.pipelineRequest(), engine = FiniteEngine.BACKTRACK)

        assertIs<OpenTheoryResult.Unsat>(execution.result)
    }

    @Test
    fun `inactive conditional branches should not require unused binding domains`() {
        val input = SmtLibAdmission.parse(
            "(declare-const b Bool) (assert b) (assert (ite b true (let ((x (log 0))) true))) (check-sat)",
        )

        val source = assertIs<SmtLibInput.Transcendental>(input).problem
        val execution = OpenTheoryPipeline.execute(source.pipelineRequest(), engine = FiniteEngine.BACKTRACK)

        assertIs<OpenTheoryResult.Sat>(execution.result)
    }

    @Test
    fun `linear sources should retain one streaming parse`() {
        var opens = 0

        val input = SmtLibAdmission.parse({
            opens++
            StringCharSource("(declare-const x Real) (assert (< x 1)) (check-sat)")
        })

        assertIs<SmtLibInput.Linear>(input)
        assertEquals(1, opens)
    }

    @Test
    fun `unused real helpers should retain one linear streaming parse`() {
        for (definition in listOf(
            "(define-fun f ((x Real)) Real (+ x 1))",
            "(define-fun f ((x Real)) Real (* x x))",
            "(define-fun f () Real 3)",
        )) {
            var opens = 0

            val input = SmtLibAdmission.parse({
                opens++
                StringCharSource("$definition (declare-const x Real) (assert (= (* (+ 1 1) x) 6)) (check-sat)")
            })

            val source = assertIs<SmtLibInput.Linear>(input).problem
            assertEquals(mapOf("x" to 0), source.realVarNames)
            assertEquals(1, opens)
        }
    }

    @Test
    fun `unused helper definitions should retain lexical sort validation`() {
        for (definition in listOf(
            "(define-fun f ((x Real)) Bool (+ x 1))",
            "(define-fun f ((x Real)) Real (> x 1))",
            "(define-fun f () Bool 3)",
        )) {
            assertFailsWith<UnsupportedSmtException> {
                SmtLibAdmission.parse("$definition (declare-const x Real) (assert (= x 1)) (check-sat)")
            }
        }
    }

    @Test
    fun `nested and zero arity helper references should retain lexical fallback`() {
        for ((definition, assertion) in listOf(
            "(define-fun f ((y Real)) Real (+ y 1))" to "(= (f x) 2)",
            "(define-fun f ((y Real)) Real (+ y 1)) (define-fun g ((y Real)) Real (f y))" to
                "(or false (= (g x) 2))",
            "(define-fun f () Real 2)" to "(= x f)",
        )) {
            var opens = 0

            val input = SmtLibAdmission.parse({
                opens++
                StringCharSource("(declare-const x Real) $definition (assert $assertion) (check-sat)")
            })

            val source = assertIs<SmtLibInput.Polynomial>(input).problem
            assertEquals(mapOf("x" to 0), source.realVarNames)
            assertEquals(2, opens)
        }
    }

    @Test
    fun `unused nonlinear helpers should preserve nonincremental session rejection`() {
        for (suffix in listOf("(check-sat) (check-sat)", "(check-sat) (assert (= x 2))")) {
            assertFailsWith<UnsupportedSmtException> {
                SmtLibAdmission.parse(
                    "(define-fun f ((y Real)) Real (* y y)) " +
                        "(declare-const x Real) (assert (= x 1)) $suffix",
                )
            }
        }
    }

    @Test
    fun `cancellation after source exhaustion should withhold unused helper admission`() {
        var exhausted = false
        var opens = 0
        val cancellation = Cancellation { exhausted }

        val input = SmtLibAdmission.parse(
            {
                opens++
                val source = StringCharSource(
                    "(define-fun f ((y Real)) Real (+ y 1)) " +
                        "(declare-const x Real) (assert (= x 2)) (check-sat)",
                )
                object : CharSource {
                    override fun next(): String? = source.next().also { if (it == null) exhausted = true }
                }
            },
            cancellation = cancellation,
        )

        assertEquals(SmtLibInput.Interrupted, input)
        assertEquals(1, opens)
    }

    @Test
    fun `polynomial fallback should close its rejected source before reopening`() {
        val text = "(declare-const x Real) (assert (= (* x x) 2)) (check-sat)"
        var opens = 0
        var firstClosed = false

        val input = SmtLibAdmission.parse({
            opens++
            if (opens == 1) {
                val source = StringCharSource(text)
                object : CharSource, AutoCloseable {
                    override fun next(): String? = source.next()
                    override fun close() {
                        firstClosed = true
                    }
                }
            } else {
                assertTrue(firstClosed)
                StringCharSource(text)
            }
        })

        assertIs<SmtLibInput.Polynomial>(input)
        assertEquals(2, opens)
    }

    @Test
    fun `fallback should drain a source without a close contract before reopening`() {
        var opens = 0
        var firstExhausted = false

        val input = SmtLibAdmission.parse({
            opens++
            if (opens == 1) {
                val chunks = ArrayDeque(
                    listOf("(declare-const x Real) (assert (= (* x x) 2))", "(check-sat)"),
                )
                object : CharSource {
                    override fun next(): String? = chunks.removeFirstOrNull().also {
                        if (it == null) firstExhausted = true
                    }
                }
            } else {
                assertTrue(firstExhausted)
                StringCharSource("(declare-const x Real) (assert (= (* x x) 2)) (check-sat)")
            }
        })

        assertIs<SmtLibInput.Polynomial>(input)
    }

    @Test
    fun `pre cancelled admission should not open a source`() {
        val input = SmtLibAdmission.parse(
            { error("cancelled admission must not open a source") },
            cancellation = Cancellation { true },
        )

        assertEquals(SmtLibInput.Interrupted, input)
    }

    @Test
    fun `interrupted admission should close its source without retrying`() {
        var stopped = false
        var closed = false
        var opens = 0

        val input = SmtLibAdmission.parse(
            {
                opens++
                object : CharSource, AutoCloseable {
                    override fun next(): String? {
                        stopped = true
                        return "(declare-const x Real)"
                    }
                    override fun close() {
                        closed = true
                    }
                }
            },
            cancellation = Cancellation { stopped },
        )

        assertEquals(SmtLibInput.Interrupted, input)
        assertEquals(1, opens)
        assertTrue(closed)
    }

    @Test
    fun `interrupted admission should not drain a non closeable tail`() {
        var reads = 0
        var stopped = false
        var opens = 0

        val input = SmtLibAdmission.parse(
            {
                opens++
                object : CharSource {
                    override fun next(): String? {
                        reads++
                        stopped = true
                        return "(declare-const x Real)"
                    }
                }
            },
            cancellation = Cancellation { stopped },
        )

        assertEquals(SmtLibInput.Interrupted, input)
        assertEquals(1, opens)
        assertEquals(1, reads)
    }

    @Test
    fun `work exhaustion in lexical fallback should close the source without publishing metadata`() {
        var opens = 0
        var closes = 0
        var charged = 0L
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = opens == 2 && charged >= 1
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }

        val input = SmtLibAdmission.parse(
            {
                opens++
                val source = StringCharSource(
                    "(declare-const x Real) (define-fun f ((y Real)) Real (* x y)) " +
                        "(assert (let ((x 7)) (= (f (+ 1 1)) 14))) (check-sat)",
                )
                object : CharSource, AutoCloseable {
                    override fun next(): String? = source.next()
                    override fun close() {
                        closes++
                    }
                }
            },
            cancellation = cancellation,
        )

        assertEquals(SmtLibInput.Interrupted, input)
        assertEquals(2, opens)
        assertEquals(2, closes)
        assertEquals(1L, charged)
    }

    @Test
    fun `lexical fallback should reject an assertion using a later helper`() {
        assertFailsWith<UnsupportedSmtException> {
            SmtLibAdmission.parse(
                "(assert (= (f 1) 1)) (define-fun f ((x Real)) Real x) (check-sat)",
            )
        }
    }

    @Test
    fun `integer helper sources should retain the linear route`() {
        val input = SmtLibAdmission.parse(
            "(declare-const x Int) (define-fun f ((y Int)) Int (+ y 1)) (assert (= (f x) 2)) (check-sat)",
        )

        assertIs<SmtLibInput.Linear>(input)
    }

    @Test
    fun `integer helper sorts without integer declarations should retain linear admission`() {
        val input = SmtLibAdmission.parse(
            "(define-fun f ((n Int)) Int (+ n 1)) (declare-const x Real) " +
                "(assert (= x (to_real (f 2)))) (check-sat)",
        )

        assertIs<SmtLibInput.Linear>(input)
    }

    @Test
    fun `nonlinear mixed integer sources should retain their integer coordinate semantics`() {
        val input = SmtLibAdmission.parse(
            "(declare-const x Real) (declare-const n Int) (assert (= (* x x) (to_real n))) (check-sat)",
        )

        val source = assertIs<SmtLibInput.NonlinearInteger>(input).problem
        assertEquals(mapOf("n" to 0), source.intVarNames)
        assertEquals(mapOf("x" to 0), source.realVarNames)
    }

    @Test
    fun `nonlinear finite sessions should reject source changes after a satisfaction query`() {
        val declaration = "(declare-const n Int) (assert (<= 1 n 3)) "
        val suffixes = listOf(
            "(assert (= (* n n) 4)) (check-sat) (assert (= n 3))",
            "(assert (= (* n n) 4)) (check-sat) (check-sat)",
            "(assert (= (^ n 0) 1)) (exit) (assert false)",
        )

        for (suffix in suffixes) {
            assertFailsWith<UnsupportedSmtException> { SmtLibAdmission.parse(declaration + suffix) }
        }
    }

    @Test
    fun `bounded nonlinear sources should not reinterpret signed numeral symbols`() {
        val source = "(declare-const x Int) (declare-const y Int) " +
            "(assert (and (<= 1 x 3) (<= 1 y 3) (= (* x y) -2)))"

        assertFailsWith<UnsupportedSmtException> { SmtLibAdmission.parse(source) }
    }

    @Test
    fun `nonlinear assertion scope should exclude later source declarations`() {
        val source = "(assert (= (* x y) 6)) (declare-const x Int) (declare-const y Int) " +
            "(assert (and (<= 1 x 3) (<= 1 y 3)))"

        assertFailsWith<UnsupportedSmtException> { SmtLibAdmission.parse(source) }
    }

    @Test
    fun `nonlinear declarations should preserve canonical quoted name identity`() {
        val source = "(declare-const x Int) (declare-const |x| Int) " +
            "(assert (and (<= 1 x 3) (<= 1 |x| 3) (= (* x |x|) 6)))"

        assertFailsWith<UnsupportedSmtException> { SmtLibAdmission.parse(source) }
    }

    @Test
    fun `function admission should preserve original declaration identities`() {
        val input = SmtLibAdmission.parse(
            "(declare-const i Int) (declare-const y Real) (assert (= y (exp (to_real i)))) (check-sat)",
        )

        val source = assertIs<SmtLibInput.Transcendental>(input).problem

        assertEquals(mapOf("i" to 0), source.intVarNames)
        assertEquals(mapOf("y" to 0), source.realVarNames)
    }

    @Test
    fun `lexical helpers should preserve original function equations`() {
        val input = SmtLibAdmission.parse(
            "(declare-const x Real) (define-fun f ((t Real)) Real (exp t)) (assert (= (f x) 2)) (check-sat)",
        )
        val source = assertIs<SmtLibInput.Transcendental>(input).problem

        val result = OpenTheoryPipeline.execute(source.pipelineRequest(), engine = FiniteEngine.BACKTRACK).result

        assertEquals("log(2)", assertIs<OpenTheoryResult.Sat>(result).assignment.realValue(0))
    }

    @Test
    fun `numeric conditionals should keep inactive log branches outside the source domain`() {
        val input = SmtLibAdmission.parse(
            "(declare-const b Bool) (assert b) (assert (= (ite b 1 (log 0)) 1)) (check-sat)",
        )
        val source = assertIs<SmtLibInput.Transcendental>(input).problem

        val result = OpenTheoryPipeline.execute(source.pipelineRequest(), engine = FiniteEngine.BACKTRACK).result

        assertTrue(assertIs<OpenTheoryResult.Sat>(result).assignment.boolValue(0))
    }

    @Test
    fun `Boolean simplification should not erase a required function domain`() {
        val input = SmtLibAdmission.parse("(assert (or true (= (log 0) 1))) (check-sat)")
        val source = assertIs<SmtLibInput.Transcendental>(input).problem

        val result = OpenTheoryPipeline.execute(source.pipelineRequest(), engine = FiniteEngine.BACKTRACK).result

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `zero powers should retain their original function domain`() {
        val input = SmtLibAdmission.parse("(assert (= (^ (log 0) 0) 1)) (check-sat)")
        val source = assertIs<SmtLibInput.Transcendental>(input).problem

        val result = OpenTheoryPipeline.execute(source.pipelineRequest(), engine = FiniteEngine.BACKTRACK).result

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `function names shadowed by polynomial helpers should keep polynomial source authority`() {
        val input = SmtLibAdmission.parse(
            "(define-fun exp ((t Real)) Real (* t t)) (declare-const x Real) (assert (= (exp x) 2)) (check-sat)",
        )

        assertIs<SmtLibInput.Polynomial>(input)
    }

    @Test
    fun `source objectives should be retained when exact function optimization is unavailable`() {
        val input = SmtLibAdmission.parse(
            "(declare-const x Real) (assert (> (exp x) 0)) (minimize (* x x)) (check-sat)",
        )
        val source = assertIs<SmtLibInput.Transcendental>(input).problem

        val result = OpenTheoryPipeline.execute(source.pipelineRequest()).result

        assertTrue(source.hasObjective)
        assertEquals(TerminationReason.Unsupported, assertIs<OpenTheoryResult.Unknown>(result).reason)
    }

    @Test
    fun `function source assumptions should retain original Boolean correlation`() {
        val input = SmtLibAdmission.parse(
            "(declare-const b Bool) (declare-const x Real) (assert (= (exp x) 2)) (check-sat-assuming (b))",
        )
        val source = assertIs<SmtLibInput.Transcendental>(input).problem

        val result = OpenTheoryPipeline.execute(source.pipelineRequest(), engine = FiniteEngine.BACKTRACK).result

        assertTrue(assertIs<OpenTheoryResult.Sat>(result).assignment.boolValue(0))
    }

    @Test
    fun `mixed polynomial objective admission should preserve exact source coefficients and direction`() {
        for ((sense, zeroSquare) in ObjectiveSense.entries.flatMap { sense -> listOf(sense to false, sense to true) }) {
            val direction = if (sense == ObjectiveSense.MINIMIZE) "minimize" else "maximize"
            val term = if (zeroSquare) "(+ (/ y 10) (* 0 x x))" else "(/ y 10)"
            val text = """
                (declare-const i Int) (declare-const x Real) (declare-const y Real)
                (assert (and (>= i 0) (<= i 1))) (assert (= x 1))
                (assert (= (* (to_real i) x) y))
                ($direction (+ (/ 7 3) $term)) (check-sat)
            """.trimIndent()

            val captured = assertIs<SmtLibInput.Quadratic>(SmtLibAdmission.parse(text))

            assertIs<SmtLibInput.NonlinearInteger>(captured.source)
            assertEquals(sense, captured.sense)
            assertEquals(QuadraticCoefficient.of(bigIntOf(7), bigIntOf(3)), captured.objective.constant)
            assertEquals(
                listOf(
                    QuadraticObjectiveTerm(
                        QuadraticCoefficient.of(bigIntOf(1), bigIntOf(10)),
                        QuadraticVariable.Real(SourceRealId(1)),
                    ),
                ),
                captured.objective.terms,
            )
        }
    }
}
