package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.Vsids
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Clause vivification (#203). Vivification only ever replaces a learned clause with an implied
 * subclause, so it must not change a verdict or the feasible set — even when it fires every
 * restart. The pass runs at restart boundaries, so these tests enable restarts to exercise it.
 */
class VivificationTest {

    private fun pigeonhole(pigeons: Int, holes: Int): Problem {
        val factors = ArrayList<Factor>()
        fun v(p: Int, h: Int) = p * holes + h
        for (p in 0 until pigeons) factors.add(Clause(IntArray(holes) { h -> Lit.make(v(p, h), true) }))
        for (h in 0 until holes) {
            for (p1 in 0 until pigeons) {
                for (p2 in p1 + 1 until pigeons) {
                    factors.add(Clause(intArrayOf(Lit.make(v(p1, h), false), Lit.make(v(p2, h), false))))
                }
            }
        }
        return Problem(
            numBoolVars = pigeons * holes,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = factors.toTypedArray(),
        )
    }

    @Test
    fun `vivification still proves a hard unsat instance`() {
        val verdict = BacktrackSolver(pigeonhole(pigeons = 5, holes = 4)).solve(
            BacktrackParams(
                randomSeed = 1L,
                variableSelector = Vsids(),
                lubyRestartBase = 50L, // restart often so vivification fires repeatedly
                maxLearnedClauses = 5_000,
                vivification = true,
                vivifyBatch = 64,
            ),
        )
        assertIs<SolveResult.Unsat>(verdict)
    }

    @Test
    fun `vivification finds a valid placement on a satisfiable instance`() {
        val holes = 4
        val pigeons = 4
        val sat = assertIs<SolveResult.Sat>(
            BacktrackSolver(pigeonhole(pigeons, holes)).solve(
                BacktrackParams(
                    randomSeed = 3L,
                    variableSelector = Vsids(),
                    lubyRestartBase = 30L,
                    vivification = true,
                ),
            ),
        )
        val b = sat.assignment.bools
        val holeUsed = BooleanArray(holes)
        for (p in 0 until pigeons) {
            var placed = 0
            for (h in 0 until holes) {
                if (b[p * holes + h]) {
                    placed++
                    assertTrue(!holeUsed[h], "hole $h used twice")
                    holeUsed[h] = true
                }
            }
            assertTrue(placed >= 1, "pigeon $p unplaced")
        }
    }

    private fun clauseProblem(): Problem = Problem(
        numBoolVars = 6,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = arrayOf<Factor>(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(3, true))),
            Clause(intArrayOf(Lit.make(1, false), Lit.make(3, true))),
            Clause(intArrayOf(Lit.make(2, false), Lit.make(4, true))),
            Clause(intArrayOf(Lit.make(3, false), Lit.make(5, true))),
            Clause(intArrayOf(Lit.make(4, false), Lit.make(5, false))),
        ),
    )

    @Test
    fun `vivification preserves the feasible set`() {
        fun models(vivify: Boolean): Set<List<Boolean>> = BacktrackSolver(clauseProblem())
            .enumerate(
                BacktrackParams(
                    randomSeed = 5L,
                    variableSelector = Vsids(),
                    lubyRestartBase = 4L,
                    vivification = vivify,
                    vivifyBatch = 8,
                ),
            )
            .map { it.bools.toList() }
            .toSet()

        val plain = models(vivify = false)
        assertTrue(plain.isNotEmpty())
        assertEquals(plain, models(vivify = true), "vivification must not change the feasible set")
    }
}
