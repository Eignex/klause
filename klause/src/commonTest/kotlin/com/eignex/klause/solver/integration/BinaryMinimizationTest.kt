package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
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
 * Binary-resolution minimization of learned clauses (#202). The pass is always sound — every
 * literal it drops is justified by one resolution step against the kept asserting literal — so
 * the verdicts and feasible sets must be unchanged. These tests stress it on clause families
 * dense in binary clauses (pigeonhole's "at most one per hole" constraints are all binary), so
 * the minimization fires on real learned clauses.
 */
class BinaryMinimizationTest {

    private fun pigeonhole(pigeons: Int, holes: Int): Problem {
        val factors = ArrayList<Factor>()
        fun v(p: Int, h: Int) = p * holes + h
        for (p in 0 until pigeons) factors.add(Clause(IntArray(holes) { h -> Lit.make(v(p, h), true) }))
        for (h in 0 until holes) {
            for (p1 in 0 until pigeons) {
                for (p2 in p1 + 1 until pigeons) {
                    // Binary "no two pigeons share a hole" clauses — the binary-min fodder.
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
    fun `binary-dense unsat pigeonhole is still proven unsat`() {
        val verdict = BacktrackSolver(pigeonhole(pigeons = 5, holes = 4).bake()).solve(
            BacktrackParams(randomSeed = 1L, variableSelector = Vsids(), maxLearnedClauses = 5_000),
        )
        assertIs<SolveResult.Unsat>(verdict)
    }

    @Test
    fun `binary-dense satisfiable pigeonhole finds a valid placement`() {
        val holes = 4
        val pigeons = 4
        val sat = assertIs<SolveResult.Sat>(
            BacktrackSolver(pigeonhole(pigeons, holes).bake()).solve(
                BacktrackParams(randomSeed = 2L, variableSelector = Vsids(), maxLearnedClauses = 5_000),
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

    @Test
    fun `binary-resolution minimization preserves the feasible set`() {
        // A mix of binary implications and a wider clause, so learned clauses are shrinkable.
        val problem = Problem(
            numBoolVars = 6,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(3, true))), // x0 ⟹ x3
                Clause(intArrayOf(Lit.make(1, false), Lit.make(3, true))), // x1 ⟹ x3
                Clause(intArrayOf(Lit.make(2, false), Lit.make(4, true))), // x2 ⟹ x4
                Clause(intArrayOf(Lit.make(3, false), Lit.make(5, true))), // x3 ⟹ x5
                Clause(intArrayOf(Lit.make(4, false), Lit.make(5, false))), // ¬(x4 ∧ x5)
            ),
        )
        val models = BacktrackSolver(problem.bake())
            .enumerate(BacktrackParams(randomSeed = 4L, variableSelector = Vsids(), lubyRestartBase = 4L))
            .map { it.bools.toList() }
            .toSet()
        // Independently brute-force the feasible set and compare.
        val expected = HashSet<List<Boolean>>()
        for (mask in 0 until (1 shl 6)) {
            val x = BooleanArray(6) { (mask shr it) and 1 == 1 }
            val ok = (x[0] || x[1] || x[2]) &&
                (!x[0] || x[3]) && (!x[1] || x[3]) && (!x[2] || x[4]) &&
                (!x[3] || x[5]) && (!x[4] || !x[5])
            if (ok) expected.add(x.toList())
        }
        assertTrue(expected.isNotEmpty())
        assertEquals(expected, models, "binary-resolution minimization must not change the feasible set")
    }
}
