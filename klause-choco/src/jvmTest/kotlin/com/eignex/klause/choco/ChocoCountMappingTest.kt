package com.eignex.klause.choco

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.factor.Count
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Regression for the Choco reference mapping of [Count]. `count_eq(xs, v, n)` means
 * `n = #{i : xs[i] = v}` where `n` is a *variable*. The adapter must compare the running
 * count to `intVars[n]`, not to the raw variable id `n` used as a constant — the latter
 * fabricates a false UNSAT whenever `n`'s id exceeds `xs.size` (the count var's id is large
 * in real models like the MiniZinc Challenge `cars` instance, tiny in toy tests, which is why
 * this slipped through).
 */
class ChocoCountMappingTest {

    @Test
    fun `count_eq compares the running count to the count variable, not its id`() {
        // ids 0,1,2 = xs; 3,4 = dummies (push n's id past xs.size); 5 = n.
        val nId = 5
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 6,
            intDomains = arrayOf(
                IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1),
                IntDomain(0, 1), IntDomain(0, 1),
                IntDomain(0, 3),
            ),
            factors = arrayOf<Factor>(Count(xs = intArrayOf(0, 1, 2), v = 1, op = Count.Op.Eq, n = nId)),
        )
        val r = ChocoSolver(problem).solve(ChocoParams())
        val sat = assertIs<SolveResult.Sat>(r, "count_eq must stay satisfiable; got $r (false UNSAT = id-as-constant bug)")
        // n must equal the actual number of xs fixed to 1.
        val actual = (0..2).count { sat.assignment.ints[it] == 1 }
        assertEquals(actual, sat.assignment.ints[nId], "n must hold the match count")
    }

    @Test
    fun `count_eq can force a specific count through the count variable`() {
        // Pin n = 3 via a singleton domain; only all-ones satisfies, so the count var is
        // genuinely wired to the running count (not ignored).
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(3, 3)),
            factors = arrayOf<Factor>(Count(xs = intArrayOf(0, 1, 2), v = 1, op = Count.Op.Eq, n = 3)),
        )
        val r = ChocoSolver(problem).solve(ChocoParams())
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals(listOf(1, 1, 1), (0..2).map { sat.assignment.ints[it] }, "n=3 forces all xs=1")
    }
}
