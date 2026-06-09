package com.eignex.klause.smt

import com.eignex.klause.ast.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.factor.ArrayMinMax
import com.eignex.klause.solver.factor.Diffn
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.Product
import com.eignex.klause.solver.factor.PseudoBoolean
import com.eignex.klause.solver.factor.Regular
import com.eignex.klause.solver.factor.ReifiedCardinality
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.factor.ReifiedPseudoBoolean
import com.eignex.klause.solver.factor.Sort
import com.eignex.klause.solver.factor.SymmetricAllDifferent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Correctness coverage for the remaining factor translations in [SmtTranslator]. */
class SmtFactorCoverageExtraTest {

    private fun problem(numInt: Int, doms: Array<IntDomain>, vararg fs: Factor) =
        Problem(numBoolVars = 0, numIntVars = numInt, intDomains = doms, factors = arrayOf(*fs))

    private fun mixedProblem(numBool: Int, numInt: Int, doms: Array<IntDomain>, vararg fs: Factor) =
        Problem(numBoolVars = numBool, numIntVars = numInt, intDomains = doms, factors = arrayOf(*fs))

    private fun sat(p: Problem): Sample {
        val r = SmtSolver(p).solve(SmtParams())
        assertTrue(r is SolveResult.Sat, "expected SAT, got $r")
        return r.assignment
    }

    private fun count(p: Problem): Int = SmtSolver(p).enumerate(SmtParams()).toList().size
    private fun dom(n: Int, lo: Int, hi: Int) = Array(n) { IntDomain(lo, hi) }

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

    @Test fun `product is nonlinear and rejected by the linear default backend`() {
        // Product translates to var × var. SMTInterpol (JavaSMT's pure-Java default) is
        // QF-LIA only, so it rejects the nonlinear term — the translation is still correct
        // for a nonlinear-capable backend (Z3 / CVC5), which is the point of documenting it.
        val p = problem(
            3,
            arrayOf(IntDomain(2, 2), IntDomain(3, 3), IntDomain(0, 20)),
            Product(a = 0, b = 1, result = 2),
        )
        assertFailsWith<UnsupportedOperationException> { SmtSolver(p).solve(SmtParams()) }
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
