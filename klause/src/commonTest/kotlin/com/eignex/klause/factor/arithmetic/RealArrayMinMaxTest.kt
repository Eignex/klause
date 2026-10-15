package com.eignex.klause.factor.arithmetic

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class RealArrayMinMaxTest {
    @Test
    fun `real extrema should require equality with an operand beyond their envelopes`() {
        for (max in listOf(false, true)) {
            val result = if (max) 3.0 else 0.0
            val source = Problem(
                0,
                0,
                emptyArray(),
                arrayOf<Factor>(RealArrayMinMax(2, intArrayOf(0, 1, 0), max)),
                numRealVars = 3,
                realLower = doubleArrayOf(1.0, 2.0, result),
                realUpper = doubleArrayOf(1.0, 2.0, result),
            )

            val verdict = BacktrackSolver.source(source).solve()

            assertIs<OpenTheoryResult.Unsat>(verdict)
        }
    }

    @Test
    fun `a real extremum should allow aliasing a repeated operand`() {
        for (max in listOf(false, true)) {
            val selected = if (max) 2.0 else 1.0
            val source = Problem(
                0,
                0,
                emptyArray(),
                arrayOf<Factor>(RealArrayMinMax(0, intArrayOf(0, 1, 0), max)),
                numRealVars = 2,
                realLower = doubleArrayOf(selected, 1.5),
                realUpper = doubleArrayOf(selected, 1.5),
            )

            val verdict = BacktrackSolver.source(source).solve()

            assertEquals(selected.toInt().toString(), assertIs<OpenTheoryResult.Sat>(verdict).assignment.realValue(0))
        }
    }

    @Test
    fun `real extremum remapping should preserve ordered repeated operands`() {
        val source = RealArrayMinMax(0, intArrayOf(1, 0, 1), max = true)
        val mapping = VarRemap(intArrayOf(), intArrayOf(), intArrayOf(1, 0))

        val remapped = assertIs<RealArrayMinMax>(source.remap(mapping))

        assertEquals(1, remapped.result)
        assertContentEquals(intArrayOf(0, 1, 0), remapped.xs)
        assertEquals(source.remapStructuralHash(mapping), remapped.structuralKey().hashCode())
        assertNotEquals(source.structuralKey(), remapped.structuralKey())
    }
}
