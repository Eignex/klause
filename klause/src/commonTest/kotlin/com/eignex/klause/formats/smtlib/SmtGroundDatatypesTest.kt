package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.lowering.PolynomialSourceInterrupted
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FiniteSolveCallbacks
import com.eignex.klause.solver.pipeline.FiniteSolveOutcome
import com.eignex.klause.solver.pipeline.FiniteSolveRequest
import com.eignex.klause.solver.pipeline.FiniteSolveVerdict
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.solver.pipeline.OpenTheoryExecution
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.SourceExecutionCallbacks
import com.eignex.klause.solver.pipeline.SourceExecutionRequest
import com.eignex.klause.solver.pipeline.SourceExecutionResult
import com.eignex.klause.solver.pipeline.SourceFiniteControls
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.ExecutionControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SmtGroundDatatypesTest {
    @Test
    fun `different enumeration constructors are distinct`() {
        val parsed = SmtLib.parse("(declare-datatype Color ((red) (green))) (assert (= red green))")

        val result = solve(parsed)

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `enumeration values exhaust a finite sort`() {
        val parsed = SmtLib.parse("""
            (declare-datatype Color ((red) (green)))
            (declare-const x Color)
            (assert (distinct x red green))
        """.trimIndent())

        val result = solve(parsed)

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `constructor equality preserves fields`() {
        val parsed = SmtLib.parse("""
            (declare-datatype Pair ((pair (first Int) (second Bool))))
            (assert (= (pair 1 true) (pair 2 true)))
        """.trimIndent())

        val result = solve(parsed)

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `recursive values cannot contain themselves`() {
        val parsed = SmtLib.parse("""
            (declare-datatype List ((nil) (cons (head Int) (tail List))))
            (declare-const x List)
            (assert (= x (cons 7 x)))
        """.trimIndent())

        val result = solve(parsed)

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `mutually recursive cycles are refuted`() {
        val parsed = SmtLib.parse("""
            (declare-datatypes ((A 0) (B 0))
                (((a0) (a (toB B))) ((b0) (b (toA A)))))
            (declare-const x A)
            (declare-const y B)
            (assert (= x (a y)))
            (assert (= y (b x)))
        """.trimIndent())

        val result = solve(parsed)

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `qualified parametric constructors retain their instantiation`() {
        val parsed = SmtLib.parse("""
            (declare-datatypes ((Option 1)) ((par (T) ((none) (some (value T))))))
            (declare-const x (Option Int))
            (assert (= x (some 7)))
            (assert (distinct x (as none (Option Int))))
        """.trimIndent())

        val assignment = assertIs<OpenTheoryResult.Sat>(solve(parsed)).assignment
        val model = parsed.reconstructModel(assignment)

        assertEquals("((as some (Option Int)) 7)", model.values.single().second.toSmtLib())
        assertTrue(parsed.intVarNames.isEmpty())
    }

    @Test
    fun `testers and selectors constrain the original constructor`() {
        val parsed = SmtLib.parse("""
            (declare-datatype List ((nil) (cons (head Int) (tail List))))
            (declare-const x List)
            (assert ((_ is cons) x))
            (assert (= (head x) 42))
            (assert (= (tail x) nil))
        """.trimIndent())

        val assignment = assertIs<OpenTheoryResult.Sat>(solve(parsed)).assignment
        val model = parsed.reconstructModel(assignment)

        assertEquals("(cons 42 nil)", model.values.single().second.toSmtLib())
    }

    @Test
    fun `wrong constructor selectors are total and consistent`() {
        val parsed = SmtLib.parse("""
            (declare-datatype Option ((none) (some (value Int))))
            (declare-const x Option)
            (assert (= x none))
            (assert (= (value none) 19))
            (assert (distinct (value x) 19))
        """.trimIndent())

        val result = solve(parsed)

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `match binds constructor fields lexically`() {
        val parsed = SmtLib.parse("""
            (declare-datatype Option ((none) (some (value Int))))
            (declare-const result Int)
            (assert (= result (let ((x 10)) (match (some 3) ((none x) ((some x) (+ x 1)))))))
        """.trimIndent())

        val assignment = assertIs<OpenTheoryResult.Sat>(solve(parsed)).assignment
        val model = parsed.reconstructModel(assignment)

        assertEquals("4", model.values.single().second.toSmtLib())
    }

    @Test
    fun `unconstrained recursive identities reconstruct distinct finite values`() {
        val parsed = SmtLib.parse("""
            (declare-datatype Nat ((zero) (succ (pred Nat))))
            (declare-const x Nat)
            (declare-const y Nat)
            (declare-const z Nat)
            (assert (distinct x y z))
        """.trimIndent())

        val assignment = assertIs<OpenTheoryResult.Sat>(solve(parsed)).assignment
        val model = parsed.reconstructModel(assignment)

        assertEquals(3, model.values.map { it.second.toSmtLib() }.toSet().size)
        assertTrue(model.values.all { it.second is SmtValue.Application })
    }

    @Test
    fun `finite record cardinality includes boolean array fields`() {
        val parsed = SmtLib.parse("""
            (declare-datatype Record ((record (bits (Array Bool Bool)))))
            (declare-const a Record)
            (declare-const b Record)
            (declare-const c Record)
            (declare-const d Record)
            (declare-const e Record)
            (assert (distinct a b c d e))
        """.trimIndent())

        val result = solve(parsed)

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `one assertion counts every exposed datatype application for cardinality`() {
        val parsed = SmtLib.parse("""
            (declare-datatype Record ((record (first Bool) (second Bool))))
            (declare-fun f (Int) Record)
            (assert (distinct (f 0) (f 1) (f 2) (f 3) (f 4)))
        """.trimIndent())

        val result = solve(parsed)

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `array reads return actual datatype constructor values`() {
        val parsed = SmtLib.parse("""
            (declare-datatype Color ((red) (green)))
            (declare-const a (Array Bool Color))
            (assert (= (select a false) red))
            (assert (= (select a true) green))
        """.trimIndent())

        val assignment = assertIs<OpenTheoryResult.Sat>(solve(parsed)).assignment
        val array = assertIs<SmtValue.Array>(parsed.reconstructModel(assignment).values.single().second)

        assertTrue(array.default is SmtValue.Application)
        assertTrue(array.entries.all { it.second is SmtValue.Application })
    }

    @Test
    fun `unused datatype functions retain total constructor defaults`() {
        val parsed = SmtLib.parse("""
            (declare-datatype Nat ((zero) (succ (pred Nat))))
            (declare-fun f (Nat) Nat)
        """.trimIndent())

        val assignment = assertIs<OpenTheoryResult.Sat>(solve(parsed)).assignment
        val function = assertIs<SmtValue.Function>(parsed.reconstructModel(assignment).values.single().second)

        assertIs<SmtValue.Application>(function.default)
        assertEquals(listOf(SmtSort.Uninterpreted("Nat")), function.parameters)
    }

    @Test
    fun `nested parameter instantiations reconstruct across array fields`() {
        val parsed = SmtLib.parse("""
            (declare-datatypes ((Box 1)) ((par (T) ((box (field T))))))
            (declare-const a (Array Bool (Box Bool)))
            (declare-const x (Box (Array Bool (Box Bool))))
            (assert (= (select a false) (box true)))
            (assert (= x (box a)))
        """.trimIndent())

        val assignment = assertIs<OpenTheoryResult.Sat>(solve(parsed)).assignment
        val model = parsed.reconstructModel(assignment)

        val outer = assertIs<SmtValue.Application>(model.values.single { it.first.name == "x" }.second)
        assertIs<SmtValue.Array>(outer.arguments.single())
    }

    @Test
    fun `non well founded datatype declarations are rejected`() {
        assertFailsWith<UnsupportedSmtException> {
            SmtLib.parse("(declare-datatype Empty ((loop (next Empty))))")
        }
    }

    @Test
    fun `nested occurrences of a mutually declared datatype are rejected`() {
        assertFailsWith<UnsupportedSmtException> {
            SmtLib.parse("(declare-datatype Invalid ((empty) (node (field (Array Bool Invalid)))))")
        }
    }

    @Test
    fun `datatype function names share one global namespace`() {
        val cases = listOf(
            "(declare-datatypes ((A 0) (B 0)) (((same)) ((same))))",
            "(declare-datatype A ((a (field Int)) (b (field Bool))))",
            "(declare-const ctor Int) (declare-datatype A ((ctor)))",
        )

        for (text in cases) assertFailsWith<UnsupportedSmtException> { SmtLib.parse(text) }
    }

    @Test
    fun `qualified indexed testers retain their datatype rank`() {
        val parsed = SmtLib.parse("""
            (declare-datatype Option ((none) (some (value Int))))
            (assert ((as (_ is some) Bool) (some 7)))
        """.trimIndent())

        val result = solve(parsed)

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `numeric literals do not resolve quoted numeric symbols`() {
        val parsed = SmtLib.parse("""
            (declare-datatype Box ((box (field Int))))
            (declare-const |1| Int)
            (assert (= |1| 9))
            (assert (= (field (box 1)) 1))
        """.trimIndent())

        val result = solve(parsed)

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `source checking withholds a rejected datatype assignment`() {
        val parsed = SmtLib.parse("(declare-datatype Nat ((zero) (succ (pred Nat)))) " +
            "(declare-const x Nat) (assert (= x zero))")
        var checks = 0
        val rejected = parsed.copy(sourceChecks = parsed.sourceChecks + SmtSourceCheck { values ->
            assertIs<SmtValue.Application>(values.value("x"))
            checks++
            false
        }).withSourceWitnessValidation()

        val result = solve(rejected)

        assertIs<OpenTheoryResult.Unknown>(result)
        assertTrue(checks > 0)
    }

    @Test
    fun `datatype exposure handles deep arithmetic fields iteratively`() {
        val expression = "(+ 1 ".repeat(3000) + "0" + ")".repeat(3000)
        val script = "(declare-datatype Box ((box (field Int)))) (assert (= (field (box $expression)) 3000))"
        val input = captureSmtSource(SExprReader(script).readAll(), ExecutionControl.Never)

        val result = exposeSmtGroundDatatypes(input, ExecutionControl.Never)

        assertTrue(result.datatypes!!.shapes.isNotEmpty())
    }

    @Test
    fun `deep constructor models render without recursive traversal`() {
        var value: SmtValue = SmtValue.Application("zero", emptyList())
        repeat(5000) { value = SmtValue.Application("succ", listOf(value)) }

        val rendered = value.toSmtLib()

        assertEquals(5000, rendered.count { it == '(' })
        assertTrue(rendered.startsWith("(succ (succ "))
    }

    @Test
    fun `datatype exposure observes cancellation`() {
        val input = captureSmtSource(SExprReader("(declare-datatype Nat ((zero) (succ (pred Nat))))").readAll(),
            ExecutionControl.Never)

        assertFailsWith<PolynomialSourceInterrupted> {
            exposeSmtGroundDatatypes(input, ExecutionControl { true })
        }
    }

    private fun solve(parsed: SmtLibProblem): OpenTheoryResult {
        val invocation = SourceSetup.prepare(SourceSetupRequest(parsed.source, ExecutionOperation.SATISFY,
            FiniteEngine.BACKTRACK))
        var sample: Sample? = null
        val result = SourceSetup.execute(SourceExecutionRequest(invocation,
            finiteRequest = { FiniteSolveRequest(it, SourceFiniteControls.Backtrack(BacktrackParams())) },
            presolveConfig = PresolveConfig.NONE), SourceExecutionCallbacks(
            finite = FiniteSolveCallbacks({ sample = it }, { sample = it.sample })))
        return when (result) {
            is SourceExecutionResult.Linear -> assertIs<OpenTheoryExecution.Satisfy>(result.result).result
            is SourceExecutionResult.Polynomial -> result.result.result
            is SourceExecutionResult.Transcendental -> result.result.result
            is SourceExecutionResult.Refuted -> OpenTheoryResult.Unsat(SolveStats.EMPTY)
            is SourceExecutionResult.Declined -> OpenTheoryResult.Unknown(result.reason, SolveStats.EMPTY)
            is SourceExecutionResult.Finite -> {
                val outcome = assertIs<FiniteSolveOutcome.Completed>(result.result.outcome)
                when (outcome.verdict) {
                    FiniteSolveVerdict.SAT -> OpenTheoryResult.Sat(
                        OpenTheoryAssignment.Sampled(requireNotNull(outcome.bestSample ?: sample)), outcome.stats)
                    FiniteSolveVerdict.UNSAT -> OpenTheoryResult.Unsat(outcome.stats)
                    else -> OpenTheoryResult.Unknown(TerminationReason.RepresentationDeclined, outcome.stats)
                }
            }
            else -> error("unexpected datatype source result")
        }
    }
}
