package com.eignex.klause.choco

import com.eignex.klause.ast.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.factor.AllDifferentExcept
import com.eignex.klause.solver.factor.ArgMinMax
import com.eignex.klause.solver.factor.ArrayMinMax
import com.eignex.klause.solver.factor.BinPacking
import com.eignex.klause.solver.factor.Diffn
import com.eignex.klause.solver.factor.Knapsack
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.Product
import com.eignex.klause.solver.factor.PseudoBoolean
import com.eignex.klause.solver.factor.Regular
import com.eignex.klause.solver.factor.ReifiedCardinality
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.factor.ReifiedPseudoBoolean
import com.eignex.klause.solver.factor.Sequence
import com.eignex.klause.solver.factor.Sort
import com.eignex.klause.solver.factor.SymmetricAllDifferent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Correctness coverage for the remaining factor translations in [ChocoModel]. */
class ChocoFactorCoverageExtraTest {

    private fun problem(numInt: Int, doms: Array<IntDomain>, vararg fs: Factor) =
        Problem(numBoolVars = 0, numIntVars = numInt, intDomains = doms, factors = arrayOf(*fs))

    private fun mixedProblem(numBool: Int, numInt: Int, doms: Array<IntDomain>, vararg fs: Factor) =
        Problem(numBoolVars = numBool, numIntVars = numInt, intDomains = doms, factors = arrayOf(*fs))

    private fun sat(p: Problem): Sample {
        val r = ChocoSolver(p).solve(ChocoParams())
        assertTrue(r is SolveResult.Sat, "expected SAT, got $r")
        return r.assignment
    }

    private fun count(p: Problem): Int = ChocoSolver(p).enumerate(ChocoParams()).toList().size
    private fun dom(n: Int, lo: Int, hi: Int) = Array(n) { IntDomain(lo, hi) }

    @Test fun `alldifferent_except keeps non-excepted distinct`() {
        val a = sat(problem(3, dom(3, 0, 2), AllDifferentExcept(intArrayOf(0, 1, 2), intArrayOf(1))))
        val kept = listOf(a.ints[0], a.ints[1], a.ints[2]).filter { it != 1 }
        assertEquals(kept.size, kept.toSet().size, "non-excepted values must be distinct: $kept")
    }

    @Test fun `array max is the maximum`() {
        val a = sat(
            problem(
                3,
                arrayOf(IntDomain(0, 5), IntDomain(0, 5), IntDomain(0, 5)),
                ArrayMinMax(result = 2, xs = intArrayOf(0, 1), max = true),
            ),
        )
        assertEquals(maxOf(a.ints[0], a.ints[1]), a.ints[2])
    }

    @Test fun `argmax points at the first maximal position`() {
        val a = sat(
            problem(
                3,
                arrayOf(IntDomain(0, 5), IntDomain(0, 5), IntDomain(0, 1)),
                ArgMinMax(idx = 2, xs = intArrayOf(0, 1), max = true, indexOffset = 0),
            ),
        )
        val xs = listOf(a.ints[0], a.ints[1])
        val expected = xs.indexOf(xs.max())
        assertEquals(expected, a.ints[2])
    }

