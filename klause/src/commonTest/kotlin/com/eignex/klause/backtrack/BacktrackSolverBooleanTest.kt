package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BacktrackSolverBooleanTest {
    private fun cnf(numVars: Int, clauses: List<IntArray>): Problem = Problem(
        numBoolVars = numVars,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = clauses.map<IntArray, Factor> { Clause(it) }.toTypedArray(),
    )

    private fun nativeParams(seed: Long) = BacktrackParams(randomSeed = seed, nativeSat = true)

    private fun satisfiesClauses(clauses: List<IntArray>, model: BooleanArray): Boolean =
        clauses.all { clause -> clause.any { lit -> model[Lit.variable(lit)] == Lit.isPositive(lit) } }

    @Test
    fun `native lane should return a satisfying witness`() {
        val clauses = listOf(
            intArrayOf(Lit.make(0, true), Lit.make(1, true)),
            intArrayOf(Lit.make(1, false), Lit.make(2, true)),
        )
        val sat = assertIs<SolveResult.Sat>(BacktrackSolver(cnf(3, clauses).bake()).solve(nativeParams(0L)))
        assertTrue(
            satisfiesClauses(clauses, sat.assignment.bools),
            "witness ${sat.assignment.bools.toList()} must satisfy",
        )
    }

    @Test
    fun `native lane should prove UNSAT on a contradiction`() {
        val clauses = listOf(
            intArrayOf(Lit.make(0, true)),
            intArrayOf(Lit.make(0, false)),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(cnf(1, clauses).bake()).solve(nativeParams(0L)))
    }

    private fun atLeast(vars: Int, min: Int): Factor =
        Cardinality(IntArray(vars) { Lit.make(it, true) }, min = min, max = vars)

    @Test
    fun `pure-Boolean minimize should prove the optimum with positive weights`() {
        val p = Problem(4, 0, emptyArray(), arrayOf(atLeast(4, min = 2)))
        val obj = LinearObjective(boolWeights = longArrayOf(3, 1, 1, 1))
        val r = BacktrackSolver(p.bake()).minimize(obj, BacktrackParams(randomSeed = 1L, pbObjectiveCutoff = true))
        val opt = assertIs<MinimizeResult.Optimal>(r)
        assertEquals(2.0, opt.objective)
    }

    @Test
    fun `pure-Boolean minimize should prove the optimum with negative weights`() {
        val p = Problem(
            3,
            0,
            emptyArray(),
            arrayOf(Cardinality(IntArray(3) { Lit.make(it, true) }, min = 0, max = 1)),
        )
        val obj = LinearObjective(boolWeights = longArrayOf(-1, -1, -1))
        val r = BacktrackSolver(p.bake()).minimize(obj, BacktrackParams(randomSeed = 1L, pbObjectiveCutoff = true))
        val opt = assertIs<MinimizeResult.Optimal>(r)
        assertEquals(-1.0, opt.objective)
    }

    private fun pbParams(seed: Long) = BacktrackParams(randomSeed = seed, pbLearning = true)

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
    fun `pb learning should prove the same UNSAT as clause learning`() {
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
        assertIs<SolveResult.Unsat>(BacktrackSolver(problem.bake()).solve(pbParams(0L)))
    }

    @Test
    fun `pb learning should return a satisfying witness`() {
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
        val sat = assertIs<SolveResult.Sat>(BacktrackSolver(problem.bake()).solve(pbParams(0L)))
        assertTrue(satisfies(factors, sat.assignment.bools), "witness ${sat.assignment.bools.toList()} must satisfy")
    }

    @Test
    fun `pb learning should stay sound under aggressive forgetting`() {
        val rng = Random(2718281L)
        var checked = 0
        repeat(10) { iter ->
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
            val pb = BacktrackSolver(problem().bake()).solve(forget)
            val cl = BacktrackSolver(problem().bake()).solve(forget.copy(pbLearning = false))
            assertEquals(cl is SolveResult.Sat, pb is SolveResult.Sat, "disagree under forgetting on $iter")
            if (pb is SolveResult.Sat) {
                checked++
                assertTrue(satisfies(factors, pb.assignment.bools), "PB witness invalid under forgetting on $iter")
            }
        }
        assertTrue(checked > 0)
    }
}
