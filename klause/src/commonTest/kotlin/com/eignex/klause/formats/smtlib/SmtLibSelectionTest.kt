package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.componentPlan
import com.eignex.klause.solver.pipeline.search
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchBoolValue
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchExactIntValue
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.solver.search.SearchSolveParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmtLibSelectionTest {
    @Test
    fun `affine integer conditionals retain native selection`() {
        val parsed = SmtLib.parse("""
            (declare-const b Bool) (declare-const x Int) (declare-const y Int)
            (assert (and (<= 0 x 2) (<= 0 y 2) (> (ite b (+ x 1) y) 1)))
        """.trimIndent())

        val sample = assertIs<SolveResult.Sat>(BacktrackSolver(parsed.model.bake())
            .solve(BacktrackParams(randomSeed = 0))).assignment

        assertEquals(1, parsed.model.factors.filterIsInstance<Element>().size)
        val selected = if (sample.boolValue(parsed.boolVarNames.getValue("b"))) {
            sample.intValue(parsed.intVarNames.getValue("x")) + 1
        } else sample.intValue(parsed.intVarNames.getValue("y"))
        assertTrue(selected > 1)
    }

    @Test
    fun `short sparse decision lists retain a complete default branch`() {
        val parsed = SmtLib.parse("""
            (declare-const i Int) (declare-const x Int)
            (assert (and (<= 0 i 1000) (<= 1 x 2)
                (= x (ite (= i 3) 1 (ite (= i 999) 2 0)))))
        """.trimIndent())

        val sample = assertIs<SolveResult.Sat>(BacktrackSolver(parsed.model.bake())
            .solve(BacktrackParams(randomSeed = 0))).assignment

        assertEquals(1, parsed.model.factors.filterIsInstance<Element>().size)
        val index = sample.intValue(parsed.intVarNames.getValue("i"))
        val expected = if (index == 3L) 1L else if (index == 999L) 2L else 0L
        assertEquals(expected, sample.intValue(parsed.intVarNames.getValue("x")))
    }

    @Test
    fun `shared conditions cannot select inconsistent arithmetic branches`() {
        val parsed = SmtLib.parse("""
            (declare-const b Bool)
            (assert (= (+ (ite b 0 1) (ite b 1 0)) 0))
        """.trimIndent())

        val result = BacktrackSolver(parsed.model.bake()).solve(BacktrackParams(randomSeed = 0))

        assertEquals(2, parsed.model.factors.filterIsInstance<Element>().size)
        assertIs<SolveResult.Unsat>(result)
    }

    @Test
    fun `signed conditional guards receive point preferences without assigning source Booleans`() {
        for (positive in listOf(false, true)) {
            val parsed = SmtLib.parse("""
                (declare-const b Bool) (declare-const x Int) (declare-const y Int)
                (assert (= x y))
                (assert (= (+ x y) (ite ${if (positive) "b" else "(not b)"} (+ x 1) (- y 1))))
            """.trimIndent())
            val variable = parsed.boolVarNames.getValue("b")
            parsed.model.componentPlan().search(parsed.model, emptyMap()).use { planned ->
                assertNull(planned.candidateHints.preferredBool(variable))
                assertIs<ComponentResult.Consistent>(planned.session.initialize())
                assertNull(planned.session.boolValue(variable))

                val preferred = assertNotNull(planned.candidateHints.preferredBool(variable))

                assertNull(planned.session.boolValue(variable))
                assertIs<ComponentResult.Consistent>(
                    planned.session.push(SearchDecision.Bool(Lit.make(variable, preferred))),
                )
                val result = assertIs<SearchResult.Satisfied>(planned.session.solve(parsed.model.numBoolVars,
                    SearchSolveParams(maxDecisions = 16), candidateHints = planned.candidateHints))
                assertEquals(preferred, result.model.valueOf(SearchBoolValue(variable)))
                val x = assertNotNull(result.model.valueOf(
                    SearchExactIntValue(SourceIntId(parsed.intVarNames.getValue("x")))))
                val y = assertNotNull(result.model.valueOf(
                    SearchExactIntValue(SourceIntId(parsed.intVarNames.getValue("y")))))
                assertEquals(x, y)
                assertEquals(if (preferred == positive) "1" else "-1", x.toString())
            }
        }
    }

    @Test
    fun `unused choices preserve a derived guard shared by live conditional indexes`() {
        for (branch in listOf(false, true)) {
            val source = """
                (declare-const b Bool) (declare-const c Bool)
                (declare-const x Int) (declare-const y Int)
                (declare-const z Int) (declare-const w Int)
                (assert ${if (branch) "(and b c)" else "(not (and b c))"})
                (assert (= x y)) (assert (= z w))
                (assert (= (+ x y) (ite (and b c) (+ x 1) (- y 1))))
                (assert (= (+ z w) (ite (not (and b c)) (+ z 2) (- w 2))))
            """.trimIndent()
            val live = SmtLib.parse(source)
            for (redundant in listOf(false, true)) {
                val parsed = if (redundant) SmtLib.parse(source +
                    "\n(assert (= (ite (and b c) 7 8) ${if (branch) 7 else 8}))") else live

                val result = BacktrackSolver.source(parsed.model)
                    .solve(BacktrackParams(randomSeed = 0, maxDecisions = 100))

                assertEquals(live.model.numIntVars, parsed.model.numIntVars)
                assertEquals(live.model.numBoolVars, parsed.model.numBoolVars)
                val selections = parsed.model.factors.filterIsInstance<Element>()
                assertEquals(2, selections.size)
                assertEquals(2, selections.map { it.idx }.distinct().size)
                val sample = assertIs<OpenTheoryResult.Sat>(result, result.toString()).assignment
                assertEquals(branch, sample.boolValue(parsed.boolVarNames.getValue("b")) &&
                    sample.boolValue(parsed.boolVarNames.getValue("c")))
                assertEquals(if (branch) "1" else "-1", sample.intValue(parsed.intVarNames.getValue("x")))
                assertEquals(sample.intValue(parsed.intVarNames.getValue("x")),
                    sample.intValue(parsed.intVarNames.getValue("y")))
                assertEquals(if (branch) "-2" else "2", sample.intValue(parsed.intVarNames.getValue("z")))
                assertEquals(sample.intValue(parsed.intVarNames.getValue("z")),
                    sample.intValue(parsed.intVarNames.getValue("w")))
            }
        }
    }

    @Test
    fun `negative real conditional guards retain their source branch`() {
        for (branch in listOf(false, true)) {
            val parsed = SmtLib.parse("""
                (declare-const b Bool) (declare-const x Real) (declare-const y Real)
                (assert ${if (branch) "b" else "(not b)"}) (assert (= x (/ 2 3)))
                (assert (= y (ite (not b) (+ x (/ 1 3)) (- x (/ 1 3)))))
            """.trimIndent())

            val result = BacktrackSolver.source(parsed.model).solve(BacktrackParams(randomSeed = 0, maxDecisions = 100))

            assertEquals(if (branch) "1/3" else "1",
                assertIs<OpenTheoryResult.Sat>(result, result.toString()).assignment
                    .realValue(parsed.realVarNames.getValue("y")))
        }
    }

    @Test
    fun `Boolean conditional normalization removes unused private choices`() {
        val parsed = SmtLib.parse("(declare-const b Bool) (assert (= (ite b 1 2) 1))")

        assertEquals(0, parsed.model.numIntVars)
        assertTrue(parsed.model.factors.none { it is Element })
    }

    @Test
    fun `wide constants remain exact beside an open conditional branch`() {
        val magnitude = "18446744073709551623"
        val parsed = SmtLib.parse("""
            (declare-const b Bool) (declare-const x Int) (declare-const y Int)
            (assert b) (assert (= y (ite b $magnitude x)))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model).solve(BacktrackParams(randomSeed = 0, maxDecisions = 100))

        assertEquals(magnitude, assertIs<OpenTheoryResult.Sat>(result, result.toString()).assignment
            .intValue(parsed.intVarNames.getValue("y")))
    }

    @Test
    fun `wide conditional digits share one source condition`() {
        val magnitude = "18446744073709551623"
        for (branch in listOf(true, false)) {
            val parsed = SmtLib.parse("""
                (declare-const b Bool) (declare-const y Int)
                (assert ${if (branch) "b" else "(not b)"})
                (assert (= y (ite b $magnitude (- $magnitude))))
                (assert (${if (branch) ">=" else "<="} y ${if (branch) magnitude else "(- $magnitude)"}))
            """.trimIndent())

            val result = BacktrackSolver.source(parsed.model).solve(BacktrackParams(randomSeed = 0, maxDecisions = 100))

            val selections = parsed.model.factors.filterIsInstance<Element>()
            assertTrue(selections.isNotEmpty())
            assertEquals(1, selections.map { it.idx }.distinct().size)
            assertEquals(if (branch) magnitude else "-$magnitude",
                assertIs<OpenTheoryResult.Sat>(result, result.toString()).assignment
                    .intValue(parsed.intVarNames.getValue("y")))
        }
    }
}
