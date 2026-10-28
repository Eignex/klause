package com.eignex.klause.solver

import com.eignex.klause.arithmetic.real.compareExactly
import com.eignex.klause.arithmetic.real.exactRealValue
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.formats.json.ExactRealJson
import com.eignex.klause.formats.json.ProblemJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SourceSamplesTest {
    @Test
    fun `exact native equality should retain algebraic and symbolic coordinate authority`() {
        for (value in listOf(
            """{"root":{"coefficients":["-2","0","1"],"index":2}}""",
            """{"exp":"1"}""", """{"pi":{}}""",
        )) {
            val problem = ProblemJson.default.decodeFromString("""
                {"bools":1,"reals":[{"lower":0,"upper":4}],
                 "factors":[{"exactRealEquality":{"real":0,"value":$value}}]}
            """.trimIndent())
            val result = assertIs<SolveResult.Sat>(BacktrackSolver.source(problem).solveSample())
            assertEquals(0, result.assignment.exactRealValue(0).compareExactly(ExactRealJson.decodeFromString(value)))
        }
    }

    @Test
    fun `symbolic pins should respect the original native real constraints`() {
        val problem = ProblemJson.default.decodeFromString("""
            {"reals":[{"upper":2}],"factors":[{"exactRealEquality":{"real":0,"value":{"exp":"1"}}}]}
        """.trimIndent())
        assertIs<SolveResult.Unsat>(BacktrackSolver.source(problem).solveSample())
    }
}
