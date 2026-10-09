package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.Format
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SolveEvidenceTest {
    @Test
    fun `only the last complete minizinc candidate is retained`() {
        val raw = "x = 1;\n----------\nx = 2;\n----------\nx = 3;\n"

        val witness = SolveEvidence.finalWitness(Format.MINIZINC, raw)

        assertEquals("x = 2;", witness)
    }

    @Test
    fun `competition records retain the final rendered witness`() {
        for (format in listOf(Format.OPB, Format.XCSP3, Format.DIMACS, Format.WCNF, Format.MPS)) {
            val witness = SolveEvidence.finalWitness(format, "v x1\no 2\nv -x1 x2\nc solveTime=1\n")

            assertEquals("v -x1 x2", witness)
        }
    }

    @Test
    fun `a stream without a complete candidate carries no witness`() {
        for (format in listOf(Format.MINIZINC, Format.OPB, Format.XCSP3)) {
            assertNull(SolveEvidence.finalWitness(format, "=====UNKNOWN=====\n"))
        }
    }
}
