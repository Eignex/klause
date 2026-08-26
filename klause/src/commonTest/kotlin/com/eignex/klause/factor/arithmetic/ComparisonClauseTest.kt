package com.eignex.klause.factor.arithmetic

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.Vsids
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ComparisonClauseTest {

    private fun le(v: Int, c: Long) = Triple(v, LinearOp.LE, c)
    private fun ge(v: Int, c: Long) = Triple(v, LinearOp.GE, c)
    private fun eq(v: Int, c: Long) = Triple(v, LinearOp.EQ, c)
    private fun ne(v: Int, c: Long) = Triple(v, LinearOp.NE, c)

    private fun clauseOf(lits: List<Triple<Int, LinearOp, Long>>) = ComparisonClause(
        vars = IntArray(lits.size) { lits[it].first },
        ops = Array(lits.size) { lits[it].second },
        consts = LongArray(lits.size) { lits[it].third },
    )

    private fun litHolds(x: Long, op: LinearOp, c: Long) = when (op) {
        LinearOp.LE -> x <= c
        LinearOp.GE -> x >= c
        LinearOp.EQ -> x == c
        LinearOp.NE -> x != c
    }

    private fun enumerate(problem: Problem, seed: Long): HashSet<List<Long>> = BacktrackSolver(problem.bake())
        .enumerate(BacktrackParams(randomSeed = seed, variableSelector = Vsids()))
        .take(100_000)
        .map { it.ints.toList() }
        .toHashSet()

    /** Enumerate every solution of a single [ComparisonClause] over [domains] and assert it equals the
     *  brute-force allowed set (a tuple is allowed iff at least one literal holds). */
    private fun checkAgainstBrute(name: String, domains: Array<IntDomain>, lits: List<Triple<Int, LinearOp, Long>>) {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = domains.size,
            intDomains = domains,
            factors = arrayOf<Factor>(clauseOf(lits)),
        )
        val brute = HashSet<List<Long>>()
        fun recurse(v: Int, acc: LongArray) {
            if (v == domains.size) {
                if (lits.any { litHolds(acc[it.first], it.second, it.third) }) brute.add(acc.toList())
                return
            }
            val d = domains[v]
            var x = d.min
            while (x <= d.max) {
                if (x in d) {
                    acc[v] = x
                    recurse(v + 1, acc)
                }
                x++
            }
        }
        recurse(0, LongArray(domains.size))
        for (seed in 1L..4L) {
            assertEquals(
                brute,
                enumerate(problem, seed),
                "$name (seed=$seed): $lits over ${domains.map { "${it.min}..${it.max}" }}",
            )
        }
    }

    @Test
    fun `enumeration matches brute force across clause shapes`() {
        val cases = listOf(
            Triple("two LE", arrayOf(IntDomain(0, 3), IntDomain(0, 3)), listOf(le(0, 1), le(1, 1))),
            // imp(x > 1, y < 2) == (x <= 1) v (y <= 1)
            Triple("GT-then-LT implication", arrayOf(IntDomain(0, 4), IntDomain(0, 4)), listOf(le(0, 1), le(1, 1))),
            Triple("GE or LE", arrayOf(IntDomain(0, 3), IntDomain(0, 3)), listOf(ge(0, 3), le(1, 0))),
            Triple("EQ or EQ", arrayOf(IntDomain(0, 3), IntDomain(0, 3)), listOf(eq(0, 1), eq(1, 2))),
            // imp(x == 1, y == 2) == (x != 1) v (y == 2)
            Triple("EQ-then-EQ implication", arrayOf(IntDomain(0, 3), IntDomain(0, 3)), listOf(ne(0, 1), eq(1, 2))),
            Triple(
                "three literals",
                arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
                listOf(eq(0, 0), le(1, 0), ge(2, 2)),
            ),
            Triple(
                "hole domain",
                arrayOf(IntDomain(0, 4).excludeValue(2), IntDomain(0, 3)),
                listOf(eq(0, 2), ne(1, 1)),
            ),
        )
        for ((name, domains, lits) in cases) checkAgainstBrute(name, domains, lits)
    }

    @Test
    fun `single unsatisfiable literal is UNSAT`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 3)),
            factors = arrayOf<Factor>(clauseOf(listOf(le(0, -1)))),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(problem.bake()).solve(BacktrackParams(randomSeed = 1L)))
    }

    @Test
    fun `unit propagation enforces the surviving literal`() {
        // (x <= 0) v (y >= 5), x in [1,3] forces the first literal false, so y >= 5 must hold; with
        // y in [0,4] that is impossible -> UNSAT, exercising the enforce-then-conflict path.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(1, 3), IntDomain(0, 4)),
            factors = arrayOf<Factor>(clauseOf(listOf(le(0, 0), ge(1, 5)))),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(problem.bake()).solve(BacktrackParams(randomSeed = 1L)))
    }
}
