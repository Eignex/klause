package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.lowering.PolynomialSourceInterrupted
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FiniteSolveCallbacks
import com.eignex.klause.solver.pipeline.FiniteSolveOutcome
import com.eignex.klause.solver.pipeline.FiniteSolveVerdict
import com.eignex.klause.solver.pipeline.SourceExecutionCallbacks
import com.eignex.klause.solver.pipeline.SourceExecutionRequest
import com.eignex.klause.solver.pipeline.SourceExecutionResult
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SmtGroundArraysTest {
    @Test
    fun `store should replace the selected value at its index`() {
        val source = source("(declare-const a (Array Bool Bool)) " +
            "(assert (not (select (store a true true) true)))")

        val result = BacktrackSolver.source(source.model).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `store should retain values at other indexes`() {
        val source = source("(declare-const a (Array Bool Bool)) (assert (select a false)) " +
            "(assert (not (select (store a true false) false)))")

        val result = BacktrackSolver.source(source.model).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `equal arrays should agree at every represented index`() {
        val source = source("(declare-const a (Array Bool Bool)) (declare-const b (Array Bool Bool)) " +
            "(assert (= a b)) (assert (distinct (select a false) (select b false)))")

        val result = BacktrackSolver.source(source.model).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `array equality under negation should reject equal total witnesses`() {
        val source = source("(declare-const a (Array Bool Bool)) (declare-const b (Array Bool Bool)) " +
            "(assert (not (= a b)))")

        assertFailsWith<IllegalArgumentException> {
            source.reconstructModel({ false }, { "0" }, { "0.0" })
        }
    }

    @Test
    fun `unused array functions should reconstruct a total typed default`() {
        val source = source("(declare-fun f (Bool) (Array Bool Bool))")

        val model = source.reconstructModel({ false }, { "0" }, { "0.0" })

        val function = assertIs<SmtValue.Function>(model.values.single().second)
        val array = assertIs<SmtValue.Array>(function.default)
        assertEquals(SmtValue.Boolean(false), array.default)
        assertTrue(model.toSmtLib().contains("(as const (Array Bool Bool))"))
        assertEquals(listOf("f"), model.values.map { it.first.name })
    }

    @Test
    fun `array conditionals should retain their selected branch`() {
        val source = source("(declare-const a (Array Bool Bool)) (assert (not (select a false))) " +
            "(assert (select (ite true (store a false true) a) false))")
        val invocation = SourceSetup.prepare(SourceSetupRequest(source.source, ExecutionOperation.SATISFY,
            FiniteEngine.BACKTRACK))
        val models = ArrayList<SmtSourceModel>()

        val result = SourceSetup.execute(SourceExecutionRequest(invocation), SourceExecutionCallbacks(
            finite = FiniteSolveCallbacks({ models += source.reconstructModel(it) }, {})))

        val completed = assertIs<FiniteSolveOutcome.Completed>(assertIs<SourceExecutionResult.Finite>(result).result.outcome)
        assertEquals(FiniteSolveVerdict.SAT, completed.verdict)
        val array = assertIs<SmtValue.Array>(models.single().values.single().second)
        assertEquals(SmtValue.Boolean(false), array.entries.last { it.first == SmtValue.Boolean(false) }.second)
    }

    @Test
    fun `array elements should retain nested store reads`() {
        val source = source("(declare-const a (Array Bool (Array Bool Bool))) " +
            "(declare-const b (Array Bool Bool)) (assert (select b false)) " +
            "(assert (not (select (select (store a true b) true) false)))")

        val result = BacktrackSolver.source(source.model).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `array indexes should respect extensional equality`() {
        val source = source("(declare-const a (Array (Array Bool Bool) Bool)) " +
            "(declare-const i (Array Bool Bool)) (declare-const j (Array Bool Bool)) " +
            "(assert (= i j)) (assert (select a i)) (assert (not (select a j)))")

        val result = BacktrackSolver.source(source.model).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `uninterpreted indexes should retain scalar identity constraints`() {
        val source = source("(declare-sort U 0) (declare-const a (Array U Bool)) " +
            "(declare-const i U) (declare-const j U) (assert (= i j)) " +
            "(assert (select a i)) (assert (not (select a j)))")

        val result = BacktrackSolver.source(source.model).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `functions should agree on extensionally equal array arguments`() {
        val source = source("(declare-const a (Array Bool Bool)) (declare-const b (Array Bool Bool)) " +
            "(declare-fun p ((Array Bool Bool)) Bool) (assert (= a b)) " +
            "(assert (p a)) (assert (not (p b)))")

        val result = BacktrackSolver.source(source.model).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `array integer elements should not receive scalar declaration overrides`() {
        val source = SmtLib.parse("(declare-const a (Array Bool Int)) (assert (= (select a true) 100))", 0, 2)

        val model = source.reconstructModel({ true }, { "100" }, { "0.0" })

        val array = assertIs<SmtValue.Array>(model.values.single().second)
        assertEquals(SmtValue.Integer("100"), array.entries.single().second)
        assertTrue(source.intVarNames.isEmpty())
    }

    @Test
    fun `quoted numeral symbols should remain distinct from literals`() {
        val formulas = listOf(
            "(assert (= (select a false) 0)) (assert (= (select a false) |0|))",
            "(assert (= (select (store a false 0) false) |0|))",
            "(assert (= 0 |0|))",
        )
        for (formula in formulas) {
            val source = source("(declare-const a (Array Bool Int)) (declare-const |0| Int) " +
                "(assert (= |0| 1)) $formula")

            val result = BacktrackSolver.source(source.model).solve()

            assertIs<OpenTheoryResult.Unsat>(result, formula)
        }
    }

    @Test
    fun `quoted numeral symbols should retain their values beside literal array stores`() {
        val source = source("(declare-const a (Array Bool Int)) (declare-const |0| Int) " +
            "(assert (= |0| 1)) (assert (= (select (store a false 0) false) 0))")

        val assignment = assertIs<OpenTheoryResult.Sat>(BacktrackSolver.source(source.model).solve()).assignment
        val model = source.reconstructModel(assignment)

        assertEquals(SmtValue.Integer("1"), model.values.single { it.first.name == "|0|" }.second)
    }

    @Test
    fun `quoted and qualified array builtins should preserve their resolved meanings`() {
        val heads = listOf(
            listOf("|select|", "|store|", "|ite|", "|=|"),
            listOf("(as select Bool)", "(as store (Array Bool Bool))", "(as ite (Array Bool Bool))", "(as = Bool)"),
        )
        for ((select, store, ite, equality) in heads) {
            val formulas = listOf(
                "(not ($select ($ite true ($store a true true) a) true))",
                "(not ($equality a a))",
            )
            for (formula in formulas) {
                val source = source("(declare-const a (Array Bool Bool)) (assert $formula)")

                val result = BacktrackSolver.source(source.model).solve()

                assertIs<OpenTheoryResult.Unsat>(result, formula)
            }
        }
    }

    @Test
    fun `qualified array conditionals should reconstruct productive source models`() {
        val source = source("(declare-const a (Array Bool Bool)) (assert (not (select a true))) " +
            "(assert ((as select Bool) ((as ite (Array Bool Bool)) true " +
            "((as store (Array Bool Bool)) a true true) a) true))")

        val assignment = assertIs<OpenTheoryResult.Sat>(BacktrackSolver.source(source.model).solve()).assignment
        val model = source.reconstructModel(assignment)

        val array = assertIs<SmtValue.Array>(model.values.single().second)
        assertEquals(SmtValue.Boolean(false), array.entries.last { it.first == SmtValue.Boolean(true) }.second)
    }

    @Test
    fun `cancellation should interrupt array closure before publication`() {
        val input = captureSmtSource(SExprReader("(declare-const a (Array Bool Bool))").readAll(), Cancellation.Never)

        assertFailsWith<PolynomialSourceInterrupted> {
            reduceSmtGroundArrays(input, Cancellation { true })
        }
    }

    private fun source(script: String): SmtLibProblem = SmtLib.parse(script)
}
