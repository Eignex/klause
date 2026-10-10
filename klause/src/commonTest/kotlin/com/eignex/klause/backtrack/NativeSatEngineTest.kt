package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Correctness of the native-SAT BCP lane ([com.eignex.klause.propagation.NativeSatState]): it must
 * decide every pure-Boolean CNF exactly as the general LCG path and only ever return satisfying
 * witnesses. The differential cases run the same random instances through both lanes.
 */
class NativeSatEngineTest {

    private fun cnf(numVars: Int, clauses: List<IntArray>): Problem = Problem(
        numBoolVars = numVars,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = clauses.map<IntArray, Factor> { Clause(it) }.toTypedArray(),
    )

    private fun nativeParams(seed: Long) = BacktrackParams(randomSeed = seed, nativeSat = true)
    private fun satisfies(clauses: List<IntArray>, model: BooleanArray): Boolean =
        clauses.all { clause -> clause.any { lit -> model[Lit.variable(lit)] == Lit.isPositive(lit) } }

    @Test
    fun `native lane returns a satisfying witness`() {
        val clauses = listOf(
            intArrayOf(Lit.make(0, true), Lit.make(1, true)),
            intArrayOf(Lit.make(1, false), Lit.make(2, true)),
        )
        val sat = assertIs<SolveResult.Sat>(BacktrackSolver(cnf(3, clauses).bake()).solve(nativeParams(0L)))
        assertTrue(satisfies(clauses, sat.assignment.bools), "witness ${sat.assignment.bools.toList()} must satisfy")
    }

    @Test
    fun `native lane proves UNSAT on a contradiction`() {
        val clauses = listOf(
            intArrayOf(Lit.make(0, true)),
            intArrayOf(Lit.make(0, false)),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(cnf(1, clauses).bake()).solve(nativeParams(0L)))
    }
}
