package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialSelection
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.QuadraticCoefficient
import com.eignex.klause.ir.QuadraticObjectiveTerm
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.TranscendentalProblem
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.SourceProblemRoute
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.solver.pipeline.pipelineRequest
import com.eignex.klause.solver.pipeline.pipelineRoute
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.util.io.StringCharSource
import com.eignex.util.numbers.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SmtLibAdmissionTest {
    @Test
    fun `scalar let sharing should keep the lowered model proportional to the source`() {
        val levels = 4
        val text = buildString {
            append("(set-logic QF_IDL) (declare-const x Int) ")
            repeat(levels) { append("(declare-const p$it Bool) ") }
            append("(assert (let ((b0 (< x 1))) ")
            for (level in 1..levels) {
                append("(let ((b$level (ite p${level - 1} b${level - 1} (not b${level - 1})))) ")
            }
            append("b$levels")
            repeat(levels + 2) { append(')') }
        }

        val source = SmtLib.parse(text)

        assertTrue(source.model.numBoolVars <= levels * 8 + 2,
            "Shared scalar source created ${source.model.numBoolVars} Boolean variables")
    }

    @Test
    fun `inconsistent integer scalar equations should retain a linear source`() {
        val source = SmtLib.parse(
            """
            (set-info :smt-lib-version 2.6)
            (set-logic QF_LIA)
            (set-info :source | Leonardo de Moura |)
            (set-info :category "check")
            (set-info :status unsat)
            (set-info :notes |This benchmark is designed to check if the integer DP is complete.|)
            (declare-fun x1 () Int)
            (declare-fun x2 () Int)
            (declare-fun x3 () Int)
            (declare-fun x4 () Int)
            (assert (and (= (+ x1 x2) 8) (= x1 (* 3 x3)) (= x2 (* 6 x4))))
            (check-sat)
            (exit)
            """.trimIndent(),
        )

        assertIs<Problem>(source.source.originalSource)
        assertEquals(setOf("x1", "x2", "x3", "x4"), source.intVarNames.keys)
    }

    @Test
    fun `inconsistent integer premises should select a refuted route before nonlinear execution`() {
        for (product in listOf("", "(assert (= (* x3 x4) 0))")) {
            val source = SmtLib.parse(
                "(declare-const x1 Int) (declare-const x2 Int) (declare-const x3 Int) (declare-const x4 Int) " +
                    "(assert (= (+ x1 x2) 8)) (assert (= x1 (* 3 x3))) (assert (= x2 (* 6 x4))) $product",
            )

            assertIs<SourceProblemRoute.Refuted>(source.model.pipelineRoute())
        }
    }

    @Test
    fun `feasible integer scalar equations should retain exact linear witnesses`() {
        val source = SmtLib.parse(
            "(declare-const x1 Int) (declare-const x2 Int) (declare-const x3 Int) (declare-const x4 Int) " +
                "(assert (= (+ x1 x2) 9)) (assert (= x1 (* 3 x3))) (assert (= x2 (* 6 x4)))",
        )

        val result = BacktrackSolver.source(source.model).solve()

        val witness = assertIs<OpenTheoryResult.Sat>(result).assignment
        val values = source.intVarNames.mapValues { (_, id) -> witness.intValue(id).toLong() }
        assertEquals(9L, values.getValue("x1") + values.getValue("x2"))
        assertEquals(3 * values.getValue("x3"), values.getValue("x1"))
        assertEquals(6 * values.getValue("x4"), values.getValue("x2"))
    }

    @Test
    fun `refuted integer premises should not admit unsupported nonlinear syntax`() {
        assertFailsWith<UnsupportedSmtException> {
            SmtLib.parse("(declare-const x Int) (declare-const y Int) " +
                "(assert (= (* 2 x) (+ (* 2 y) 1))) (assert (= (^ x (- 1)) 0))")
        }
    }

    @Test
    fun `feasible open nonlinear premises should retain exact nonlinear source admission`() {
        val source = SmtLib.parse(
            "(declare-const x Int) (declare-const y Int) " +
                "(assert (= (* 2 x) (+ (* 2 y) 2))) (assert (= (* x y) 0))",
        )

        assertIs<NonlinearIntegerProblem>(source.source.originalSource)
    }

    @Test
    fun `refuted nonlinear premises should retain source objective units and direction`() {
        val source = SmtLib.parse(
            "(declare-const x Int) (declare-const y Int) " +
                "(assert (= (* 2 x) (+ (* 2 y) 1))) (assert (= (* x y) 0)) " +
                "(maximize (+ 7 (* 2 x)))",
        )

        assertEquals(ObjectiveSense.MAXIMIZE, source.sense)
        assertEquals(7L, assertNotNull(source.objective).constant)
        assertEquals(2L, assertNotNull(source.objective).intCoefficients[source.intVarNames.getValue("x")])
    }

    @Test
    fun `refuted integer premises should retain unsupported function objective capture`() {
        val source = SmtLib.parse(
            "(declare-const x Int) (declare-const y Int) " +
                "(assert (= (* 2 x) (+ (* 2 y) 1))) (assert (= (* x y) 0)) " +
                "(maximize (exp (to_real x)))",
        )

        val captured = assertIs<TranscendentalProblem>(source.source.originalSource)
        assertTrue(captured.hasObjective)
        assertEquals(ObjectiveSense.MAXIMIZE, captured.objectiveSense)
    }

    @Test
    fun `all SMT source representations should retain supplied settings`() {
        val settings = ProblemSettings()
        val texts = listOf(
            "(declare-const x Real) (assert (= x 1))",
            "(declare-const x Real) (assert (= (* x x) 2))",
            "(declare-const n Int) (assert (= (* n n) 4))",
            "(declare-const x Real) (assert (= x (exp 0)))",
        )

        for (text in texts) {
            val source = SmtLib.parse(text, settings = settings)

            assertSame(settings, source.source.settings)
        }
    }

    @Test
    fun `native source metadata should retain declaration spelling`() {
        val source = SmtLib.parse(
            "(declare-const |angle value| Real) (assert (= |angle value| (exp 0)))",
        )

        assertEquals(mapOf("|angle value|" to 0), source.realVarNames)
    }

    @Test
    fun `maximized affine source objectives should retain original units`() {
        val source = SmtLib.parse(
            "(declare-const i Int) (assert (<= 0 i 2)) (maximize (+ 7 (* 2 i)))",
        )

        val objective = assertNotNull(source.objective)

        assertEquals(ObjectiveSense.MAXIMIZE, source.sense)
        assertEquals(7L, objective.constant)
        assertEquals(listOf(2L), objective.intCoefficients.toList())
    }

    @Test
    fun `an unrelated open linear integer should preserve a bounded mixed product source`() {
        val input = SmtLib.parse(
            "(declare-const n Int) (declare-const q Int) (declare-const x Real) (declare-const y Real) " +
                "(assert (and (>= n 1) (<= n 2) (>= q 0) (= x 2) (= y 4))) " +
                "(assert (= (* (to_real n) x) y)) (check-sat)",
        )
        val source = (input)

        val result = BacktrackSolver.source(source.model).solve()

        val witness = assertIs<OpenTheoryResult.Sat>(result).assignment
        assertTrue(source.model.factors.any { it is RealProduct })
        assertEquals("2", witness.intValue(source.intVarNames.getValue("n")))
        assertTrue(witness.intValue(source.intVarNames.getValue("q")).toLong() >= 0)
        assertTrue(source.model.intBounds.isOpenUpper(source.intVarNames.getValue("q")))
    }

    @Test
    fun `native polynomial selection should retain numeric guards and ordered branches`() {
        for (sort in listOf("Real", "Int")) {
            val input = SmtLibPolynomial.parse(
                """
                (declare-const x $sort) (declare-const b Bool)
                (define-fun selected () $sort (ite b (* x x) 3))
                (assert (= selected 4)) (check-sat)
                """.trimIndent(), allowIntegers = true,
            )
            val formula = input.formula

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
        val input = SmtLib.parse(
            "(declare-const n Int) (declare-const m Int) (declare-const k Int) " +
                "(declare-const x Real) (declare-const y Real) " +
                "(assert (and (>= m 0) (<= m 1) (>= k 0) (<= k 2) (or (= k 0) (= k 2)) (= n (+ m 1)))) " +
                "(assert (distinct n m k)) (assert (= x 2)) (assert (= y 4)) " +
                "(assert (= (* (to_real n) x) y)) (check-sat)",
        )
        val source = (input)

        val witness = assertIs<OpenTheoryResult.Sat>(BacktrackSolver.source(source.model).solve()).assignment

        assertTrue(source.model.factors.any { it is RealProduct })
        assertTrue(source.model.factors.any { it is AllDifferent })
        assertEquals("2", witness.intValue(source.intVarNames.getValue("n")))
        assertEquals("1", witness.intValue(source.intVarNames.getValue("m")))
        assertEquals("0", witness.intValue(source.intVarNames.getValue("k")))
    }

    @Test
    fun `genuine open nonlinear integers should retain the original exact source`() {
        val input = SmtLib.parse(
            "(declare-const n Int) (declare-const x Real) (declare-const y Real) " +
                "(assert (>= n 0)) (assert (= x 2)) (assert (= (* (to_real n) x) y)) (check-sat)",
        )

        val source = assertIs<NonlinearIntegerProblem>((input).source.originalSource)

        assertEquals(mapOf("n" to 0), source.intVarNames)
        assertEquals(mapOf("x" to 0, "y" to 1), source.realVarNames)
    }

    @Test
    fun `unused helper arguments should retain their log domains`() {
        val input = SmtLib.parse(
            "(define-fun f ((x Real)) Real 1) (assert (= (f (log 0)) 1)) (check-sat)",
        )

        val source = assertIs<TranscendentalProblem>((input).source.originalSource)
        val execution = OpenTheoryPipeline.execute(source.pipelineRequest(), engine = FiniteEngine.BACKTRACK)

        assertIs<OpenTheoryResult.Unsat>(execution.result)
    }

    @Test
    fun `unused let bindings should retain their log domains`() {
        val input = SmtLib.parse("(assert (let ((x (log 0))) true)) (check-sat)")

        val source = assertIs<TranscendentalProblem>((input).source.originalSource)
        val execution = OpenTheoryPipeline.execute(source.pipelineRequest(), engine = FiniteEngine.BACKTRACK)

        assertIs<OpenTheoryResult.Unsat>(execution.result)
    }

    @Test
    fun `inactive conditional branches should not require unused binding domains`() {
        val input = SmtLib.parse(
            "(declare-const b Bool) (assert b) (assert (ite b true (let ((x (log 0))) true))) (check-sat)",
        )

        val source = assertIs<TranscendentalProblem>((input).source.originalSource)
        val execution = OpenTheoryPipeline.execute(source.pipelineRequest(), engine = FiniteEngine.BACKTRACK)

        assertIs<OpenTheoryResult.Sat>(execution.result)
    }

    @Test
    fun `unused helper definitions should retain lexical sort validation`() {
        for (definition in listOf(
            "(define-fun f ((x Real)) Bool (+ x 1))",
            "(define-fun f ((x Real)) Real (> x 1))",
            "(define-fun f () Bool 3)",
        )) {
            assertFailsWith<UnsupportedSmtException> {
                SmtLib.parse("$definition (declare-const x Real) (assert (= x 1)) (check-sat)")
            }
        }
    }

    @Test
    fun `nested and zero arity helper references should lower from one captured source`() {
        for ((definition, assertion) in listOf(
            "(define-fun f ((y Real)) Real (+ y 1))" to "(= (f x) 2)",
            "(define-fun f ((y Real)) Real (+ y 1)) (define-fun g ((y Real)) Real (f y))" to
                "(or false (= (g x) 2))",
            "(define-fun f () Real 2)" to "(= x f)",
        )) {
            var opens = 0

            val input = SmtLib.parse({
                opens++
                StringCharSource("(declare-const x Real) $definition (assert $assertion) (check-sat)")
            })

            assertEquals(mapOf("x" to 0), input.realVarNames)
            assertEquals(1, opens)
        }
    }

    @Test
    fun `unused nonlinear helpers should preserve nonincremental session rejection`() {
        for (suffix in listOf("(check-sat) (check-sat)", "(check-sat) (assert (= x 2))")) {
            assertFailsWith<UnsupportedSmtException> {
                SmtLib.parse(
                    "(define-fun f ((y Real)) Real (* y y)) " +
                        "(declare-const x Real) (assert (= x 1)) $suffix",
                )
            }
        }
    }

    @Test
    fun `lexical fallback should reject an assertion using a later helper`() {
        assertFailsWith<UnsupportedSmtException> {
            SmtLib.parse(
                "(assert (= (f 1) 1)) (define-fun f ((x Real)) Real x) (check-sat)",
            )
        }
    }

    @Test
    fun `integer helper sources should retain the linear route`() {
        val input = SmtLib.parse(
            "(declare-const x Int) (define-fun f ((y Int)) Int (+ y 1)) (assert (= (f x) 2)) (check-sat)",
        )

        assertIs<Problem>((input).source.originalSource)
    }

    @Test
    fun `integer helper sorts without integer declarations should retain linear admission`() {
        val input = SmtLib.parse(
            "(define-fun f ((n Int)) Int (+ n 1)) (declare-const x Real) " +
                "(assert (= x (to_real (f 2)))) (check-sat)",
        )

        assertIs<Problem>((input).source.originalSource)
    }

    @Test
    fun `nonlinear mixed integer sources should retain their integer coordinate semantics`() {
        val input = SmtLib.parse(
            "(declare-const x Real) (declare-const n Int) (assert (= (* x x) (to_real n))) (check-sat)",
        )

        assertIs<Problem>(input.source.originalSource)
        assertEquals(mapOf("n" to 0), input.intVarNames)
        assertEquals(mapOf("x" to 0), input.realVarNames)
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
            assertFailsWith<UnsupportedSmtException> { SmtLib.parse(declaration + suffix) }
        }
    }

    @Test
    fun `bounded nonlinear sources should not reinterpret signed numeral symbols`() {
        val source = "(declare-const x Int) (declare-const y Int) " +
            "(assert (and (<= 1 x 3) (<= 1 y 3) (= (* x y) -2)))"

        assertFailsWith<UnsupportedSmtException> { SmtLib.parse(source) }
    }

    @Test
    fun `nonlinear assertion scope should exclude later source declarations`() {
        val source = "(assert (= (* x y) 6)) (declare-const x Int) (declare-const y Int) " +
            "(assert (and (<= 1 x 3) (<= 1 y 3)))"

        assertFailsWith<UnsupportedSmtException> { SmtLib.parse(source) }
    }

    @Test
    fun `nonlinear declarations should preserve canonical quoted name identity`() {
        val source = "(declare-const x Int) (declare-const |x| Int) " +
            "(assert (and (<= 1 x 3) (<= 1 |x| 3) (= (* x |x|) 6)))"

        assertFailsWith<UnsupportedSmtException> { SmtLib.parse(source) }
    }

    @Test
    fun `function admission should preserve original declaration identities`() {
        val input = SmtLib.parse(
            "(declare-const i Int) (declare-const y Real) (assert (= y (exp (to_real i)))) (check-sat)",
        )

        val source = assertIs<TranscendentalProblem>((input).source.originalSource)

        assertEquals(mapOf("i" to 0), source.intVarNames)
        assertEquals(mapOf("y" to 0), source.realVarNames)
    }

    @Test
    fun `lexical helpers should preserve original function equations`() {
        val input = SmtLib.parse(
            "(declare-const x Real) (define-fun f ((t Real)) Real (exp t)) (assert (= (f x) 2)) (check-sat)",
        )
        val source = assertIs<TranscendentalProblem>((input).source.originalSource)

        val result = OpenTheoryPipeline.execute(source.pipelineRequest(), engine = FiniteEngine.BACKTRACK).result

        assertEquals("log(2)", assertIs<OpenTheoryResult.Sat>(result).assignment.realValue(0))
    }

    @Test
    fun `numeric conditionals should keep inactive log branches outside the source domain`() {
        val input = SmtLib.parse(
            "(declare-const b Bool) (assert b) (assert (= (ite b 1 (log 0)) 1)) (check-sat)",
        )
        val source = assertIs<TranscendentalProblem>((input).source.originalSource)

        val result = OpenTheoryPipeline.execute(source.pipelineRequest(), engine = FiniteEngine.BACKTRACK).result

        assertTrue(assertIs<OpenTheoryResult.Sat>(result).assignment.boolValue(0))
    }

    @Test
    fun `Boolean simplification should not erase a required function domain`() {
        val input = SmtLib.parse("(assert (or true (= (log 0) 1))) (check-sat)")
        val source = assertIs<TranscendentalProblem>((input).source.originalSource)

        val result = OpenTheoryPipeline.execute(source.pipelineRequest(), engine = FiniteEngine.BACKTRACK).result

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `zero powers should retain their original function domain`() {
        val input = SmtLib.parse("(assert (= (^ (log 0) 0) 1)) (check-sat)")
        val source = assertIs<TranscendentalProblem>((input).source.originalSource)

        val result = OpenTheoryPipeline.execute(source.pipelineRequest(), engine = FiniteEngine.BACKTRACK).result

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `function names shadowed by polynomial helpers should retain factor mathematics`() {
        val input = SmtLib.parse(
            "(define-fun exp ((t Real)) Real (* t t)) (declare-const x Real) (assert (= (exp x) 2)) (check-sat)",
        )

        assertIs<Problem>(input.source.originalSource)
        assertIs<OpenTheoryResult.Sat>(BacktrackSolver.source(input.model).solve())
    }

    @Test
    fun `declared pi should retain its original sort and factor admission`() {
        for ((sort, value) in listOf("Int" to "2", "Real" to "2", "Bool" to "true")) {
            val input = SmtLib.parse("(declare-const pi $sort) (assert (= pi $value)) (check-sat)")
            val source = (input)

            val result = BacktrackSolver.source(source.model).solve()

            val witness = assertIs<OpenTheoryResult.Sat>(result).assignment
            val actual = when (sort) {
                "Int" -> witness.intValue(source.intVarNames.getValue("pi"))
                "Real" -> witness.realValue(source.realVarNames.getValue("pi"))
                else -> witness.boolValue(source.boolVarNames.getValue("pi")).toString()
            }
            assertEquals(value, actual)
        }
    }

    @Test
    fun `function names should retain linear integer helper meanings`() {
        for (name in listOf("exp", "log", "sin", "cos", "pi")) {
            val input = SmtLib.parse(
                "(define-fun $name ((n Int)) Int (+ n 1)) (declare-const x Int) " +
                    "(assert (<= 0 x 3)) (assert (= ($name x) 3)) (check-sat)",
            )
            val source = (input)

            val result = BacktrackSolver.source(source.model).solve()

            val witness = assertIs<OpenTheoryResult.Sat>(result).assignment
            assertEquals("2", witness.intValue(source.intVarNames.getValue("x")))
        }
    }

    @Test
    fun `zero arity pi helpers should retain their integer meaning`() {
        val input = SmtLib.parse(
            "(define-fun pi () Int 2) (declare-const x Int) (assert (= x pi)) (check-sat)",
        )
        val source = (input)

        val result = BacktrackSolver.source(source.model).solve()

        val witness = assertIs<OpenTheoryResult.Sat>(result).assignment
        assertEquals("2", witness.intValue(source.intVarNames.getValue("x")))
    }

    @Test
    fun `local pi bindings should retain their Boolean meaning`() {
        val input = SmtLib.parse(
            "(declare-const b Bool) (assert (= b (let ((pi true)) pi))) (check-sat)",
        )
        val source = (input)

        val result = BacktrackSolver.source(source.model).solve()

        val witness = assertIs<OpenTheoryResult.Sat>(result).assignment
        assertTrue(witness.boolValue(source.boolVarNames.getValue("b")))
    }

    @Test
    fun `unused Boolean let bindings should retain actual log domains`() {
        val input = SmtLib.parse("(assert (let ((b (= (log 0) 1))) true)) (check-sat)")
        val source = assertIs<TranscendentalProblem>((input).source.originalSource)

        val result = OpenTheoryPipeline.execute(source.pipelineRequest(), engine = FiniteEngine.BACKTRACK).result

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `source objectives should be retained when exact function optimization is unavailable`() {
        val input = SmtLib.parse(
            "(declare-const x Real) (assert (> (exp x) 0)) (minimize (* x x)) (check-sat)",
        )
        val source = assertIs<TranscendentalProblem>((input).source.originalSource)

        val result = OpenTheoryPipeline.execute(source.pipelineRequest()).result

        assertTrue(source.hasObjective)
        assertEquals(TerminationReason.Unsupported, assertIs<OpenTheoryResult.Unknown>(result).reason)
    }

    @Test
    fun `function source assumptions should retain original Boolean correlation`() {
        val input = SmtLib.parse(
            "(declare-const b Bool) (declare-const x Real) (assert (= (exp x) 2)) (check-sat-assuming (b))",
        )
        val source = assertIs<TranscendentalProblem>((input).source.originalSource)

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

            val captured = (SmtLib.parse(text))

            assertIs<Problem>(captured.source.originalSource)
            assertEquals(sense, captured.sense)
            assertEquals(
                QuadraticCoefficient.of(bigIntOf(7), bigIntOf(3)),
                assertNotNull(captured.quadraticObjective).constant,
            )
            assertEquals(
                listOf(
                    QuadraticObjectiveTerm(
                        QuadraticCoefficient.of(bigIntOf(1), bigIntOf(10)),
                        QuadraticVariable.Real(SourceRealId(1)),
                    ),
                ),
                assertNotNull(captured.quadraticObjective).terms,
            )
        }
    }
}
