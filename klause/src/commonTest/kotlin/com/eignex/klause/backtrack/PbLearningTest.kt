package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.model.PbOp
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
 * Soundness of pseudo-Boolean cutting-planes learning (#1119 Phase 3): a PB problem must decide exactly
 * the same as the clause-learning path, and every witness must satisfy every constraint. The differential
 * cases run the same instances with `pbLearning` on and off.
 */
class PbLearningTest {

    private fun pbParams(seed: Long) = BacktrackParams(randomSeed = seed, pbLearning = true)
    private fun clauseParams(seed: Long) = BacktrackParams(randomSeed = seed, pbLearning = false)

    private fun pbHolds(pb: PseudoBoolean, model: BooleanArray): Boolean {
        var sum = 0L
        for (i in pb.literals.indices) {
            val lit = pb.literals[i]
            if (model[Lit.variable(lit)] == Lit.isPositive(lit)) sum += pb.weights[i]
        }
        return when (pb.op) {
            PbOp.GE -> sum >= pb.bound
            PbOp.LE -> sum <= pb.bound
            PbOp.EQ -> sum == pb.bound
        }
    }

    private fun clauseHolds(c: Clause, model: BooleanArray): Boolean =
        c.literals.any { model[Lit.variable(it)] == Lit.isPositive(it) }

    private fun satisfies(factors: List<Factor>, model: BooleanArray): Boolean = factors.all {
        when (it) {
            is PseudoBoolean -> pbHolds(it, model)
            is Clause -> clauseHolds(it, model)
            else -> true
        }
    }

    @Test
    fun `pb learning proves the same UNSAT as clause learning`() {
        // 3·x0 + 3·x1 + 3·x2 ≥ 4 forces at least two true; x0+x1 ≤ 1, x0+x2 ≤ 1, x1+x2 ≤ 1 forbid any pair.
        val factors = listOf<Factor>(
            PseudoBoolean(
                longArrayOf(3, 3, 3),
                intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)),
                PbOp.GE,
                4,
            ),
            PseudoBoolean(longArrayOf(1, 1), intArrayOf(Lit.make(0, true), Lit.make(1, true)), PbOp.LE, 1),
            PseudoBoolean(longArrayOf(1, 1), intArrayOf(Lit.make(0, true), Lit.make(2, true)), PbOp.LE, 1),
            PseudoBoolean(longArrayOf(1, 1), intArrayOf(Lit.make(1, true), Lit.make(2, true)), PbOp.LE, 1),
        )
        val problem = Problem(3, 0, emptyArray(), factors.toTypedArray())
        assertIs<SolveResult.Unsat>(BacktrackSolver(problem).solve(pbParams(0L)))
    }

    @Test
    fun `pb learning returns a satisfying witness`() {
        val factors = listOf<Factor>(
            PseudoBoolean(
                longArrayOf(2, 1, 1),
                intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)),
                PbOp.GE,
                2,
            ),
            PseudoBoolean(longArrayOf(1, 1), intArrayOf(Lit.make(1, false), Lit.make(2, false)), PbOp.GE, 1),
        )
        val problem = Problem(3, 0, emptyArray(), factors.toTypedArray())
        val sat = assertIs<SolveResult.Sat>(BacktrackSolver(problem).solve(pbParams(0L)))
        assertTrue(satisfies(factors, sat.assignment.bools), "witness ${sat.assignment.bools.toList()} must satisfy")
    }

    @Test
    fun `pb learning stays sound under aggressive forgetting`() {
        // A tiny learned-clause cap plus restarts forces frequent forgetting/compaction of the watched
        // PB store; a watch-reconcile or compaction bug would surface as a wrong verdict here.
        val rng = Random(2718281L)
        var checked = 0
        repeat(30) { iter ->
            val numVars = 6 + rng.nextInt(4)
            val factors = ArrayList<Factor>()
            repeat(numVars + rng.nextInt(numVars)) {
                val k = 2 + rng.nextInt(3)
                val vars = mutableSetOf<Int>()
                while (vars.size < k) vars.add(rng.nextInt(numVars))
                val lits = vars.map { v -> Lit.make(v, rng.nextBoolean()) }.toIntArray()
                val weights = LongArray(k) { (1 + rng.nextInt(4)).toLong() }
                val total = weights.sum()
                if (rng.nextBoolean()) {
                    factors.add(PseudoBoolean(weights, lits, PbOp.GE, 1 + rng.nextLong(total)))
                } else {
                    factors.add(PseudoBoolean(weights, lits, PbOp.LE, rng.nextLong(total)))
                }
            }
            val problem = { Problem(numVars, 0, emptyArray(), factors.toTypedArray()) }
            val forget = BacktrackParams(
                randomSeed = 5L,
                pbLearning = true,
                maxLearnedClauses = 3,
                lubyRestartBase = 8L,
            )
            val pb = BacktrackSolver(problem()).solve(forget)
            val cl = BacktrackSolver(problem()).solve(forget.copy(pbLearning = false))
            assertEquals(cl is SolveResult.Sat, pb is SolveResult.Sat, "disagree under forgetting on $iter")
            if (pb is SolveResult.Sat) {
                checked++
                assertTrue(satisfies(factors, pb.assignment.bools), "PB witness invalid under forgetting on $iter")
            }
        }
        assertTrue(checked > 0, "expected some SAT instances")
    }

    @Test
    fun `pb and clause learning agree on random PB instances`() {
        val rng = Random(20260716L)
        var sats = 0
        var unsats = 0
        repeat(40) { iter ->
            val numVars = 5 + rng.nextInt(4)
            val factors = ArrayList<Factor>()
            repeat(numVars + rng.nextInt(numVars)) {
                val k = 2 + rng.nextInt(3)
                val vars = mutableSetOf<Int>()
                while (vars.size < k) vars.add(rng.nextInt(numVars))
                val lits = vars.map { v -> Lit.make(v, rng.nextBoolean()) }.toIntArray()
                val weights = LongArray(k) { (1 + rng.nextInt(3)).toLong() }
                val total = weights.sum()
                when (rng.nextInt(3)) {
                    0 -> factors.add(PseudoBoolean(weights, lits, PbOp.GE, 1 + rng.nextLong(total)))
                    1 -> factors.add(PseudoBoolean(weights, lits, PbOp.LE, rng.nextLong(total)))
                    else -> factors.add(Clause(lits))
                }
            }
            val problem = { Problem(numVars, 0, emptyArray(), factors.toTypedArray()) }
            val pb = BacktrackSolver(problem()).solve(pbParams(3L))
            val cl = BacktrackSolver(problem()).solve(clauseParams(3L))
            assertEquals(
                cl is SolveResult.Sat,
                pb is SolveResult.Sat,
                "PB and clause learning disagree on instance $iter (vars=$numVars, ${factors.size} constraints)",
            )
            if (pb is SolveResult.Sat) {
                sats++
                assertTrue(satisfies(factors, pb.assignment.bools), "PB-learning witness invalid on instance $iter")
            } else {
                unsats++
            }
        }
        assertTrue(sats > 0 && unsats > 0, "expected a mix of SAT/UNSAT, got sat=$sats unsat=$unsats")
    }
}
