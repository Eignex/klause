package com.eignex.klause.backtrack.lp

import com.eignex.klause.backtrack.ComposedSampleVerifier
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The LP primal heuristics are untrusted producers, so what they hand back is only a proposal — but a
 * producer whose proposals the composed factor set routinely refuses would silently stop seeding
 * incumbents. Every proposal the three of them make on random covering/packing instances must survive
 * [ComposedSampleVerifier].
 */
class LpProposalVerificationTest {

    private val producers: List<Pair<String, LpEngine.(LinearObjective, Cancellation) -> Sample?>> = listOf(
        "the rounding probe" to LpEngine::lpRoundingProbe,
        "the feasibility pump" to LpEngine::lpFeasibilityPump,
        "the best-bound tree search" to LpEngine::lbTreeSearch,
    )

    private fun instance(rng: Random): Pair<Problem, LinearObjective> {
        val n = rng.nextInt(3, 7)
        val factors = ArrayList<Factor>()
        repeat(rng.nextInt(1, 4)) {
            val k = rng.nextInt(2, n + 1)
            val vars = (0 until n).shuffled(rng).take(k).toIntArray()
            val coeffs = IntArray(k) { 1 }
            // Covering (≥1, satisfiable by ones) or packing (≤k-1, satisfiable by zeros).
            if (rng.nextBoolean()) {
                factors.add(Linear(coeffs, vars, LinearOp.GE, 1))
            } else {
                factors.add(Linear(coeffs, vars, LinearOp.LE, k - 1))
            }
        }
        val problem = Problem(0, n, Array(n) { IntDomain(0, 1) }, factors.toTypedArray())
        return problem to LinearObjective(intCoefficients = LongArray(n) { rng.nextLong(-2, 3) })
    }

    @Test
    fun `every LP primal proposal passes composed verification`() {
        val rng = Random(20260902)
        val produced = producers.associate { (name, _) -> name to 0 }.toMutableMap()
        repeat(6) {
            val (problem, objective) = instance(rng)
            val engine = LpEngine(
                problem,
                objective,
                LpParams(lpPlan = LpPlan(bounding = true, probe = true, lbTreeSearch = true)),
                SolveStatsSink(backend = "lp-proposal-test"),
            )
            val verifier = ComposedSampleVerifier(problem, objective, Cancellation.Never)
            for ((name, produce) in producers) {
                val proposal = engine.produce(objective, Cancellation.Never) ?: continue
                produced[name] = produced.getValue(name) + 1
                assertIs<Verification.Accepted<Sample, Double>>(
                    verifier.verify(Candidate(proposal, objective.evaluate(proposal))),
                    "$name proposed an assignment the composed model refuses",
                )
            }
        }
        assertTrue(produced.values.all { it > 0 }, "a producer seeded nothing to verify: $produced")
    }
}
