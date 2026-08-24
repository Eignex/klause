package com.eignex.klause.factor

import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.values
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the graded-violation contract for a [Factor]: `deltaIf*` and `apply*` must
 * each return exactly `violationDegree(after) - violationDegree(before)`, and the engine's
 * incrementally-maintained [LocalSearchState.cost] (`Σ violationDegree`) must stay in lockstep
 * with a fresh [LocalSearchState.recompute].
 *
 * A factor whose graded delta disagrees with its [Factor.violationDegree] silently
 * desyncs the global cost and corrupts the CBLS gradient; this oracle catches that by random-
 * walking the assignment space and checking, after every move:
 *  - `netDelta(move)` (the summed `deltaIf*`) predicted the actual `cost` change, and
 *  - the maintained `cost` equals the cost a fresh `recompute` of the same assignment produces.
 *
 * Also asserts the degree is non-negative and `(degree > 0) == isViolated` at every step, so
 * a graded factor can't report a positive degree while claiming to be satisfied (or vice versa).
 */
object DegreeConsistencyOracle {

    /**
     * `exactProbe`: when true, additionally require `netDelta(move)` (the summed
     *   `deltaIf*` probe used by the CBLS gradient) to *exactly* predict the cost change —
     *   i.e. the probe is graded-exact, not a best-effort approximation. Turn this on for
     *   factors with graded violation (their gradient must be accurate); leave off for
     *   factors whose probe is approximate by design (cost-tracking via `apply*` is still
     *   verified for them).
     */
    fun assertConsistent(
        problem: Problem,
        label: String = "factor",
        iters: Int = 200,
        seed: Long = 0xD0E5L,
        exactProbe: Boolean = false,
        softCap: Int? = null,
    ) {
        require(problem.factors.size == 1) { "DegreeConsistencyOracle expects a single-factor Problem" }
        val rng = Random(seed)
        val state = LocalSearchState(problem, rng)
        val fresh = LocalSearchState(problem, Random(seed xor 0x5A5AL))
        // A non-default cap must be set on BOTH states (the fresh-recompute comparison uses it too)
        // before any recompute. Exercises the violationSoftCap threading: a compress call left on
        // the default cap would desync the maintained cost from the fresh recompute here.
        if (softCap != null) {
            state.violationSoftCap = softCap
            fresh.violationSoftCap = softCap
        }

        randomize(state, problem, rng)
        state.recompute()
        assertDegreeInvariants(state, label, "initial")
        assertMatchesFreshRecompute(state, fresh, problem, label, "initial")

        repeat(iters) { iter ->
            val move = randomMove(state, problem, rng) ?: return@repeat
            val predicted = state.netDelta(move)
            val costBefore = state.cost
            state.apply(move)
            // Cost-tracking correctness (universal): apply* must keep the maintained cost in
            // lockstep with a fresh recompute of the same assignment.
            assertDegreeInvariants(state, label, "iter=$iter after $move")
            assertMatchesFreshRecompute(state, fresh, problem, label, "iter=$iter after $move")
            // Probe exactness (graded factors only).
            if (exactProbe) {
                assertEquals(
                    costBefore + predicted,
                    state.cost,
                    "$label: netDelta($move)=$predicted did not predict the cost change " +
                        "($costBefore → ${state.cost}) on iter=$iter — graded deltaIf* probe is " +
                        "not exact (CBLS gradient would be wrong).",
                )
            }
        }
    }

    private fun assertDegreeInvariants(state: LocalSearchState, label: String, where: String) {
        val deg = state.factors[0].violationDegree(state, 0)
        assertTrue(deg >= 0, "$label: violationDegree=$deg is negative ($where)")
        assertEquals(
            deg > 0,
            state.factors[0].isViolated(state, 0),
            "$label: (violationDegree>0)=${deg > 0} disagrees with isViolated=${state.factors[0].isViolated(
                state,
                0,
            )} ($where)",
        )
    }

    /** The engine maintains `cost` and per-factor `factorDegree` incrementally; assert they
     *  equal what a clean recompute of the identical assignment yields. */
    private fun assertMatchesFreshRecompute(
        state: LocalSearchState,
        fresh: LocalSearchState,
        problem: Problem,
        label: String,
        where: String,
    ) {
        for (b in 0 until problem.numBoolVars) fresh.assignment.setBool(b, state.assignment.boolValue(b))
        for (i in 0 until problem.numIntVars) fresh.assignment.setInt(i, state.assignment.intValue(i))
        fresh.recompute()
        assertEquals(
            fresh.cost,
            state.cost,
            "$label: maintained cost=${state.cost} != fresh recompute cost=${fresh.cost} ($where)",
        )
        assertEquals(
            fresh.factorDegree[0],
            state.factorDegree[0],
            "$label: maintained factorDegree=${state.factorDegree[0]} != fresh=${fresh.factorDegree[0]} ($where)",
        )
    }

    private fun randomMove(state: LocalSearchState, problem: Problem, rng: Random): Move? {
        val nb = problem.numBoolVars
        val ni = problem.numIntVars
        if (nb + ni == 0) return null
        // Bias toward int moves when both exist; both kinds get exercised over many iters.
        return if (ni > 0 && (nb == 0 || rng.nextBoolean())) {
            val v = rng.nextInt(ni)
            val d = problem.intDomains[v]
            val cur = state.assignment.intValue(v)
            val target = d.values.valueAt(rng.nextInt(d.values.size))
            if (target == cur) null else Move.IntSet(v, target)
        } else {
            Move.BoolFlip(rng.nextInt(nb))
        }
    }

    private fun randomize(state: LocalSearchState, problem: Problem, rng: Random) {
        for (b in 0 until problem.numBoolVars) state.assignment.setBool(b, rng.nextBoolean())
        for (i in 0 until problem.numIntVars) {
            val d: IntDomain = problem.intDomains[i]
            state.assignment.setInt(i, d.values.valueAt(rng.nextInt(d.values.size)))
        }
    }
}