    @Test fun `knapsack ties weight and profit sums`() {
        val a = sat(
            problem(
                4,
                arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 100), IntDomain(0, 100)),
                Knapsack(weights = intArrayOf(2, 3), profits = intArrayOf(5, 1), xs = intArrayOf(0, 1), w = 2, p = 3),
            ),
        )
        assertEquals(2 * a.ints[0] + 3 * a.ints[1], a.ints[2])
        assertEquals(5 * a.ints[0] + 1 * a.ints[1], a.ints[3])
    }

    @Test fun `diffn separates two rectangles`() {
        val a = sat(
            problem(
                4,
                dom(4, 0, 3),
                Diffn(
                    xs = intArrayOf(0, 1),
                    ys = intArrayOf(2, 3),
                    widths = intArrayOf(2, 2),
                    heights = intArrayOf(2, 2),
                ),
            ),
        )
        val x0 = a.ints[0]
        val x1 = a.ints[1]
        val y0 = a.ints[2]
        val y1 = a.ints[3]
        val sepX = x0 + 2 <= x1 || x1 + 2 <= x0
        val sepY = y0 + 2 <= y1 || y1 + 2 <= y0
        assertTrue(sepX || sepY, "rectangles overlap")
    }

    @Test fun `sort yields a sorted permutation`() {
        val a = sat(problem(6, dom(6, 0, 2), Sort(xs = intArrayOf(0, 1, 2), ys = intArrayOf(3, 4, 5))))
        val xs = listOf(a.ints[0], a.ints[1], a.ints[2])
        val ys = listOf(a.ints[3], a.ints[4], a.ints[5])
        assertEquals(ys, ys.sorted())
        assertEquals(xs.sorted(), ys)
    }

    @Test fun `sequence keeps each window in range`() {
        val a = sat(
            problem(
                4,
                dom(4, 0, 1),
                Sequence(low = 1, high = 2, k = 2, xs = intArrayOf(0, 1, 2, 3), values = intArrayOf(1)),
            ),
        )
        val v = listOf(a.ints[0], a.ints[1], a.ints[2], a.ints[3])
        for (s in 0..v.size - 2) assertTrue(v.subList(s, s + 2).count { it == 1 } in 1..2)
    }

    @Test fun `symmetric_all_different enumerates involutions`() =
        assertEquals(4, count(problem(3, dom(3, 0, 2), SymmetricAllDifferent(intArrayOf(0, 1, 2)))))

    @Test fun `regular accepts its language`() =
        // DFA: from q0=1 only symbol 1 advances to accepting state 2, which then self-loops.
        assertEquals(
            2,
            count(
                problem(
                    2,
                    dom(2, 1, 2),
                    Regular(
                        seq = intArrayOf(0, 1),
                        numStates = 2,
                        alphabetSize = 2,
                        transitions = intArrayOf(2, 0, 2, 2),
                        q0 = 1,
                        accepting = intArrayOf(2),
                    ),
                ),
            ),
        )

    @Test fun `bin_packing fills load variables`() {
        val a = sat(
            problem(
                4,
                arrayOf(IntDomain(1, 2), IntDomain(1, 2), IntDomain(0, 100), IntDomain(0, 100)),
                BinPacking(
                    bins = intArrayOf(0, 1),
                    weights = intArrayOf(3, 4),
                    mode = BinPacking.Mode.LoadVars,
                    loadVars = intArrayOf(2, 3),
                    numBins = 2,
                    binOffset = 1,
                ),
            ),
        )
        val w = intArrayOf(3, 4)
        val load1 = (0..1).filter { a.ints[it] == 1 }.sumOf { w[it] }
        val load2 = (0..1).filter { a.ints[it] == 2 }.sumOf { w[it] }
        assertEquals(load1, a.ints[2])
        assertEquals(load2, a.ints[3])
    }

    @Test fun `reified linear matches its predicate`() {
        val a = sat(
            mixedProblem(
                1,
                1,
                arrayOf(IntDomain(0, 5)),
                ReifiedLinear(
                    auxBoolVar = 0,
                    coeffs = intArrayOf(1),
                    vars = intArrayOf(0),
                    op = LinearOp.GE,
                    bound = 3,
                ),
            ),
        )
        assertEquals(a.ints[0] >= 3, a.bools[0])
    }

    @Test fun `reified pseudo-boolean matches its predicate`() {
        val a = sat(
            mixedProblem(
                3,
                0,
                emptyArray(),
                ReifiedPseudoBoolean(
                    auxBoolVar = 2,
                    weights = intArrayOf(1, 1),
                    literals = intArrayOf(Lit.make(0, true), Lit.make(1, true)),
                    op = PbOp.GE,
                    bound = 1,
                ),
            ),
        )
        val sum = (if (a.bools[0]) 1 else 0) + (if (a.bools[1]) 1 else 0)
        assertEquals(sum >= 1, a.bools[2])
    }

    @Test fun `reified cardinality matches its predicate`() {
        val a = sat(
            mixedProblem(
                3,
                0,
                emptyArray(),
                ReifiedCardinality(
                    auxBoolVar = 2,
                    literals = intArrayOf(Lit.make(0, true), Lit.make(1, true)),
                    min = 1,
                    max = 2,
                ),
            ),
        )
        val n = (if (a.bools[0]) 1 else 0) + (if (a.bools[1]) 1 else 0)
        assertEquals(n in 1..2, a.bools[2])
    }

    @Test fun `product multiplies`() {
        val a = sat(
            problem(
                3,
                arrayOf(IntDomain(2, 2), IntDomain(3, 3), IntDomain(0, 20)),
                Product(a = 0, b = 1, result = 2),
            ),
        )
        assertEquals(6, a.ints[2])
    }

    @Test fun `pseudo-boolean enforces the weighted bound`() {
        val a = sat(
            mixedProblem(
                2,
                0,
                emptyArray(),
                PseudoBoolean(
                    weights = intArrayOf(1, 1),
                    literals = intArrayOf(Lit.make(0, true), Lit.make(1, true)),
                    op = PbOp.GE,
                    bound = 1,
                ),
            ),
        )
        assertTrue((if (a.bools[0]) 1 else 0) + (if (a.bools[1]) 1 else 0) >= 1)
    }
}
