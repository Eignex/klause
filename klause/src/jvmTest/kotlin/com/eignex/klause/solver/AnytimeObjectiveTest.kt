package com.eignex.klause.solver

import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression tests for the anytime invariant: `LocalSearchSolver.minimize` must return
 * the best feasible solution it has seen so far whenever it stops, regardless of why
 * it stopped (budget exhausted, cancelled, etc.). Returning null means "no feasible
 * was seen at all", never "I forgot the one I found".
 */
class AnytimeObjectiveTest {

    /** Budget exhausts before LS finds optimum on a small problem; we should still
     *  receive *some* feasible solution rather than null. */
    @Test
    fun `minimize returns best feasible when budget runs out`() {
        // Exactly-one over 4 bools, objective minimises a weighted sum. Three feasibles;
        // any of them is a valid "best so far" return — just must not be null.
        val factor = Cardinality.exactlyOne(intArrayOf(
            Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true),
        ))
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val obj = LinearObjective(boolWeights = doubleArrayOf(10.0, 1.0, 100.0, 50.0))
        // A budget of 50 flips is enough to find at least one feasible.
        val sample = LocalSearchSolver(problem).minimize(obj, LocalSearchParams(maxFlips = 50, randomSeed = 1L)).assignment
        assertNotNull(sample, "minimize must return a feasible when one was reached during the budget")
        assertTrue(sample.bools.count { it } == 1, "must be a feasible exactly-one assignment")
    }

    /** Cancel mid-search; we should get back whatever was the best feasible at the
     *  moment of cancellation. */
    @Test
    fun `minimize on cancellation returns best feasible seen so far`() {
        val factor = Cardinality.exactlyOne(intArrayOf(
            Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true),
            Lit.make(4, true), Lit.make(5, true),
        ))
        val problem = Problem(6, 0, emptyArray(), listOf(factor))
        val obj = LinearObjective(boolWeights = doubleArrayOf(10.0, 5.0, 1.0, 100.0, 50.0, 25.0))
        val cancel = AtomicBoolean(false)

        val flagger = thread(start = true) { Thread.sleep(50); cancel.set(true) }
        val sample = LocalSearchSolver(problem).minimize(
            obj,
            LocalSearchParams(
                maxFlips = Long.MAX_VALUE,
                randomSeed = 0L,
                cancellation = { cancel.get() },
            ),
        ).assignment
        flagger.join()
        // 50ms is enough for any local-search to find at least one of the 6 feasibles.
        assertNotNull(sample, "minimize must remember the best feasible across cancellation")
        assertTrue(sample.bools.count { it } == 1)
    }
}
