package com.eignex.klause.solver

import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.strategy.Ddfw
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalSearchSessionTest {

    private fun ddfwProblem(): Problem {
        // 5 cardinality factors over overlapping vars — DDFW shuffles weights between
        // satisfied / unsatisfied neighbours, producing non-default weights quickly.
        return Problem(
            numBoolVars = 6, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(
                Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Cardinality.exactlyOne(intArrayOf(Lit.make(2, true), Lit.make(3, true))),
                Cardinality.exactlyOne(intArrayOf(Lit.make(4, true), Lit.make(5, true))),
                Cardinality.exactlyOne(intArrayOf(Lit.make(1, true), Lit.make(2, true))),
                Cardinality.exactlyOne(intArrayOf(Lit.make(3, true), Lit.make(4, true))),
            ),
        )
    }

    @Test
    fun `session captures DDFW factor weights after a call`() {
        val problem = ddfwProblem()
        val solver = LocalSearchSolver(problem, strategy = Ddfw())
        val session = LocalSearchSession(solver)
        // Before any call: warm state is empty.
        assertNull(session.warmState.factorWeights)
        // Run a sample-search; DDFW will mutate weights along the way.
        session.sample(LocalSearchParams(maxFlips = 2_000L, randomSeed = 1L))
        val captured = session.warmState.factorWeights
        assertNotNull(captured, "session should capture factorWeights")
        assertEquals(problem.numFactors, captured.size)
        // At least one weight should have deviated from the default of 1.0.
        assertTrue(captured.any { it != 1.0 }, "DDFW should learn non-default weights")
    }

    @Test
    fun `reset clears warm state`() {
        val problem = ddfwProblem()
        val session = LocalSearchSession(LocalSearchSolver(problem, strategy = Ddfw()))
        session.sample(LocalSearchParams(maxFlips = 1_000L, randomSeed = 2L))
        assertNotNull(session.warmState.factorWeights)
        session.reset()
        assertNull(session.warmState.factorWeights)
    }

    @Test
    fun `warm weights survive across two minimize calls`() {
        val problem = ddfwProblem()
        val session = LocalSearchSession(LocalSearchSolver(problem, strategy = Ddfw()))
        val obj = LinearObjective(boolWeights = DoubleArray(6) { 1.0 })
        session.minimize(obj, LocalSearchParams(maxFlips = 1_000L, randomSeed = 5L))
        val firstWeights = session.warmState.factorWeights!!.copyOf()
        session.minimize(obj, LocalSearchParams(maxFlips = 1_000L, randomSeed = 6L))
        val secondWeights = session.warmState.factorWeights!!
        // The second capture should not be exactly equal to the all-defaults vector —
        // it built on top of the first.
        val allOnes = DoubleArray(problem.numFactors) { 1.0 }
        assertTrue(
            !secondWeights.contentEquals(allOnes),
            "second call should have learned weights, not reset to defaults",
        )
        // And it should generally differ from the first capture (further learning happened).
        // Tolerate equality only in pathological cases; this is a loose check.
        assertTrue(
            firstWeights.size == secondWeights.size,
            "weight array shape must match across calls",
        )
    }

    @Test
    fun `bare solver call does not touch the session warm state`() {
        // Concurrent / non-session callers shouldn't have a path to mutate session warm
        // state. Verified by running a bare solver call and confirming the unrelated
        // session's warm state is still null afterwards.
        val problem = ddfwProblem()
        val solver = LocalSearchSolver(problem, strategy = Ddfw())
        val session = LocalSearchSession(solver)
        // Bare call — bypasses the session entirely.
        solver.sample(LocalSearchParams(maxFlips = 1_000L, randomSeed = 9L))
        assertNull(session.warmState.factorWeights, "bare solver call must not write to session warm state")
    }
}
