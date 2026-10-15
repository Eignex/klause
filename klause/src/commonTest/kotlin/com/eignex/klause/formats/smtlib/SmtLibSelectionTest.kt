package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.table.Element
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
