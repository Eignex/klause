package com.eignex.klause.cli

import com.eignex.klause.formats.mps.MpsSourceWitness
import com.eignex.klause.simplex.exact.BigFraction
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertTrue

class MpsModeTest {
    private fun capture(block: () -> Unit): String {
        val output = ByteArrayOutputStream()
        val previous = System.out
        System.setOut(PrintStream(output))
        try {
            block()
        } finally {
            System.setOut(previous)
        }
        return output.toString()
    }

    @Test
    fun `a source objective with no finite decimal prints as a fraction`() {
        val third = BigFraction.ofLong(3L).reciprocal()
        val output = MpsOutput(sourceWitness = { MpsSourceWitness(listOf(third), third) })

        val text = capture { output.onSolution("v X=1/3", objective = 0L, continuousObjective = 1.0 / 3.0) }

        assertTrue("o 1/3" in text, text)
    }

    @Test
    fun `a source objective with a finite decimal stays decimal`() {
        val half = BigFraction.ofLong(2L).reciprocal()
        val output = MpsOutput(sourceWitness = { MpsSourceWitness(listOf(half), half) })

        val text = capture { output.onSolution("v X=0.5", objective = 0L, continuousObjective = 0.5) }

        assertTrue("o 0.5" in text, text)
    }

    @Test
    fun `a source difference qualifies the lowered optimum`() {
        val output = MpsOutput(sourceExact = false, sourceDifference = "objective coefficient 'X'")

        val text = capture {
            output.onSolution("v X=1", objective = 1L)
            output.onComplete(Verdict.OPTIMAL)
        }

        assertTrue("s SATISFIABLE" in text, text)
        assertTrue("lowered model differs from MPS source at objective coefficient 'X'" in text, text)
    }

    @Test
    fun `a feasible verdict without a verified source witness is unknown`() {
        val output = MpsOutput(sourceExact = false, sourceDifference = "row 'R' coefficient")

        val text = capture { output.onComplete(Verdict.OPTIMAL) }

        assertTrue("s UNKNOWN" in text, text)
        assertTrue("s SATISFIABLE" !in text, text)
    }

    @Test
    fun `a source difference declines negative terminal claims`() {
        listOf(Verdict.UNSATISFIABLE, Verdict.UNBOUNDED).forEach { verdict ->
            val output = MpsOutput(sourceExact = false, sourceDifference = "row 'R' coefficient")

            val text = capture { output.onComplete(verdict) }

            assertTrue("s UNKNOWN" in text, text)
        }
    }
}
