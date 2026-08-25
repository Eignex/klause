package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.Chb
import com.eignex.klause.backtrack.selector.DomWdeg
import com.eignex.klause.backtrack.selector.DomainMaxRegret
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.backtrack.selector.LargestDomain
import com.eignex.klause.backtrack.selector.LastConflict
import com.eignex.klause.backtrack.selector.RandomVariable
import com.eignex.klause.backtrack.selector.SmallestDomain
import com.eignex.klause.backtrack.selector.Vsids
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * An unbounded `var int` reaches the search with the full `Long` range, which no span can enumerate.
 * The search still has to branch it and hand back a value that satisfies the model.
 */
class UnboundedColumnSolveTest {

    @Test
    fun `a column open below is branched and answered under every domain-reading selector`() {
        val selectors = listOf(
            "DomWdeg" to DomWdeg(),
            "SmallestDomain" to SmallestDomain,
            "LargestDomain" to LargestDomain,
            "InputOrder" to InputOrder,
            "RandomVariable" to RandomVariable,
            "DomainMaxRegret" to DomainMaxRegret,
            "Chb" to Chb(),
            "Vsids" to Vsids(),
            "LastConflict" to LastConflict(DomWdeg()),
        )
        for ((name, selector) in selectors) {
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 1,
                intDomains = arrayOf(IntDomain(Long.MIN_VALUE, Long.MAX_VALUE)),
                factors = arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, -5)),
            )

            val result = BacktrackSolver(problem.bake()).solve(
                BacktrackParams(variableSelector = selector, randomSeed = 0L),
            )

            val sat = assertIs<SolveResult.Sat>(result, name)
            assertTrue(
                sat.assignment.ints[0] <= -5L,
                "$name answered ${'$'}{sat.assignment.ints[0]}, which breaks the row",
            )
        }
    }

    @Test
    fun `an equality on an unbounded column is propagated to its single value`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(Long.MIN_VALUE, Long.MAX_VALUE)),
            factors = arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 7)),
        )

        val result = BacktrackSolver(problem.bake()).solve(BacktrackParams(randomSeed = 0L))

        assertEquals(7L, assertIs<SolveResult.Sat>(result).assignment.ints[0])
    }

    @Test
    fun `a global over a column no span can enumerate answers instead of walking it`() {
        // The declared span is `Long.MAX_VALUE`, the width whose successor wraps. A global that indexes a
        // value window sits on it, so the column reaches a value-enumerating propagator still full width:
        // if the domain hands out a truncated span here, the walk is 2^31 values long and the answer is
        // drawn from the wrong set.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, Long.MAX_VALUE), IntDomain(0, Long.MAX_VALUE)),
            factors = arrayOf<Factor>(
                AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 4),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3),
            ),
        )

        val result = BacktrackSolver(problem.bake()).solve(BacktrackParams(randomSeed = 0L))

        val ints = assertIs<SolveResult.Sat>(result).assignment.ints
        assertTrue(ints[0] <= 3L, "the row bounds the indexed column, got ${'$'}{ints[0]}")
        assertTrue(ints[0] != ints[1], "the global separates the two columns")
    }
}
