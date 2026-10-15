package com.eignex.klause.factor.table

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class RealElementTest {
    @Test
    fun `a selected real result alias retains an unbounded source direction`() {
        val source = Problem(0, 1, arrayOf(IntDomain(1, 1)),
            arrayOf<Factor>(RealElement(0, 0, intArrayOf(0))),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))

        val result = BacktrackSolver(source.bake()).minimize(LinearObjective(realCoefficients = doubleArrayOf(-1.0)),
            BacktrackParams(randomSeed = 0))

        assertIs<MinimizeResult.Unbounded>(result)
    }

    @Test
    fun `real selection optimizes across every legal index`() {
        val source = Problem(0, 1, arrayOf(IntDomain(1, 3)),
            arrayOf<Factor>(RealElement(0, 0, doubleArrayOf(3.0, 1.0, 2.0))),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(4.0))

        val result = BacktrackSolver(source.bake()).minimize(LinearObjective(realCoefficients = doubleArrayOf(1.0)),
            BacktrackParams(randomSeed = 0))

        assertEquals(1.0, assertIs<MinimizeResult.Optimal>(result).objective)
    }

    @Test
    fun `real selection preserves an index beyond the signed integer range`() {
        val source = Problem(0, 1, arrayOf(IntDomain(Int.MAX_VALUE.toLong() + 1, Int.MAX_VALUE.toLong() + 1)),
            arrayOf<Factor>(RealElement(0, 0, doubleArrayOf(1.5, 2.5), indexOffset = Int.MAX_VALUE)),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(3.0))

        val result = BacktrackSolver(source.bake()).solve(BacktrackParams(randomSeed = 0))

        assertEquals(2.5, assertIs<SolveResult.Sat>(result).assignment.realValue(0))
    }

    @Test
    fun `real selections with a shared index require one consistent value`() {
        val source = Problem(0, 1, arrayOf(IntDomain(1, 2)),
            arrayOf<Factor>(RealElement(0, 0, doubleArrayOf(1.0, 2.0)),
                RealElement(0, 0, doubleArrayOf(2.0, 1.0))),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(3.0))

        val result = BacktrackSolver(source.bake()).solve(BacktrackParams(randomSeed = 0))

        assertIs<SolveResult.Unsat>(result)
    }

    @Test
    fun `a selected real entry may alias the result`() {
        val source = Problem(0, 1, arrayOf(IntDomain(2, 2)),
            arrayOf<Factor>(RealElement(0, 1, intArrayOf(0, 1))),
            numRealVars = 2, realLower = doubleArrayOf(2.0, 3.0), realUpper = doubleArrayOf(2.0, 3.0))

        val result = BacktrackSolver(source.bake()).solve(BacktrackParams(randomSeed = 0))

        assertEquals(3.0, assertIs<SolveResult.Sat>(result).assignment.realValue(1))
    }

    @Test
    fun `empty real selections have no valid index`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 0)),
            arrayOf<Factor>(RealElement(0, 0, doubleArrayOf(), indexOffset = 0)),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))

        val result = BacktrackSolver(source.bake()).solve(BacktrackParams(randomSeed = 0))

        assertIs<SolveResult.Unsat>(result)
    }

    @Test
    fun `remapping preserves real selection order and coordinate namespaces`() {
        val source = RealElement(1, 0, intArrayOf(0, 1, 0), indexOffset = -2)

        val remapped = assertIs<RealElement>(source.remap(VarRemap(intArrayOf(), intArrayOf(1, 0), intArrayOf(1, 0))))

        assertEquals(0, remapped.idx)
        assertEquals(1, remapped.result)
        assertEquals(-2, remapped.indexOffset)
        assertContentEquals(intArrayOf(1, 0, 1), remapped.entries)
        assertNotEquals(source.structuralKey(), remapped.structuralKey())
    }
}
