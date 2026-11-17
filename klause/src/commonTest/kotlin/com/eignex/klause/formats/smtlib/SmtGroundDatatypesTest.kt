package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.lowering.PolynomialSourceInterrupted
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
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
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.ExecutionControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SmtGroundDatatypesTest {
    @Test
    fun `quoted equality retains boolean sort inside constructors`() {
        val parsed = SmtLib.parse("""
            (declare-datatype Box ((box (field Bool))))
            (assert (field (box (|=| 1 1))))
        """.trimIndent())

        val result = solve(parsed)

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `quoted real conversion retains its result sort inside constructors`() {
        val parsed = SmtLib.parse("""
            (declare-datatype Box ((box (field Real))))
            (assert (= (field (box (|to_real| 1))) 1.0))
        """.trimIndent())

        val result = solve(parsed)

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `datatype constructors named log remain productive with global sort parameters`() {
        val parsed = SmtLib.parse("""
            (declare-sort-parameter A)
            (declare-datatype N ((z) (log (pred N))))
            (assert (= (log z) (log z)))
        """.trimIndent())

        val result = solve(parsed)

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `datatype selectors named log remain productive with global sort parameters`() {
        val parsed = SmtLib.parse("""
            (declare-sort-parameter A)
            (declare-datatype Box ((box (log Int))))
            (assert (= (log (box 7)) 7))
        """.trimIndent())

        val result = solve(parsed)

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `quoted grammar functions retain their spelling through datatype exposure`() {
        val parsed = SmtLib.parse("""
            (declare-datatype Box ((box (field Int))))
            (declare-fun |let| (Int) Int)
            (assert (= (|let| (field (box 1))) 2))
        """.trimIndent())

        val result = solve(parsed)

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `polymorphic expansion keeps nullary constructors distinct from source marker names`() {
        val parsed = SmtLib.parse("""
            (declare-sort-parameter A)
            (declare-datatype Color ((red) (green)))
            (declare-const |datatype:red| Color)
            (assert (distinct red |datatype:red|))
        """.trimIndent())

        val result = solve(parsed)

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `polymorphic expansion keeps constructor names distinct from symbol identity markers`() {
        val parsed = SmtLib.parse("""
            (declare-sort-parameter A)
            (declare-datatype Choice ((|symbol:0:f| (field Int)) (other)))
            (declare-fun f (Int) Choice)
            (assert (distinct (|symbol:0:f| 1) (f 1)))
        """.trimIndent())

        val result = solve(parsed)

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `polymorphic expansion preserves constructor disjointness`() {
        val parsed = SmtLib.parse("""
            (declare-sort-parameter A)
            (declare-datatype Color ((red) (green)))
            (assert (= red green))
        """.trimIndent())

        val result = solve(parsed)

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `polymorphic expansion refutes different tester results on successors`() {
        val parsed = SmtLib.parse("""
            (declare-sort-parameter A)
            (declare-datatype Nat ((zero) (succ (pred Nat))))
            (assert (let ((x (succ zero)) (y (succ (succ zero))))
                (and (distinct x y) (not (= ((_ is succ) x) ((_ is succ) y))))))
        """.trimIndent())

        val result = solve(parsed)

        assertIs<OpenTheoryResult.Unsat>(result, "termination reason: ${(result as? OpenTheoryResult.Unknown)?.reason}")
    }

    @Test
    fun `polymorphic expansion preserves negative constructor testers`() {
        val parsed = SmtLib.parse("""
            (declare-sort-parameter A)
            (declare-datatype Nat ((zero) (succ (pred Nat))))
            (assert ((_ is succ) zero))
        """.trimIndent())

        val result = solve(parsed)

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `polymorphic expansion retains match field bindings`() {
        val parsed = SmtLib.parse("""
            (declare-sort-parameter A)
            (declare-datatype Option ((none) (some (field Int))))
            (declare-const x Option)
            (assert (= (match x ((none 0) ((some v) v))) 1))
        """.trimIndent())

        val result = solve(parsed)

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `polymorphic expansion preserves match constructor guards`() {
        val parsed = SmtLib.parse("""
            (declare-sort-parameter A)
            (declare-datatype Option ((none) (some (field Int))))
            (assert (= (match none ((none 0) ((some v) v))) 1))
        """.trimIndent())

        val result = solve(parsed)

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `polymorphic expansion retains match catch all bindings`() {
        val parsed = SmtLib.parse("""
            (declare-sort-parameter A)
            (declare-datatype Option ((none) (some (field Int))))
            (declare-const x Option)
            (assert (= x (some 3)))
            (assert (= (let ((v 9)) (match x ((none v) (rest (field rest))))) 3))
        """.trimIndent())

        val result = solve(parsed)

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `polymorphic expansion preserves lexical match field shadowing`() {
        val parsed = SmtLib.parse("""
            (declare-sort-parameter A)
            (declare-datatype Option ((none) (some (field Int))))
            (assert (= (let ((v 9)) (match (some 3) ((none v) ((some v) v)))) 3))
        """.trimIndent())

        val result = solve(parsed)

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `polymorphic expansion retains qualified phantom constructor ranks`() {
        val parsed = SmtLib.parse("""
            (declare-sort-parameter A)
            (declare-datatype Phantom (par (T) ((none) (some (field Int)))))
            (declare-const x (Phantom Bool))
            (assert (distinct x (as none (Phantom Bool))))
            (assert (= x ((as some (Phantom Bool)) 7)))
        """.trimIndent())

        val result = solve(parsed)

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `polymorphic expansion retains qualified numeric constructor ranks`() {
        val parsed = SmtLib.parse("""
            (declare-sort-parameter A)
            (declare-datatype Box (par (T) ((box (field T)))))
            (declare-const x (Box Real))
            (assert (= x ((as box (Box Real)) 1)))
            (assert (= (field x) 1.0))
        """.trimIndent())

        val result = solve(parsed)

        assertIs<OpenTheoryResult.Sat>(result)
    }

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
    fun `captured alias sorts are not replaced by datatype parameters`() {
        val parsed = SmtLib.parse("""
            (declare-sort U 0)
            (define-sort Captured () U)
            (declare-datatype Box (par (U) ((box (field Captured)))))
            (declare-const a (Box Bool))
            (declare-const b (Box Bool))
            (declare-const c (Box Bool))
            (assert (distinct a b c))
        """.trimIndent())

        val result = solve(parsed)

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `datatype fields reject unbound global type parameters`() {
        assertFailsWith<UnsupportedSmtException> {
            SmtLib.parse("(declare-sort-parameter A) (declare-datatype D ((ctor (field A))))")
        }
    }

    @Test
    fun `local datatype parameters may shadow global type parameters`() {
        val parsed = SmtLib.parse("""
            (declare-sort-parameter A)
            (declare-datatype Box (par (A) ((box (field A)))))
            (declare-const x (Box Bool))
            (assert (= x (box true)))
        """.trimIndent())

        val assignment = assertIs<OpenTheoryResult.Sat>(solve(parsed)).assignment

        assertEquals("((as box (Box Bool)) true)",
            parsed.reconstructModel(assignment).values.single().second.toSmtLib())
    }

    @Test
    fun `datatype parameters cannot shadow theory sorts or reserved names`() {
        for (name in listOf("Int", "Bool", "Real", "Array", "BitVec", "@private", ".private")) {
            assertFailsWith<UnsupportedSmtException> {
                SmtLib.parse("(declare-datatype D (par ($name) ((ctor))))")
            }
        }
    }

    @Test
    fun `pi declarations retain their lexical meaning in datatype fields`() {
        val parsed = SmtLib.parse("""
            (declare-datatype Box ((box (field Int))))
            (declare-const pi Int)
            (assert (= pi 7))
            (assert (= (field (box pi)) 7))
        """.trimIndent())

        val result = solve(parsed)

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `wrong selector tables preserve the chosen observed value`() {
        val parsed = SmtLib.parse("""
            (declare-datatype Option ((none) (some (field Int))))
            (assert (= (field none) 19))
        """.trimIndent())
        val assignment = assertIs<OpenTheoryResult.Sat>(solve(parsed)).assignment

        val interpretation = retained(parsed, assignment) { values ->
            values.value(smtSelectorInterpretationKey("field", SmtSort.Uninterpreted("Option"), SmtSort.Int))
        }

        val table = assertIs<SmtValue.Function>(interpretation)
        assertTrue(table.entries.any { (arguments, result) ->
            arguments.single() == SmtValue.Application("none", emptyList()) && result == SmtValue.Integer("19")
        })
        assertIs<SmtValue.Integer>(table.default)
    }

    @Test
    fun `polymorphic selector tables retain separate result ranks`() {
        val parsed = SmtLib.parse("""
            (declare-datatypes ((Option 1)) ((par (T) ((none) (some (field T))))))
            (assert (= (field (as none (Option Int))) 19))
            (assert (not (field (as none (Option Bool)))))
        """.trimIndent())
        val assignment = assertIs<OpenTheoryResult.Sat>(solve(parsed)).assignment

        val tables = retained(parsed, assignment) { values ->
            listOf(SmtSort.Int, SmtSort.Bool).map { sort ->
                values.value(smtSelectorInterpretationKey("field", SmtSort.Uninterpreted("Option", listOf(sort)), sort))
            }
        }

        assertIs<SmtValue.Integer>(assertIs<SmtValue.Function>(tables[0]).default)
        assertIs<SmtValue.Boolean>(assertIs<SmtValue.Function>(tables[1]).default)
    }

    @Test
    fun `wrong constructor selectors may return their own argument`() {
        val parsed = SmtLib.parse("""
            (declare-datatype Nat ((zero) (succ (pred Nat))))
            (assert (= (pred zero) zero))
        """.trimIndent())

        val result = solve(parsed)

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `large finite field domains do not enumerate their entire cardinality`() {
        val parsed = SmtLib.parse("""
            (declare-datatype Record ((record (field (Array (_ BitVec 129) Bool)))))
            (declare-const x Record)
            (declare-const y Record)
            (assert (distinct x y))
        """.trimIndent())

        val result = solve(parsed)

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `unobserved selector ranks choose a stable typed interpretation`() {
        val parsed = SmtLib.parse("""
            (declare-datatypes ((Option 1)) ((par (T) ((none) (some (field T))))))
            (declare-const x (Option Bool))
            (assert (= x (as none (Option Bool))))
        """.trimIndent())
        val assignment = assertIs<OpenTheoryResult.Sat>(solve(parsed)).assignment

        val interpretation = retained(parsed, assignment) { values ->
            smtUnobservedSelectorInterpretation(requireNotNull(parsed.originalSource?.typed?.datatypes), "field",
                SmtSort.Uninterpreted("Option", listOf(SmtSort.Int)), SmtSort.Int, values)
        }

        assertEquals(SmtValue.Function(listOf(SmtSort.Uninterpreted("Option", listOf(SmtSort.Int))),
            emptyList(), SmtValue.Integer("0")), interpretation)
    }

    @Test
    fun `single constructor selector queries retain fields on observed and fresh values`() {
        val parsed = SmtLib.parse("""
            (declare-datatype Box ((box (field Bool))))
            (declare-const x Box)
            (assert (field x))
        """.trimIndent())
        val assignment = assertIs<OpenTheoryResult.Sat>(solve(parsed)).assignment

        val model = parsed.reconstructSessionModel(assignment)

        assertEquals("true", model.evaluate(SExprReader("(field x)").readAll().single()))
        assertEquals("false", model.evaluate(SExprReader("(field (box false))").readAll().single()))
    }

    @Test
    fun `real constructor fields retain exact rational values`() {
        val parsed = SmtLib.parse("""
            (declare-datatype Box ((box (field Real))))
            (declare-const x Box)
            (assert (= x (box (/ 1.0 2.0))))
        """.trimIndent())

        val assignment = assertIs<OpenTheoryResult.Sat>(solve(parsed)).assignment
        val model = parsed.reconstructModel(assignment)

        assertEquals("(box (/ 1.0 2.0))", model.values.single().second.toSmtLib())
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
            exposeSmtGroundDatatypes(input, ExecutionControl(Cancellation { true }))
        }
    }

    internal fun solve(parsed: SmtLibProblem): OpenTheoryResult {
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
                    else -> OpenTheoryResult.Unknown(
                        (result.result.nativeSourceResult as? SolveResult.Unknown)?.reason
                            ?: TerminationReason.RepresentationDeclined, outcome.stats)
                }
            }
            else -> error("unexpected datatype source result")
        }
    }

    private fun <T> retained(parsed: SmtLibProblem, assignment: OpenTheoryAssignment,
        action: (SmtModelValues) -> T): T = withSmtModelValues(parsed, assignment::boolValue, assignment::intValue,
        { assignment.smtRealValue(it, ExecutionControl.Never) }, ExecutionControl.Never,
        { assignment.smtExactRealValue(it, ExecutionControl.Never) }) { values, _ -> action(values) }
}
