package com.eignex.klause.localsearch.movesource

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SatisfiedStructuredTest {

    private val sampleCount = 4

    /** Two `Linear EQ` factors satisfied at the all-zero initial assignment (sum 0), each with
     *  symmetric domains so structured pair-shifts exist — so both scopes emit non-empty pools. */
    private fun feasibleProblem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 3,
        intDomains = arrayOf(IntDomain(-3, 3), IntDomain(-3, 3), IntDomain(-3, 3)),
        factors = arrayOf<Factor>(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 0),
            Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.EQ, 0),
        ),
    )

    /** The expected sampled construction: draw [sampleCount] factors, propose from the non-violated ones. */
    private fun expectedSampled(state: LocalSearchState, sink: MoveSink) {
        val total = state.problem.numFactors
        if (total == 0) return
        repeat(sampleCount) {
            val fid = state.rng.nextInt(total)
            if (!state.violated.contains(fid)) {
                state.factors[fid].proposeStructuredMoves(state, fid, sink)
            }
        }
    }

    /** The expected enumerate-all construction: propose from every satisfied factor. */
    private fun expectedAll(state: LocalSearchState, sink: MoveSink) {
        for (fid in 0 until state.problem.numFactors) {
            val f = state.factors[fid]
            if (!f.isViolated(state, fid)) f.proposeStructuredMoves(state, fid, sink)
        }
    }

    /** An `AllDifferent` (which provides an implicit neighbourhood, so it is elected) over three
     *  vars, satisfied at the identity permutation so it proposes structured swaps. */
    private fun electedProblem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 3,
        intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
        factors = arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3)),
    )

    /** Seed the identity permutation so the AllDifferent is satisfied (and thus consulted). */
    private fun seedPermutation(state: LocalSearchState) {
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 1)
        state.assignment.setInt(2, 2)
        state.recompute()
    }

    /** The expected elected construction: sample the elected implicit factors, propose from the satisfied ones. */
    private fun expectedElected(state: LocalSearchState, sink: MoveSink) {
        val elected = state.seeding.electedImplicit
        if (elected.isEmpty()) return
        repeat(minOf(sampleCount, elected.size)) {
            val fid = elected[state.rng.nextInt(elected.size)]
            if (!state.violated.contains(fid)) {
                state.factors[fid].proposeStructuredMoves(state, fid, sink)
            }
        }
    }

    @Test
    fun `sampled scope draws sampleCount satisfied factors' structured moves`() {
        for (seed in longArrayOf(1L, 7L, 42L, 1234L, 99999L)) {
            assertSourceMatchesGenerator(::feasibleProblem, seed, SatisfiedStructured.sampled(sampleCount)) { s, sink ->
                expectedSampled(s, sink)
            }
        }
    }

    @Test
    fun `all scope enumerates every satisfied factor's structured moves`() {
        for (seed in longArrayOf(1L, 7L, 42L, 1234L)) {
            assertSourceMatchesGenerator(::feasibleProblem, seed, SatisfiedStructured.all()) { s, sink ->
                expectedAll(s, sink)
            }
        }
    }

    @Test
    fun `elected scope samples the elected implicit factors' structured moves`() {
        for (seed in longArrayOf(1L, 7L, 42L, 1234L)) {
            assertSourceMatchesGenerator(
                ::electedProblem,
                seed,
                SatisfiedStructured.elected(sampleCount),
                prepare = ::seedPermutation,
            ) { s, sink -> expectedElected(s, sink) }
        }
    }

    @Test
    fun `structural scope reaches an owned global among satisfied arithmetic rows`() {
        val global = electedProblem()
        val problem = Problem(0, 3, global.finiteIntDomains(), global.factors + Array<Factor>(256) {
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2)
        })
        val state = freshState(problem, 7L)
        seedPermutation(state)
        val sink = MoveSink()
        sink.setOwners(intArrayOf(0, 0, 0))

        SatisfiedStructured.structural(1).generate(state, sink)

        assertTrue(sink.list.isNotEmpty())
        for (move in sink.list) assertEquals(0L, state.netDelta(move))
        assertEquals(MoveSink.NO_PROPOSER, sink.proposer)
    }

    @Test
    fun `structural scope retains uniform sampling without elected globals`() {
        for (seed in longArrayOf(1L, 7L, 42L, 1234L)) {
            assertSourceMatchesGenerator(
                ::feasibleProblem, seed, SatisfiedStructured.structural(sampleCount),
            ) { s, sink ->
                expectedSampled(s, sink)
            }
        }
    }

    @Test
    fun `zero structural draws emit no global moves`() {
        val state = freshState(electedProblem(), 7L)
        seedPermutation(state)
        val sink = MoveSink()

        SatisfiedStructured.structural(0).generate(state, sink)

        assertTrue(sink.list.isEmpty())
    }

    @Test
    fun `structural scope skips a violated elected global`() {
        val state = freshState(electedProblem(), 7L)
        val sink = MoveSink()

        SatisfiedStructured.structural(1).generate(state, sink)

        assertTrue(sink.list.isEmpty())
    }
}
