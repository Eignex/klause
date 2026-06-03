package com.eignex.klause.choco

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.factor.AllEqual
import com.eignex.klause.solver.factor.Among
import com.eignex.klause.solver.factor.Circuit
import com.eignex.klause.solver.factor.Cumulative
import com.eignex.klause.solver.factor.Element
import com.eignex.klause.solver.factor.Geost
import com.eignex.klause.solver.factor.GlobalCardinality
import com.eignex.klause.solver.factor.Inverse
import com.eignex.klause.solver.factor.LexLess
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.Mdd
import com.eignex.klause.solver.factor.Monotone
import com.eignex.klause.solver.factor.NValue
import com.eignex.klause.solver.factor.SetBitsetDisjoint
import com.eignex.klause.solver.factor.SetBitsetEq
import com.eignex.klause.solver.factor.SetBitsetSubset
import com.eignex.klause.solver.factor.Subcircuit
import com.eignex.klause.solver.factor.Table
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Correctness coverage for the global-constraint factor translations in [ChocoModel]. */
class ChocoFactorCoverageTest {

    private fun problem(numInt: Int, doms: Array<IntDomain>, vararg fs: Factor) =
        Problem(numBoolVars = 0, numIntVars = numInt, intDomains = doms, factors = arrayOf(*fs))

    private fun sat(p: Problem): Sample {
        val r = ChocoSolver(p).solve(ChocoParams())
        assertTrue(r is SolveResult.Sat, "expected SAT, got $r")
        return r.assignment
    }

    private fun count(p: Problem): Int = ChocoSolver(p).enumerate(ChocoParams()).toList().size

    private fun dom(n: Int, lo: Int, hi: Int) = Array(n) { IntDomain(lo, hi) }

    @Test fun `all_equal enumerates the diagonal`() =
        assertEquals(3, count(problem(3, dom(3, 0, 2), AllEqual(intArrayOf(0, 1, 2)))))

    @Test fun `strictly increasing has one solution`() = assertEquals(
        1,
        count(
            problem(
                3,
                dom(3, 0, 2),
                Monotone(intArrayOf(0, 1, 2), Monotone.Direction.Increasing, strict = true),
            ),
        ),
    )

    @Test fun `inverse channels two permutations`() =
        assertEquals(2, count(problem(4, dom(4, 0, 1), Inverse(intArrayOf(0, 1), intArrayOf(2, 3)))))

    @Test fun `circuit has two hamiltonian cycles on three nodes`() =
        assertEquals(2, count(problem(3, dom(3, 0, 2), Circuit(intArrayOf(0, 1, 2)))))

    @Test fun `table allows exactly its tuples`() = assertEquals(
        2,
        count(
            problem(
                2,
                dom(2, 0, 2),
                Table(intArrayOf(0, 1), intArrayOf(0, 1, 2, 2)),
            ),
        ),
    )

    @Test fun `strict lex-less counts ordered pairs`() = assertEquals(
        6,
        count(
            problem(
                4,
                dom(4, 0, 1),
                LexLess(intArrayOf(0, 1), intArrayOf(2, 3), strict = true),
            ),
        ),
    )

