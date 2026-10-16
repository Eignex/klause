package com.eignex.klause.cli

import com.eignex.klause.solver.pipeline.ProblemPipeline
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@RunWith(Parameterized::class)
class SmtLibModeRoutingTest(private val expression: String, private val declaredInteger: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} declared integer={1}")
        fun variants(): List<Array<Any>> = listOf(
            arrayOf("(ite b 1.0 2.0)", false),
            arrayOf("(to_real (ite b 1 2))", false),
            arrayOf("(to_real (to_int x))", false),
            arrayOf("(to_real i)", true),
        )
    }

    @Test
    fun `source real arithmetic should retain private integer semantics in its selected route`() {
        val source = File.createTempFile("smt-route", ".smt2")
        source.writeText(
            """
                (declare-const b Bool)
                (declare-const x Real)
                (declare-const y Real)
                ${if (declaredInteger) "(declare-const i Int) (assert (>= i 0)) (assert (<= i 1))" else ""}
                (assert (= y $expression))
                (check-sat)
            """.trimIndent(),
        )

        val loaded = try {
            SmtLibMode.newSession().load(source.absolutePath, CommonOptions())
        } finally {
            source.delete()
        }

        if (declaredInteger) {
            assertEquals(SolvablePipeline.FiniteCp, loaded.pipeline)
        } else {
            val selected = assertIs<SolvablePipeline.OpenTheory>(loaded.pipeline)
            assertEquals(ProblemPipeline.EXACT_LIRA, selected.request.route)
        }
    }
}
