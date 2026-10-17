package com.eignex.klause.cli

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.SourceExecutionRequest
import com.eignex.klause.solver.pipeline.SourceExecutionResult
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.solver.pipeline.optimizeLinear
import com.eignex.klause.solver.result.LinearOptimizationResult
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.PrintStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CliModePublicationTest {

    @Test
    fun `ordinary source publication failure should leave a later invocation independent`() {
        val source = File.createTempFile("linear-publication-failure", ".smt2").apply {
            writeText("(declare-const x Real) (assert (= (* 3 x) 1)) (minimize (+ 7 x)) (check-sat)")
        }
        val common = CommonOptions().apply {
            engine = "fixed"
            parallel = 1
            presolve = "off"
        }
        val session = SmtLibMode.newSession()
        val expected = IOException("closed linear consumer")
        var terminal = false
        val failed = object : OutputProtocol by session.output(common) {
            override fun onArithmeticIncumbent(rendered: String, objective: SourceValue): Boolean {
                assertFalse(terminal)
                throw expected
            }
            override fun onVerdictContext(context: VerdictContext) {
                terminal = true
            }
        }
        val values = mutableListOf<BigFraction>()
        var completed: Verdict? = null
        val healthy = object : OutputProtocol by session.output(common) {
            override fun onArithmeticIncumbent(rendered: String, objective: SourceValue): Boolean {
                values += assertIs<SourceValue.Rational>(objective).value
                return true
            }
            override fun onComplete(verdict: Verdict) {
                completed = verdict
            }
        }

        try {
            val solvable = session.load(source.absolutePath, common)
            assertIs<SolvablePipeline.OpenTheory>(solvable.pipeline)
            assertSame(expected, assertFailsWith<IOException> { SolveCore.solve(solvable, common, failed) })
            val later = CommonOptions().apply {
                engine = "fixed"
                parallel = 1
                presolve = "off"
            }
            SolveCore.solve(solvable, later, healthy)
        } finally {
            source.delete()
        }

        assertFalse(terminal)
        assertEquals(listOf(BigFraction.ofLong(22) * BigFraction.ofLong(3).reciprocal()), values)
        assertEquals(Verdict.OPTIMAL, completed)
    }

    @Test
    fun `an ordinary source rendering veto should withhold its witness and terminal metadata`() {
        val source = File.createTempFile("linear-source-veto", ".smt2").apply {
            writeText("(declare-const x Real) (assert (= x 1)) (minimize 7) (check-sat)")
        }
        val common = CommonOptions().apply {
            engine = "fixed"
            parallel = 1
            presolve = "off"
        }
        val session = SmtLibMode.newSession()
        var checked = 0
        var completed: Verdict? = null
        val output = object : OutputProtocol by session.output(common) {
            override fun arithmeticSourceValue(objective: SourceValue): SourceValue? {
                checked++
                return null
            }
            override fun onArithmeticIncumbent(rendered: String, objective: SourceValue): Boolean =
                error("a rejected source rendering cannot reach publication")

            override fun onLinearResult(result: LinearOptimizationResult) =
                error("a rejected source rendering cannot justify terminal metadata")

            override fun onComplete(verdict: Verdict) {
                completed = verdict
            }
        }

        try {
            val solvable = session.load(source.absolutePath, common)
            assertIs<SolvablePipeline.OpenTheory>(solvable.pipeline)
            SolveCore.solve(solvable, common, output)
        } finally {
            source.delete()
        }

        assertTrue(checked > 0)
        assertEquals(Verdict.UNKNOWN, completed)
    }

    @Test
    fun `cancellation from the second arithmetic publication should survive cleanup without terminal claims`() {
        val source = File.createTempFile("quadratic-publication-cancel", ".smt2").apply {
            writeText(
                "(declare-const b Bool) (declare-const x Real) (assert (= x (ite b 0 1))) " +
                    "(minimize (- 7 (* x x))) (check-sat)",
            )
        }
        val common = CommonOptions().apply {
            engine = "cp"
            parallel = 1
            randomSeed = 17
        }
        val session = SmtLibMode.newSession()
        val expected = CancellationException("consumer cancelled")
        val values = mutableListOf<BigFraction>()
        var terminal = false
        val output = object : OutputProtocol by session.output(common) {
            override fun onArithmeticIncumbent(rendered: String, objective: SourceValue): Boolean {
                assertFalse(terminal)
                values += assertNotNull(assertIs<SourceValue.Algebraic>(objective).value.rational)
                if (values.size == 2) throw expected
                return true
            }
            override fun onVerdictContext(context: VerdictContext) {
                terminal = true
            }
            override fun onComplete(verdict: Verdict) = error("cancelled publication cannot emit a terminal claim")
        }

        try {
            assertSame(
                expected,
                assertFailsWith<CancellationException> {
                    SolveCore.solve(session.load(source.absolutePath, common), common, output)
                },
            )
        } finally {
            source.delete()
        }

        assertEquals(listOf(BigFraction.ofLong(7), BigFraction.ofLong(6)), values)
        assertFalse(terminal)
    }

    @Test
    fun `a source rendering veto should withhold the arithmetic witness and terminal proof`() {
        val source = File.createTempFile("quadratic-source-veto", ".smt2").apply {
            writeText("(declare-const x Real) (assert (= x 1)) (minimize (* x x)) (check-sat)")
        }
        val common = CommonOptions().apply {
            engine = "fixed"
            parallel = 1
        }
        val session = SmtLibMode.newSession()
        var checked = 0
        var completed: Verdict? = null
        val output = object : OutputProtocol by session.output(common) {
            override fun arithmeticSourceValue(objective: SourceValue): SourceValue? {
                checked++
                return null
            }
            override fun onArithmeticIncumbent(rendered: String, objective: SourceValue): Boolean =
                error("a rejected source rendering cannot reach publication")

            override fun onQuadraticResult(rendered: String?, result: QuadraticOptimizationResult) =
                error("a rejected source rendering cannot justify terminal proof output")

            override fun onComplete(verdict: Verdict) {
                completed = verdict
            }
        }

        try {
            SolveCore.solve(session.load(source.absolutePath, common), common, output)
        } finally {
            source.delete()
        }

        assertTrue(checked > 0)
        assertEquals(Verdict.UNKNOWN, completed)
    }

    @Test
    fun `a consumer failure should abort source publication and leave a later invocation usable`() {
        val source = File.createTempFile("quadratic-output-failure", ".smt2").apply {
            writeText("(declare-const x Real) (assert (= x 1)) (minimize (* x x)) (check-sat)")
        }
        val common = CommonOptions().apply {
            engine = "fixed"
            parallel = 1
        }
        val session = SmtLibMode.newSession()
        val solvable = session.load(source.absolutePath, common)
        val expected = IOException("closed consumer")
        var terminal = false
        val failed = object : OutputProtocol by session.output(common) {
            override fun onArithmeticIncumbent(rendered: String, objective: SourceValue): Boolean {
                assertEquals(false, terminal)
                throw expected
            }
            override fun onVerdictContext(context: VerdictContext) {
                terminal = true
            }
        }
        var emitted = 0
        var completed: Verdict? = null
        val healthy = object : OutputProtocol by session.output(common) {
            override fun onArithmeticIncumbent(rendered: String, objective: SourceValue): Boolean {
                emitted++
                return true
            }
            override fun onQuadraticResult(rendered: String?, result: QuadraticOptimizationResult) {
                assertEquals(null, rendered)
            }
            override fun onComplete(verdict: Verdict) {
                completed = verdict
            }
        }

        try {
            assertSame(expected, assertFailsWith<IOException> { SolveCore.solve(solvable, common, failed) })
            SolveCore.solve(
                solvable,
                CommonOptions().apply {
                    engine = "fixed"
                    parallel = 1
                },
                healthy,
            )
        } finally {
            source.delete()
        }

        assertEquals(false, terminal)
        assertEquals(1, emitted)
        assertEquals(Verdict.OPTIMAL, completed)
    }

    @Test
    fun `ordinary linear CLI should keep a feasible witness distinct from its strict infimum`() {
        val source = File.createTempFile("linear-cli-infimum", ".smt2").apply {
            writeText("(declare-const x Real) (assert (> x 0)) (minimize (+ 5 x)) (check-sat)")
            deleteOnExit()
        }

        val text = capture {
            assertEquals(0, runCli(arrayOf("-e", "cp", "--presolve", "off", source.absolutePath)))
        }

        assertTrue("sat" in text.lines(), text)
        assertTrue("; optimizationStatus=unattained-infimum" in text.lines(), text)
        assertTrue("; objectiveBound=5" in text.lines(), text)
        assertTrue("; objectiveBoundDirection=lower" in text.lines(), text)
        assertTrue("; objectiveBoundStrict=true" in text.lines(), text)
        assertEquals(1, text.lines().count { it.startsWith("; objective=") }, text)
        assertFalse("; objective=5.0" in text.lines(), text)
        assertFalse("; optimizationStatus=optimal" in text.lines(), text)
    }

    @Test
    fun `ordinary mixed linear CLI should preserve an integer recession claim`() {
        val source = File.createTempFile("linear-cli-integer-ray", ".smt2").apply {
            writeText(
                "(declare-const i Int) (declare-const x Real) " +
                    "(assert (= x (to_real i))) (minimize (+ 5 i)) (check-sat)",
            )
            deleteOnExit()
        }

        val text = capture {
            assertEquals(0, runCli(arrayOf("-e", "cp", "--presolve", "off", source.absolutePath)))
        }

        assertTrue("sat" in text.lines(), text)
        assertTrue("; optimizationStatus=unbounded" in text.lines(), text)
        assertEquals(1, text.lines().count { it.startsWith("; objective=") }, text)
        assertTrue("(define-fun i () Int" in text || "(define-fun |i| () Int" in text, text)
        assertTrue("(define-fun x () Real" in text || "(define-fun |x| () Real" in text, text)
        assertFalse("; optimizationStatus=optimal" in text.lines(), text)
    }

    @Test
    fun `public linear callbacks should publish source improvements before cancellation`() {
        val a = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val b = SourceCoordinate(SourceCoordinate.Kind.Boolean, 1)
        val x = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val scope = SourceScope(
            SourceRevision("cli-linear-improvements", "exact-affine"),
            objective = SourceObjective("7 minus Boolean weights", ObjectiveSense.MINIMIZE),
        )
        val context = SourceProofContext(
            scope,
            setOf(a, b, x),
            mapOf(
                "equal-upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(3)), BigFraction.ONE)),
                "equal-lower" to SourcePremise(
                    SourceInequality(
                        mapOf(x to BigFraction.ofLong(-3)),
                        BigFraction.MINUS_ONE,
                    ),
                ),
            ),
            mapOf(a to BigFraction.ofLong(-2), b to BigFraction.MINUS_ONE),
            BigFraction.ofLong(7),
        )
        val protocol = SmtLibOutput()
        val values = mutableListOf<BigFraction>()
        var returned = false
        val output = object : OutputProtocol by protocol {
            override fun onArithmeticIncumbent(rendered: String, objective: SourceValue): Boolean {
                assertFalse(returned)
                values += assertIs<SourceValue.Rational>(objective).value
                return protocol.onArithmeticIncumbent(rendered, objective)
            }
        }
        val publication = ArithmeticPublication(output)
        val render: (Sample) -> String = {
            renderOpenTheoryModel(
                emptyMap(),
                mapOf("a" to 0, "b" to 1),
                mapOf("x" to 0),
                OpenTheoryAssignment.Sampled(it),
            )
        }
        lateinit var result: LinearOptimizationResult

        val text = capture {
            output.begin(optimize = true, maximize = false)
            result = OpenTheoryPipeline.optimizeLinear(
                context,
                TheoryParams(
                    cancellation = Cancellation { values.size == 2 },
                ),
            ) { sample, value -> publication.publish(render(sample), SourceValue.Rational(value)) }
            returned = true
            reportLinearOptimization(result, render, output, publication, false, 0L, true)
        }

        assertEquals(listOf(BigFraction.ofLong(7), BigFraction.ofLong(6)), values)
        assertEquals(TerminationReason.Cancelled, result.reason)
        assertEquals(2, text.lines().count { it.startsWith("; objective=") })
        assertTrue("; optimizationStatus=best-found" in text.lines(), text)
    }

    @Test
    fun `linear nonattainment metadata should not republish its feasible witness`() {
        val x = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val scope = SourceScope(
            SourceRevision("cli-linear-limit", "exact-affine"),
            objective = SourceObjective("x", ObjectiveSense.MINIMIZE),
        )
        val context = SourceProofContext(
            scope,
            setOf(x),
            mapOf(
                "positive" to SourcePremise(
                    SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO, strict = true),
                ),
            ),
            mapOf(x to BigFraction.ONE),
        )
        val output = SmtLibOutput()
        val publication = ArithmeticPublication(output)
        val render: (Sample) -> String = {
            renderOpenTheoryModel(emptyMap(), emptyMap(), mapOf("x" to 0), OpenTheoryAssignment.Sampled(it))
        }
        lateinit var result: LinearOptimizationResult

        val progress = capture {
            output.begin(optimize = true, maximize = false)
            result = OpenTheoryPipeline.optimizeLinear(context) { sample, value ->
                publication.publish(render(sample), SourceValue.Rational(value))
            }
        }
        val terminal = capture { reportLinearOptimization(result, render, output, publication, false, 0L, true) }

        assertTrue("define-fun x" in progress, progress)
        assertFalse("; objective=0\n" in progress, progress)
        assertFalse("define-fun" in terminal, terminal)
        assertFalse("; objective=" in terminal, terminal)
        assertTrue("; objectiveBound=0" in terminal.lines(), terminal)
        assertTrue("; objectiveBoundStrict=true" in terminal.lines(), terminal)
        assertTrue("; optimizationStatus=unattained-infimum" in terminal.lines(), terminal)
    }

    @Test
    fun `cancelled linear admission should print unknown without an assignment`() {
        val x = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val scope = SourceScope(
            SourceRevision("cli-linear-cancelled", "exact-affine"),
            objective = SourceObjective("x", ObjectiveSense.MINIMIZE),
        )
        val context = SourceProofContext(scope, setOf(x), emptyMap(), mapOf(x to BigFraction.ONE))
        val result = OpenTheoryPipeline.optimizeLinear(context, TheoryParams(cancellation = Cancellation { true }))
        val output = SmtLibOutput()
        val publication = ArithmeticPublication(output)

        val text = capture {
            output.begin(optimize = true, maximize = false)
            reportLinearOptimization(result, { error("no source witness") }, output, publication, false, 0L, true)
        }

        assertEquals("unknown\n; optimizationStatus=unknown\n; cancelled\n", text)
        assertEquals(0L, publication.solutions)
    }

    @Test
    fun `linear unbounded completion should retain the emitted witness without a duplicate objective`() {
        val x = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val scope = SourceScope(
            SourceRevision("cli-linear-ray", "exact-affine"),
            objective = SourceObjective("x", ObjectiveSense.MINIMIZE),
        )
        val context = SourceProofContext(scope, setOf(x), emptyMap(), mapOf(x to BigFraction.ONE))
        val output = SmtLibOutput()
        val publication = ArithmeticPublication(output)
        val render: (Sample) -> String = {
            renderOpenTheoryModel(emptyMap(), emptyMap(), mapOf("x" to 0), OpenTheoryAssignment.Sampled(it))
        }
        lateinit var result: LinearOptimizationResult

        val progress = capture {
            output.begin(optimize = true, maximize = false)
            result = OpenTheoryPipeline.optimizeLinear(context) { sample, value ->
                publication.publish(render(sample), SourceValue.Rational(value))
            }
        }
        val terminal = capture { reportLinearOptimization(result, render, output, publication, false, 0L, true) }

        assertTrue("define-fun x" in progress, progress)
        assertFalse("optimizationStatus" in progress, progress)
        assertEquals("sat\n; optimizationStatus=unbounded\n", terminal)
        assertEquals(1L, publication.solutions)
    }

    @Test
    fun `finite SMT improvements should each stream a complete model before completion`() {
        val output = SmtLibOutput()
        val first = "(\n  (define-fun i () Int 0)\n)"
        val second = "(\n  (define-fun i () Int 1)\n)"

        val progress = capture {
            output.begin(optimize = true, maximize = true)
            output.onSolution(first, 7L)
            output.onSolution(second, 9L)
        }
        val terminal = capture { output.onComplete(Verdict.OPTIMAL) }

        assertEquals("; objective=7\nsat\n$first\n; objective=9\nsat\n$second\n", progress)
        assertEquals("sat\n; optimizationStatus=optimal\n", terminal)
    }

    @Test
    fun `arithmetic SMT witnesses should stream before terminal proof status`() {
        val output = SmtLibOutput()
        val publication = ArithmeticPublication(output)
        val model = "(\n  (define-fun x () Real (/ 1.0 3.0))\n)"

        val progress = capture {
            output.begin(optimize = true, maximize = false)
            publication.publish(model, SourceValue.Rational(BigFraction.ONE * BigFraction.ofLong(3).reciprocal()))
        }
        val terminal = capture {
            publication.publish(model, SourceValue.Rational(BigFraction.ONE * BigFraction.ofLong(3).reciprocal()))
            output.onComplete(Verdict.OPTIMAL)
        }

        assertEquals("; objective=1/3\nsat\n$model\n", progress)
        assertEquals("sat\n; optimizationStatus=optimal\n", terminal)
    }

    @Test
    fun `arithmetic algebraic publication should separate a source optimum from its witness`() {
        val source = File.createTempFile("stream-root", ".smt2").apply {
            writeText("(declare-const x Real) (assert (= (* x x) 2)) (assert (> x 0)) (minimize x)")
            deleteOnExit()
        }
        val solvable = SmtLibMode.newSession().load(source.absolutePath, CommonOptions().apply { engine = "cp" })
        val pipeline = assertIs<SolvablePipeline.PolynomialTheory>(solvable.pipeline)
        val execution = assertIs<SourceExecutionResult.Quadratic>(
            SourceSetup.execute(SourceExecutionRequest(assertNotNull(solvable.sourceInvocation))),
        )
        val result = execution.result.result
        val point = assertIs<QuadraticOptimizationResult.Optimal>(result).incumbent
        val model = pipeline.render(polynomialAssignment(point.sample))
        val output = SmtLibOutput()
        val publication = ArithmeticPublication(output)

        val progress = capture {
            output.begin(optimize = true, maximize = false)
            publication.publish(model, SourceValue.Algebraic(point.value))
        }
        val terminal = capture {
            publication.quadratic(model, result)
            output.onComplete(Verdict.OPTIMAL)
        }

        assertTrue("; objective=(root-obj (+ (- 2) (* 1 (^ x 2))) 2)" in progress.lines(), progress)
        assertTrue("sat" in progress.lines(), progress)
        assertTrue(progress.indexOf("sat\n") < progress.indexOf(model), progress)
        assertTrue(model in progress, progress)
        assertFalse("optimizationStatus" in progress, progress)
        assertFalse("; objective=" in terminal, terminal)
        assertFalse("define-fun" in terminal, terminal)
        assertTrue("; objectiveBound=(root-obj (+ (- 2) (* 1 (^ x 2))) 2)" in terminal.lines(), terminal)
        assertTrue("; optimizationStatus=optimal" in terminal.lines(), terminal)
    }

    @Test
    fun `arithmetic FlatZinc publication should keep its solution and terminal separators`() {
        val output = MiniZincOutput()
        val publication = ArithmeticPublication(output)

        val progress = capture {
            publication.publish("x = 1;\n----------\n", SourceValue.Rational(BigFraction.ONE))
        }
        val terminal = capture {
            publication.publish("x = 1;\n----------\n", SourceValue.Rational(BigFraction.ONE))
            output.onComplete(Verdict.OPTIMAL)
        }

        assertEquals("% objective=1\nx = 1;\n----------\n", progress)
        assertEquals("==========\n", terminal)
    }

    @Test
    fun `exact FlatZinc should optimize a continuous objective in either direction`() {
        for ((direction, expected) in listOf("minimize" to -1.5, "maximize" to 1.5)) {
            val fzn = File.createTempFile("floatobj", ".fzn").apply {
                writeText(
                    """
                    var float: x :: output_var;
                    constraint float_le(-1.5, x);
                    constraint float_le(x, 1.5);
                    solve $direction x;
                    """.trimIndent(),
                )
                deleteOnExit()
            }

            val output = capture {
                assertEquals(0, runCli(arrayOf("--exact", "--output-objective", "-e", "cp", fzn.absolutePath)))
            }

            assertTrue("_objective = $expected;" in output, output)
            assertTrue("==========" in output, output)
        }
    }

    private fun capture(block: () -> Unit): String {
        val buf = ByteArrayOutputStream()
        val old = System.out
        System.setOut(PrintStream(buf))
        try {
            block()
        } finally {
            System.setOut(old)
        }
        return buf.toString()
    }
}