    @Test fun `element reads the indexed constant`() {
        val a = sat(
            problem(
                2,
                arrayOf(IntDomain(0, 2), IntDomain(0, 20)),
                Element(idx = 0, result = 1, arr = intArrayOf(5, 7, 9), arrIsVars = false, indexOffset = 0),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 2),
            ),
        )
        assertEquals(2, a.ints[0])
        assertEquals(9, a.ints[1])
    }

    @Test fun `among counts membership`() {
        val a = sat(
            problem(
                3,
                arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 2)),
                Among(n = 2, xs = intArrayOf(0, 1), values = intArrayOf(1, 2)),
            ),
        )
        val expected = listOf(a.ints[0], a.ints[1]).count { it == 1 || it == 2 }
        assertEquals(expected, a.ints[2])
    }

    @Test fun `nvalue equals the distinct count`() {
        val a = sat(
            problem(
                4,
                arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 3)),
                NValue(n = 3, xs = intArrayOf(0, 1, 2)),
            ),
        )
        val distinct = setOf(a.ints[0], a.ints[1], a.ints[2]).size
        assertEquals(distinct, a.ints[3])
    }

    @Test fun `global cardinality keeps counts in range`() {
        val a = sat(
            problem(
                3,
                dom(3, 0, 1),
                GlobalCardinality(
                    xs = intArrayOf(0, 1, 2),
                    cover = intArrayOf(0, 1),
                    countLow = intArrayOf(1, 1),
                    countHigh = intArrayOf(2, 2),
                ),
            ),
        )
        val zeros = listOf(a.ints[0], a.ints[1], a.ints[2]).count { it == 0 }
        val ones = 3 - zeros
        assertTrue(zeros in 1..2 && ones in 1..2, "counts out of range: zeros=$zeros ones=$ones")
    }

    @Test fun `cumulative is satisfiable with a wide horizon`() {
        // durations/resources are constants (not var ids). Two unit-demand tasks of duration 3
        // under capacity 1 must not overlap; starts in [0,5] leave room (e.g. 0 and 3) → SAT.
        sat(
            problem(
                2,
                arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
                Cumulative(
                    starts = intArrayOf(0, 1),
                    durations = intArrayOf(3, 3),
                    resources = intArrayOf(1, 1),
                    capacity = 1,
                ),
            ),
        )
    }

    @Test fun `cumulative is unsat when tasks cannot fit`() {
        // Same tasks, but starts pinned to {0,1} force the two duration-3 tasks to overlap, so
        // their combined demand 2 exceeds capacity 1 → UNSAT.
        val p = problem(
            2,
            arrayOf(IntDomain(0, 1), IntDomain(0, 1)),
            Cumulative(
                starts = intArrayOf(0, 1),
                durations = intArrayOf(3, 3),
                resources = intArrayOf(1, 1),
                capacity = 1,
            ),
        )
        assertTrue(ChocoSolver(p).solve(ChocoParams()) is SolveResult.Unsat)
    }

    @Test fun `cumulative constant durations and resources are not indexed as int vars`() {
        // Regression for #119: durations/resources/capacity are constants. A constant value
        // larger than the int-var count (here duration 7, demand 3 with a single start var) must
        // be posted as a fixed Choco var, not used to index the int-var array.
        sat(
            problem(
                1,
                arrayOf(IntDomain(0, 10)),
                Cumulative(
                    starts = intArrayOf(0),
                    durations = intArrayOf(7),
                    resources = intArrayOf(3),
                    capacity = 5,
                ),
            ),
        )
    }

    @Test fun `linear whose reachable sum exceeds Choco's range is reported unsupported, not crashed`() {
        // Regression for #120: two vars near half the int range sum past Integer.MAX_VALUE, so the
        // scalar's intermediate var would trip Choco's domain-range guard. The adapter must raise
        // its explicit unsupported signal rather than letting a raw SolverException escape.
        val big = 1_500_000_000
        val p = problem(
            2,
            arrayOf(IntDomain(0, big), IntDomain(0, big)),
            Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.LE, bound = big),
        )
        assertFailsWith<UnsupportedFactorException> { ChocoSolver(p).solve(ChocoParams()) }
    }

    private fun boolProblem(numBool: Int, vararg fs: Factor) =
        Problem(numBoolVars = numBool, numIntVars = 0, intDomains = emptyArray(), factors = arrayOf(*fs))

    @Test fun `set subset allows three configs per position`() = assertEquals(
        9,
        ChocoSolver(boolProblem(4, SetBitsetSubset(intArrayOf(0, 1), intArrayOf(2, 3))))
            .enumerate(ChocoParams()).toList().size,
    )

    @Test fun `set disjoint allows three configs per position`() = assertEquals(
        9,
        ChocoSolver(boolProblem(4, SetBitsetDisjoint(intArrayOf(0, 1), intArrayOf(2, 3))))
            .enumerate(ChocoParams()).toList().size,
    )

    @Test fun `set equal couples the indicators`() = assertEquals(
        4,
        ChocoSolver(boolProblem(4, SetBitsetEq(intArrayOf(0, 1), intArrayOf(2, 3))))
            .enumerate(ChocoParams()).toList().size,
    )

    @Test fun `subcircuit on three nodes has six valid configurations`() =
        assertEquals(6, count(problem(3, dom(3, 0, 2), Subcircuit(intArrayOf(0, 1, 2)))))

    @Test fun `mdd accepts the language`() = // Accepts seq[0] = 1 with seq[1] free → 2 words.
        assertEquals(
            2,
            count(
                problem(
                    2,
                    dom(2, 0, 1),
                    Mdd(
                        seq = intArrayOf(0, 1),
                        numStatesPerLayer = intArrayOf(1, 2, 3),
                        layerStarts = intArrayOf(0, 3, 9),
                        transitions = intArrayOf(0, 1, 1, 1, 0, 2, 1, 1, 2),
                        initial = 0,
                        accepting = intArrayOf(2),
                        recordStride = 3,
                    ),
                ),
            ),
        )

    @Test fun `geost keeps boxes apart`() {
        val a = sat(
            problem(
                2,
                dom(2, 0, 2),
                Geost(numDims = 1, numObjects = 2, origin = intArrayOf(0, 1), length = intArrayOf(2, 2)),
            ),
        )
        assertTrue(a.ints[0] + 2 <= a.ints[1] || a.ints[1] + 2 <= a.ints[0])
    }
}
