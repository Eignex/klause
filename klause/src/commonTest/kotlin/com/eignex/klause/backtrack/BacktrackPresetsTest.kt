package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The SAT-optimized preset bundles the full modern-CDCL stack (adaptive restarts, target
 * phasing, three-tier learned DB, binary-resolution minimization, vivification). Each
 * component is sound, so the preset must produce correct verdicts; these tests pin that on a
 * conflict-heavy UNSAT family and a satisfiable instance.
 */
class BacktrackPresetsTest {

    /** Pigeonhole P(n+1, n) — UNSAT, conflict-heavy. */
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
    fun `sat-optimized preset proves a conflict-heavy unsat instance`() {
        val verdict = BacktrackSolver(pigeonhole(pigeons = 5, holes = 4).bake())
            .solve(BacktrackPresets.satOptimized(randomSeed = 1L))
        assertIs<SolveResult.Unsat>(verdict)
    }

    @Test
    fun `sat-optimized preset finds a valid witness on a satisfiable problem`() {
        val problem = satisfiableChain()
        val sat = assertIs<SolveResult.Sat>(
            BacktrackSolver(problem.bake()).solve(BacktrackPresets.satOptimized(randomSeed = 2L)),
        )
        assertChainWitness(sat)
    }

    @Test
    fun `sat-optimized preset should schedule inprocessing at an amortizing cadence`() {
        val p = BacktrackPresets.satOptimized()
        assertTrue(p.vivification)
        assertTrue(p.subsumption)
        assertEquals(4, p.inprocessingCadence)
        val optedOut = BacktrackPresets.satOptimized(inprocess = false)
        assertFalse(optedOut.vivification)
        assertFalse(optedOut.subsumption)
    }

    @Test
    fun `conflict-driven preset proves a conflict-heavy unsat instance`() {
        val verdict = BacktrackSolver(pigeonhole(pigeons = 5, holes = 4).bake())
            .solve(BacktrackPresets.conflictDriven(randomSeed = 1L))
        assertIs<SolveResult.Unsat>(verdict)
    }

    @Test
    fun `conflict-driven preset finds a valid witness on a satisfiable problem`() {
        val sat = assertIs<SolveResult.Sat>(
            BacktrackSolver(satisfiableChain().bake()).solve(BacktrackPresets.conflictDriven(randomSeed = 2L)),
        )
        assertChainWitness(sat)
    }

    private fun satisfiableChain(): Problem = Problem(
        numBoolVars = 4,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = arrayOf<Factor>(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(1, false), Lit.make(2, true))),
            Clause(intArrayOf(Lit.make(2, false), Lit.make(3, true))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(3, false))),
        ),
    )

    private fun assertChainWitness(sat: SolveResult.Sat) {
        val b = sat.assignment.bools
        assertTrue(b[0] || b[1])
        assertTrue(!b[1] || b[2])
        assertTrue(!b[2] || b[3])
        assertTrue(!b[0] || !b[3])
    }
}
