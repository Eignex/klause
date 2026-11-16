package com.eignex.klause.cli

import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.checkMpsWitness
import com.eignex.klause.formats.mps.toLinearSourceProblem
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MpsOutputTest {
    @Test
    fun `an exact nominal unbounded ray should remain valid in the tolerance accepted superset`() {
        val output = MpsOutput(exactSourceWitness = { true })

        val text = captureStdout {
            output.onExactSolution("v X=1", BigFraction.ONE)
            output.onComplete(Verdict.UNBOUNDED)
        }

        assertTrue("s UNBOUNDED" in text.lines(), text)
        assertTrue("v X=1" in text.lines(), text)
    }

    @Test
    fun `float rendering should check zero reconstruction against source rows`() {
        val source = assertNotNull(Mps.parse(
            "ROWS\n N COST\n E R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 1 R -1\n" +
                " M1 'MARKER' 'INTEND'\n X R 1\nRHS\n RHS R 0\nBOUNDS\n BV B I\n LO B X 0\nENDATA",
        ).toLinearSourceProblem())
        for (residual in listOf(-5e-14, 5e-14)) {
            val point = assertNotNull(source.checkMpsWitness(
                listOf(BigFraction.ZERO, assertNotNull(BigFraction.ofDouble(residual))),
            ))

            val rendered = renderMpsSourceWitness(point, reconstruct = true)

            assertEquals("v I=0 X=0", rendered)
        }
    }

    @Test
    fun `zero reconstruction should preserve a nonzero source equality`() {
        val source = assertNotNull(Mps.parse(
            "ROWS\n N COST\n E R\nCOLUMNS\n X COST 0 R 1\nRHS\n RHS R 0.00000000000001\n" +
                "BOUNDS\n LO B X 0\nENDATA",
        ).toLinearSourceProblem())
        val value = assertNotNull(BigFraction.ofDouble(1e-14))
        val point = assertNotNull(source.checkMpsWitness(listOf(value)))

        val rendered = renderMpsSourceWitness(point, reconstruct = true)

        assertEquals("v X=$value", rendered)
    }

    @Test
    fun `zero reconstruction should preserve the source objective at the float point`() {
        val source = assertNotNull(Mps.parse("ROWS\n N COST\nCOLUMNS\n X COST 1\nBOUNDS\n LO B X 0\nENDATA")
            .toLinearSourceProblem())
        val value = assertNotNull(BigFraction.ofDouble(-5e-14))
        val point = assertNotNull(source.checkMpsWitness(listOf(value)))

        val rendered = renderMpsSourceWitness(point, reconstruct = true)

        assertEquals("v X=$value", rendered)
    }

    @Test
    fun `accepted MPS objectives should retain original units and decimal formatting`() {
        val output = MpsOutput()

        val text = captureStdout {
            output.onArithmeticIncumbent(
                "v X=1/2",
                SourceValue.Rational(
                    BigFraction.ONE *
                        BigFraction.ofLong(2).reciprocal(),
                ),
            )
        }

        assertEquals("o 0.5\nv X=1/2\n", text)
    }

    @Test
    fun `an exact objective with no finite decimal should print as a fraction`() {
        val third = BigFraction.ofLong(3L).reciprocal()
        val output = MpsOutput()

        val text = captureStdout { output.onExactSolution("v X=1/3", third) }

        assertTrue("o 1/3" in text, text)
    }

    @Test
    fun `an exact objective with a finite decimal should stay decimal`() {
        val half = BigFraction.ofLong(2L).reciprocal()
        val output = MpsOutput()

        val text = captureStdout { output.onExactSolution("v X=0.5", half) }

        assertTrue("o 0.5" in text, text)
    }

    @Test
    fun `a feasible verdict without a verified source witness should be unknown`() {
        val output = MpsOutput()

        val text = captureStdout { output.onComplete(Verdict.OPTIMAL) }

        assertTrue("s UNKNOWN" in text, text)
        assertTrue("s SATISFIABLE" !in text, text)
    }

    @Test
    fun `a tolerance witness should not support source unboundedness`() {
        val output = MpsOutput(exactSourceWitness = { false })

        val text = captureStdout {
            output.onSolution("v X=0", objective = 0)
            output.onComplete(Verdict.UNBOUNDED)
        }

        assertTrue("s SATISFIABLE" in text.lines(), text)
        assertFalse("s UNBOUNDED" in text.lines(), text)
    }
}
