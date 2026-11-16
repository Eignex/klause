package com.eignex.klause.formats.smtlib

import com.eignex.klause.solver.pipeline.OpenTheoryResult
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertIs

@RunWith(Parameterized::class)
class SmtGroundDatatypesGrammarTest(private val source: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun sources(): List<Array<String>> = listOf("let", "as", "match", "!", "_").flatMap { name ->
            listOf(
                "(declare-datatype Box ((|$name| (field Int)))) (assert (= (field (|$name| 1)) 1))",
                "(declare-datatype Box ((box (|$name| Int)))) (assert (= (|$name| (box 1)) 1))",
            ).map { arrayOf(it) }
        }
    }

    @Test
    fun `quoted grammar datatype operators retain their symbol meaning`() {
        val parsed = SmtLib.parse(source)

        val result = SmtGroundDatatypesTest().solve(parsed)

        assertIs<OpenTheoryResult.Sat>(result)
    }
}
