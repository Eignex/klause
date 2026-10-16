package com.eignex.klause.factor.table

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
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
import kotlin.test.assertTrue

class RealChoiceTest {
    @Test
    fun `signed guards should select their exact real arm`() {
        for (positive in listOf(false, true)) {
            for (truth in listOf(false, true)) {
                val source = Problem(1, 0, emptyArray(), arrayOf<Factor>(
                    Clause(intArrayOf(Lit.make(0, truth))), RealChoice(Lit.make(0, positive), 0, 1, 2)),
                    numRealVars = 3, realLower = doubleArrayOf(0.0, 1.25, 2.5),
                    realUpper = doubleArrayOf(3.0, 1.25, 2.5))

                val result = BacktrackSolver(source.bake()).solve(BacktrackParams(randomSeed = 0))

                val point = assertIs<SolveResult.Sat>(result).assignment
                assertEquals(if (truth == positive) 1.25 else 2.5, point.reals[0])
            }
        }
    }

    @Test
    fun `equal real arms should leave either guard value feasible`() {
        for (truth in listOf(false, true)) {
            val source = Problem(1, 0, emptyArray(), arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, truth))), RealChoice(Lit.make(0, true), 0, 1, 1)),
                numRealVars = 2, realLower = doubleArrayOf(1.25, 1.25), realUpper = doubleArrayOf(1.25, 1.25))

            val result = BacktrackSolver(source.bake()).solve(BacktrackParams(randomSeed = 0))

            assertEquals(truth, assertIs<SolveResult.Sat>(result).assignment.boolValue(0))
        }
    }

    @Test
    fun `a refuted choice should retain its feasible Boolean sibling`() {
        val source = Problem(1, 0, emptyArray(), arrayOf<Factor>(RealChoice(Lit.make(0, true), 0, 1, 2)),
            numRealVars = 3, realLower = doubleArrayOf(2.0, 2.0, 1.0), realUpper = doubleArrayOf(2.0, 2.0, 1.0))

        val result = BacktrackSolver(source.bake()).solve(
            BacktrackParams(randomSeed = 0, valueSelector = IndomainMin),
        )

        assertTrue(assertIs<SolveResult.Sat>(result).assignment.boolValue(0))
    }

    @Test
    fun `a selected result alias should preserve an unbounded real direction`() {
        val source = Problem(1, 0, emptyArray(), arrayOf<Factor>(
            Clause(intArrayOf(Lit.make(0, true))), RealChoice(Lit.make(0, true), 0, 0, 1)),
            numRealVars = 2, realLower = doubleArrayOf(0.0, 3.0),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY, 3.0))

        val result = BacktrackSolver(source.bake()).minimize(
            LinearObjective(realCoefficients = doubleArrayOf(-1.0, 0.0)), BacktrackParams(randomSeed = 0),
        )

        assertIs<MinimizeResult.Unbounded>(result)
    }

    @Test
    fun `remapping should retain signed guard and distinct real roles`() {
        val source = RealChoice(Lit.make(0, false), 2, 0, 1)
        val mapping = VarRemap(intArrayOf(1), intArrayOf(), intArrayOf(2, 0, 1))

        val result = assertIs<RealChoice>(source.remap(mapping))

        assertEquals(RealChoice(Lit.make(1, false), 1, 2, 0).structuralKey(), result.structuralKey())
        assertContentEquals(intArrayOf(1), result.boolVars)
        assertContentEquals(intArrayOf(), result.intVars)
        assertContentEquals(intArrayOf(1, 2, 0), result.variables.reals)
    }

    @Test
    fun `structural identity should distinguish guard polarity and arm order`() {
        val source = RealChoice(Lit.make(0, true), 0, 1, 2)

        assertNotEquals(source.structuralKey(), RealChoice(Lit.make(0, false), 0, 1, 2).structuralKey())
        assertNotEquals(source.structuralKey(), RealChoice(Lit.make(0, true), 0, 2, 1).structuralKey())
    }
}
