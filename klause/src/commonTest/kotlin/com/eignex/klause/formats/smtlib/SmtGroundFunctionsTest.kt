package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.TranscendentalProblem
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.pipelineRequest
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SmtGroundFunctionsTest {
    @Test
    fun `equal arguments require equal uninterpreted results`() {
        val cases = listOf(
            "(declare-const a Int) (declare-const b Int) (declare-fun f (Int) Int) " +
                "(assert (= a b)) (assert (distinct (f a) (f b)))",
            "(declare-const a Real) (declare-const b Real) (declare-fun f (Real) Real) " +
                "(assert (= a b)) (assert (distinct (f a) (f b)))",
            "(declare-const a Bool) (declare-const b Bool) (declare-fun f (Bool) Int) " +
                "(assert (= a b)) (assert (distinct (f a) (f b)))",
            "(declare-sort U 0) (declare-const a U) (declare-const b U) (declare-fun f (U) U) " +
                "(assert (= a b)) (assert (distinct (f a) (f b)))",
        )

        for (text in cases) {
            val parsed = SmtLib.parse(text)
            val result = BacktrackSolver.source(parsed.model).solve()

            assertIs<OpenTheoryResult.Unsat>(result, text)
        }
    }

    @Test
    fun `equal arguments require consistent predicate truth`() {
        val parsed = SmtLib.parse("""
            (declare-sort U 0)
            (declare-const a U)
            (declare-const b U)
            (declare-fun p (U) Bool)
            (assert (= a b))
            (assert (p a))
            (assert (not (p b)))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `congruence propagates through nested applications`() {
        val parsed = SmtLib.parse("""
            (declare-sort U 0)
            (declare-const a U)
            (declare-const b U)
            (declare-fun f (U) U)
            (assert (= a b))
            (assert (distinct (f (f a)) (f (f b))))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `interpreted integer arguments and results retain arbitrary precision`() {
        val parsed = SmtLib.parse("""
            (declare-const x Int)
            (declare-fun f (Int) Int)
            (assert (= x 1000000000000000000000000000000))
            (assert (= (f x) 2000000000000000000000000000000))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model).solve()

        val assignment = assertIs<OpenTheoryResult.Sat>(result).assignment
        val model = parsed.reconstructModel(assignment::boolValue, assignment::intValue, assignment::realValue)
        val function = assertIs<SmtValue.Function>(model.values.single { it.first.name == "f" }.second)
        assertEquals(SmtValue.Integer("2000000000000000000000000000000"), function.entries.single().second)
    }

    @Test
    fun `congruence uses exact arithmetic equality of arguments`() {
        val parsed = SmtLib.parse("""
            (declare-fun f (Real Int Bool) Int)
            (assert (distinct (f 0.5 (+ 1 1) (not false)) (f (/ 1 2) 2 true)))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `free sort witnesses reconstruct typed total functions`() {
        val parsed = SmtLib.parse("""
            (declare-sort U 0)
            (declare-const a U)
            (declare-const b U)
            (declare-fun f (U) U)
            (assert (distinct a b))
            (assert (= (f a) b))
            (assert (= (f b) a))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model).solve()

        val assignment = assertIs<OpenTheoryResult.Sat>(result).assignment
        val model = parsed.reconstructModel(assignment::boolValue, assignment::intValue, assignment::realValue)
        val values = model.values.associate { it.first.name to it.second }
        val function = assertIs<SmtValue.Function>(values.getValue("f"))
        assertEquals(values.getValue("b"), function.entries.single { it.first == listOf(values.getValue("a")) }.second)
        assertEquals(values.getValue("a"), function.entries.single { it.first == listOf(values.getValue("b")) }.second)
        assertIs<SmtValue.Uninterpreted>(function.default)
        assertTrue(model.toSmtLib().contains("(as @klause.u"))
    }

    @Test
    fun `free sort identities do not appear in public integer name maps`() {
        val parsed = SmtLib.parse("(declare-sort U 0) (declare-const a U) (declare-const n Int)")

        assertEquals(setOf("n"), parsed.intVarNames.keys)
        assertTrue(parsed.boolVarNames.isEmpty())
        assertTrue(parsed.realVarNames.isEmpty())
    }

    @Test
    fun `unused functions have source typed defaults`() {
        val parsed = SmtLib.parse("(declare-sort U 0) (declare-fun f (Int) U)")

        val result = BacktrackSolver.source(parsed.model).solve()

        val assignment = assertIs<OpenTheoryResult.Sat>(result).assignment
        val model = parsed.reconstructModel(assignment::boolValue, assignment::intValue, assignment::realValue)
        val function = assertIs<SmtValue.Function>(model.values.single().second)
        assertEquals(emptyList(), function.entries)
        assertEquals(SmtSort.Uninterpreted("U"), assertIs<SmtValue.Uninterpreted>(function.default).sort)
    }

    @Test
    fun `macro expansion and parallel lets preserve their lexical bindings`() {
        val parsed = SmtLib.parse("""
            (declare-sort U 0)
            (declare-const a U)
            (declare-const b U)
            (declare-fun f (U) U)
            (define-fun g ((x U)) U (f x))
            (assert (distinct a b))
            (assert (let ((a b) (b a)) (distinct (g a) (f b))))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model).solve()

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `function arguments can use zero arity declarations and qualified constants`() {
        val parsed = SmtLib.parse("""
            (declare-sort U 0)
            (declare-fun a () U)
            (declare-fun f (U) U)
            (assert (distinct (f a) (f (as a U))))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `function variables do not capture previously generated symbols`() {
        val original = captureSmtSource(SExprReader("(declare-const n Int)").readAll(), Cancellation.Never)
        val commands = SExprReader("""
            (declare-const n Int)
            (declare-const @klause.uf.0 Int)
            (declare-fun f (Int) Int)
            (assert (= @klause.uf.0 1))
            (assert (= n @klause.uf.0))
            (assert (= (f @klause.uf.0) 2))
        """.trimIndent()).readAll()
        val reduced = reduceSmtGroundFunctions(original.copy(commands = commands), Cancellation.Never)
        val parsed = lowerSmtReduction(reduced, Long.MIN_VALUE, Long.MAX_VALUE, ProblemSettings(),
            Cancellation.Never, smtLibSemantics.resolve())

        val result = BacktrackSolver.source(parsed.model).solve()

        val assignment = assertIs<OpenTheoryResult.Sat>(result).assignment
        val model = parsed.reconstructModel(assignment::boolValue, assignment::intValue, assignment::realValue)
        assertEquals(SmtValue.Integer("1"), model.values.single { it.first.name == "n" }.second)
    }

    @Test
    fun `quoted numeral symbols remain distinct from numeric arguments`() {
        val parsed = SmtLib.parse("""
            (declare-const |0| Int)
            (declare-fun f (Int) Int)
            (assert (= |0| 2))
            (assert (distinct (f |0|) (f 0)))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model).solve()

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `integer declaration overrides do not constrain function results or free identities`() {
        val parsed = SmtLib.parse("""
            (declare-sort U 0)
            (declare-const a U)
            (declare-const b U)
            (declare-const n Int)
            (declare-fun f (U) Int)
            (assert (= n 100))
            (assert (distinct a b))
            (assert (= (f a) 1000000))
        """.trimIndent(), unboundedIntLo = 100, unboundedIntHi = 101)

        val result = BacktrackSolver.source(parsed.model).solve()

        val assignment = assertIs<OpenTheoryResult.Sat>(result).assignment
        val model = parsed.reconstructModel(assignment)
        val function = assertIs<SmtValue.Function>(model.values.single { it.first.name == "f" }.second)
        assertEquals(SmtValue.Integer("1000000"), function.entries.single().second)
    }

    @Test
    fun `generated function declarations stay outside the original source model`() {
        val original = captureSmtSource(SExprReader("(declare-const flag Bool)").readAll(), Cancellation.Never)
        val commands = SExprReader("(declare-const flag Bool) (declare-fun private (Int) Int) " +
            "(assert (= (private 1) 2))").readAll()

        val reduced = exposeSmtGroundFunctions(original.copy(commands = commands), Cancellation.Never)

        assertEquals(listOf("flag"), reduced.bindings.map { it.declaration.name })
        assertTrue("private" in reduced.privateBindings)
    }

    @Test
    fun `free sort erasure does not admit ill typed terms`() {
        val cases = listOf("(assert (= a b))", "(assert (= (+ a 1) 2))", "(assert (= (f b) a))")

        for (assertion in cases) {
            assertFailsWith<UnsupportedSmtException> {
                SmtLib.parse("(declare-sort U 0) (declare-sort V 0) " +
                    "(declare-const a U) (declare-const b V) (declare-fun f (U) U) $assertion")
            }
        }
    }

    @Test
    fun `sort aliases substitute their parameters simultaneously`() {
        val parsed = SmtLib.parse("""
            (declare-sort U 0)
            (declare-sort V 0)
            (define-sort First (A B) A)
            (define-sort Other (U) (First U V))
            (declare-const a (Other Int))
            (declare-fun f ((First Int Real)) Int)
            (assert (= a 7))
            (assert (distinct (f a) (f 7)))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `sort aliases preserve interpreted scalar declarations`() {
        val parsed = SmtLib.parse("""
            (define-sort Count () Int)
            (declare-const n Count)
            (assert (= n 1000000000000000000000000000000))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model).solve()

        val assignment = assertIs<OpenTheoryResult.Sat>(result).assignment
        assertEquals("1000000000000000000000000000000", assignment.intValue(parsed.intVarNames.getValue("n")))
    }

    @Test
    fun `sort aliases cannot refer to themselves or later declarations`() {
        val cases = listOf("(define-sort A () A)", "(define-sort A () B) (declare-sort B 0)")

        for (text in cases) assertFailsWith<UnsupportedSmtException> { SmtLib.parse(text) }
    }

    @Test
    fun `result qualifications check source types without coercion`() {
        val parsed = SmtLib.parse("""
            (declare-sort U 0)
            (declare-const a U)
            (declare-fun f (U) U)
            (assert (distinct ((as f U) a) (f a)))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `qualified interpreted operators and constants retain their selected rank`() {
        val parsed = SmtLib.parse("""
            (assert (= ((as + Int) 1 2) 3))
            (assert (= ((as + Real) 1 2) 3.0))
            (assert (as true Bool))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model).solve()

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `qualifying a user function does not cast its result`() {
        assertFailsWith<UnsupportedSmtException> {
            SmtLib.parse("(declare-fun f (Int) Int) (assert (= ((as f Real) 1) 1.0))")
        }
    }

    @Test
    fun `ground exposure retains domains of unused helper arguments and let values`() {
        val cases = listOf(
            "(define-fun f ((x Real)) Real 1) (assert (= (f (log 0)) 1))",
            "(assert (let ((x (log 0))) true))",
            "(assert (let ((x (= (log 0) 1))) true))",
        )

        for (body in cases) {
            val parsed = SmtLib.parse("(define-sort R () Real) $body")
            val source = assertIs<TranscendentalProblem>(parsed.source.originalSource)

            val result = OpenTheoryPipeline.execute(source.pipelineRequest(), engine = FiniteEngine.BACKTRACK)

            assertIs<OpenTheoryResult.Unsat>(result.result, body)
        }
    }

    @Test
    fun `ground exposure keeps unused binding domains inside their conditional branch`() {
        val parsed = SmtLib.parse("""
            (define-sort R () Real)
            (declare-const b Bool)
            (assert b)
            (assert (ite b true (let ((x (log 0))) true)))
        """.trimIndent())
        val source = assertIs<TranscendentalProblem>(parsed.source.originalSource)

        val result = OpenTheoryPipeline.execute(source.pipelineRequest(), engine = FiniteEngine.BACKTRACK)

        assertIs<OpenTheoryResult.Sat>(result.result)
    }

    @Test
    fun `function expansion observes the source cancellation allowance`() {
        var work = 0L
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = work >= 100
            override fun workMeter(): WorkMeter = WorkMeter { work += it }
        }
        val applications = (0..40).joinToString(" ") { "(assert (= (f $it) $it))" }

        assertFailsWith<CancellationException> {
            SmtLib.parse("(declare-fun f (Int) Int) $applications", cancellation = cancellation)
        }
        assertTrue(work >= 100)
    }
}
