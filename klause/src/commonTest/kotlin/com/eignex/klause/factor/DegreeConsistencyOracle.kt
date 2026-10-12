package com.eignex.klause.factor

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.values
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Random-walk oracle for graded violation: applied deltas equal the change in violation degree,
 * and incremental cost equals a fresh recomputation. Degrees are nonnegative and positive exactly
 * when the factor is violated.
 */
object DegreeConsistencyOracle {

    /**
     * [exactProbe] also requires the probed delta to equal the applied cost change. Disable it for
     * approximate probes; incremental apply and recomputation must still agree.
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
        val baked = problem.bake()
        val state = LocalSearchState(baked, rng)
        val fresh = LocalSearchState(baked, Random(seed xor 0x5A5AL))
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
            val d = state.rootDomains[v]
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
            val d: IntDomain = state.rootDomains[i]
            state.assignment.setInt(i, d.values.valueAt(rng.nextInt(d.values.size)))
        }
    }
}
