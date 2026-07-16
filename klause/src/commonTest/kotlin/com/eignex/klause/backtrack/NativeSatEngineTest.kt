package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
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
    private fun generalParams(seed: Long) = BacktrackParams(randomSeed = seed, nativeSat = false)

    private fun satisfies(clauses: List<IntArray>, model: BooleanArray): Boolean =
        clauses.all { clause -> clause.any { lit -> model[Lit.variable(lit)] == Lit.isPositive(lit) } }

    @Test
    fun `native lane returns a satisfying witness`() {
        val clauses = listOf(
            intArrayOf(Lit.make(0, true), Lit.make(1, true)),
            intArrayOf(Lit.make(1, false), Lit.make(2, true)),
        )
        val sat = assertIs<SolveResult.Sat>(BacktrackSolver(cnf(3, clauses)).solve(nativeParams(0L)))
        assertTrue(satisfies(clauses, sat.assignment.bools), "witness ${sat.assignment.bools.toList()} must satisfy")
    }

    @Test
    fun `default params auto-dispatch an eligible problem and solve it correctly`() {
        // nativeSat defaults to null (auto): an eligible pure-CNF problem must solve correctly with no
        // explicit lane selection — the front-end dispatch that routes DIMACS / SAT-pure XCSP3 in.
        val clauses = listOf(
            intArrayOf(Lit.make(0, true), Lit.make(1, false)),
            intArrayOf(Lit.make(0, false), Lit.make(2, true)),
        )
        val sat = assertIs<SolveResult.Sat>(BacktrackSolver(cnf(3, clauses)).solve(BacktrackParams(randomSeed = 0L)))
        assertTrue(satisfies(clauses, sat.assignment.bools), "auto-dispatched witness must satisfy")
    }

    @Test
    fun `native lane proves UNSAT on a contradiction`() {
        val clauses = listOf(
            intArrayOf(Lit.make(0, true)),
            intArrayOf(Lit.make(0, false)),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(cnf(1, clauses)).solve(nativeParams(0L)))
    }

    @Test
    fun `native lane cascades unit propagation to UNSAT`() {
        // a ; a→b ; b→c ; ¬c  forces a,b,c true then contradicts ¬c.
        val clauses = listOf(
            intArrayOf(Lit.make(0, true)),
            intArrayOf(Lit.make(0, false), Lit.make(1, true)),
            intArrayOf(Lit.make(1, false), Lit.make(2, true)),
            intArrayOf(Lit.make(2, false)),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(cnf(3, clauses)).solve(nativeParams(0L)))
    }

    @Test
    fun `native and general lanes agree on random 3-CNF`() {
        val rng = Random(20260716L)
        var sats = 0
        var unsats = 0
        repeat(120) {
            val numVars = 6 + rng.nextInt(6)
            val numClauses = (numVars * (3 + rng.nextInt(3)))
            val clauses = List(numClauses) {
                val vars = mutableSetOf<Int>()
                while (vars.size < 3) vars.add(rng.nextInt(numVars))
                IntArray(3).also { arr ->
                    vars.forEachIndexed { i, v -> arr[i] = Lit.make(v, rng.nextBoolean()) }
                }
            }
            val problem = cnf(numVars, clauses)
            val native = BacktrackSolver(problem).solve(nativeParams(1L))
            val general = BacktrackSolver(cnf(numVars, clauses)).solve(generalParams(1L))
            assertEquals(
                general is SolveResult.Sat,
                native is SolveResult.Sat,
                "lanes disagree on instance $it (vars=$numVars clauses=$numClauses)",
            )
            if (native is SolveResult.Sat) {
                sats++
                assertTrue(satisfies(clauses, native.assignment.bools), "native witness invalid on instance $it")
            } else {
                unsats++
            }
        }
        // The generator straddles the phase transition, so both outcomes must actually occur —
        // otherwise the differential check is vacuous.
        assertTrue(sats > 0 && unsats > 0, "expected a mix of SAT/UNSAT, got sat=$sats unsat=$unsats")
    }

    @Test
    fun `native and general lanes agree under aggressive clause forgetting`() {
        // A tiny learned-clause cap plus restarts forces frequent native forgetting/compaction; a bug in
        // watch remapping or arena compaction would surface as a wrong verdict or an invalid witness.
        val rng = Random(31415926L)
        var forgot = false
        repeat(60) {
            val numVars = 8 + rng.nextInt(5)
            val numClauses = numVars * 4
            val clauses = List(numClauses) {
                val vars = mutableSetOf<Int>()
                while (vars.size < 3) vars.add(rng.nextInt(numVars))
                IntArray(3).also { arr -> vars.forEachIndexed { i, v -> arr[i] = Lit.make(v, rng.nextBoolean()) } }
            }
            val forget = BacktrackParams(
                randomSeed = 2L,
                nativeSat = true,
                maxLearnedClauses = 4,
                tieredLearnedDb = true,
                lubyRestartBase = 8L,
            )
            val native = BacktrackSolver(cnf(numVars, clauses)).solve(forget)
            val general = BacktrackSolver(cnf(numVars, clauses)).solve(forget.copy(nativeSat = false))
            assertEquals(
                general is SolveResult.Sat,
                native is SolveResult.Sat,
                "lanes disagree under forgetting on $it",
            )
            if (native is SolveResult.Sat) {
                forgot = true
                assertTrue(
                    satisfies(clauses, native.assignment.bools),
                    "native witness invalid under forgetting on $it",
                )
            }
        }
        assertTrue(forgot, "expected at least one SAT instance to exercise the witness check")
    }

    @Test
    fun `native and general lanes enumerate the same model count`() {
        // Small enough to enumerate fully; exercises the learned-clause store across many backjumps.
        val clauses = listOf(
            intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)),
            intArrayOf(Lit.make(0, false), Lit.make(1, false)),
            intArrayOf(Lit.make(1, true), Lit.make(3, false)),
        )
        val nativeCount = BacktrackSolver(cnf(4, clauses)).enumerate(nativeParams(7L)).count()
        val generalCount = BacktrackSolver(cnf(4, clauses)).enumerate(generalParams(7L)).count()
        assertEquals(generalCount, nativeCount, "native and general must enumerate the same number of models")
    }
}
