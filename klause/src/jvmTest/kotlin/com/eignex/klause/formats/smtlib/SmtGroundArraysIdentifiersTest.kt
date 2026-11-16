package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertIs

@RunWith(Parameterized::class)
class SmtGroundArraysIdentifiersTest(private val source: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun sources(): List<Array<String>> {
            val numeric = "(declare-const a (Array Bool Int)) (declare-const |0| Int) (assert (= |0| 1)) "
            val boolean = "(declare-const a (Array Bool Bool)) "
            return listOf(
                numeric + "(assert (= (select a false) 0)) (assert (= (select a false) |0|))",
                numeric + "(assert (= (select (store a false 0) false) |0|))",
                numeric + "(assert (= 0 |0|))",
                boolean + "(assert (not (|select| (|ite| true (|store| a true true) a) true)))",
                boolean + "(assert (not (|=| a a)))",
                boolean + "(assert (not ((as select Bool) ((as ite (Array Bool Bool)) true " +
                    "((as store (Array Bool Bool)) a true true) a) true)))",
                boolean + "(assert (not ((as = Bool) a a)))",
            ).map { arrayOf(it) }
        }
    }

    @Test
    fun `array identifier spellings should preserve contradictions`() {
        val parsed = SmtLib.parse(source)

        val result = BacktrackSolver.source(parsed.model).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }
}
