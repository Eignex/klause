package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The presolve driver's contract: the [PresolveOutcome.reconstruct] composed across rounds must lift
 * every solution of the transformed problem back to a solution of the original, the transform must not
 * change satisfiability, and a presolve that reduces nothing must hand the caller its own problem back.
 */
class PresolvePipelineTest {

    private fun isFeasible(problem: Problem, ints: LongArray): Boolean {
        var a = Assumptions.None
        for (v in 0 until problem.numIntVars) a = a.withInt(v, ints[v])
        return problem.propagate(a) !is PropagationResult.Unsat
    }

    /** Every int tuple inside [problem]'s declared domains. */
    private fun boxPoints(problem: Problem): List<LongArray> {
        val n = problem.numIntVars
        val out = ArrayList<LongArray>()
        val ints = LongArray(n) { problem.requireFiniteIntDomains()[it].min }
        while (true) {
            out.add(ints.copyOf())
            var i = 0
            while (i < n) {
                ints[i]++
                if (ints[i] <= problem.requireFiniteIntDomains()[i].max) break
                ints[i] = problem.requireFiniteIntDomains()[i].min
                i++
            }
            if (i == n) break
        }
        return out
    }

    private val model = Problem(
        numBoolVars = 0,
        numIntVars = 3,
        intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
        factors = listOf(
            Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 3),
            Linear(longArrayOf(1, -1), intArrayOf(1, 2), LinearOp.LE, 1),
            Linear(longArrayOf(1), intArrayOf(2), LinearOp.GE, 1),
        ),
    )

    @Test
    fun `reconstruct lifts every solution of the transformed problem back to the original`() {
        val outcome = PresolvePipeline.run(model, null, PresolveConfig.AUTO, solutionSetSensitive = false)
        assertTrue(outcome.changed, "the fixture must actually be transformed for this to test anything")

        var lifted = 0
        for (p in boxPoints(outcome.problem)) {
            if (!isFeasible(outcome.problem, p)) continue
            val recon = outcome.reconstruct(Sample(bools = BooleanArray(0), ints = p))
            assertTrue(
                isFeasible(model, recon.ints),
                "reconstruct produced ${recon.ints.toList()} which does not satisfy the original",
            )
            lifted++
        }
        // `lifted > 0` plus the lift check covers reduced ⇒ original; this covers the other direction.
        assertEquals(
            boxPoints(model).any { isFeasible(model, it) },
            lifted > 0,
            "presolve changed satisfiability",
        )
    }

    @Test
    fun `a pass-proven infeasibility is reported in the stats`() {
        // 2x + 4y = 3: the coefficient gcd does not divide the bound, so a pass proves infeasibility
        // outright rather than leaving it to the bake.
        val unsat = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 4), IntDomain(0, 4)),
            factors = listOf(Linear(longArrayOf(2, 4), intArrayOf(0, 1), LinearOp.EQ, 3)),
        )
        val outcome = PresolvePipeline.run(unsat, null, PresolveConfig.AUTO, solutionSetSensitive = false)
        assertTrue(outcome.stats.infeasible, "the gcd rule proves the model infeasible")
        assertTrue(boxPoints(unsat).none { isFeasible(unsat, it) }, "the fixture really has no solution")
    }

    @Test
    fun `reconstruct rebuilds a substituted binary column from the literal that replaced it`() {
        // A triangle of at-least-one rows over three `{0, 1}` columns: the whole model leaves the integer
        // lane, so the composed reconstruct has to rebuild every column from its literal.
        val binary = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1)),
            factors = listOf(
                Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
                Linear(longArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
                Linear(longArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
            ),
        )
        val outcome = PresolvePipeline.run(binary, null, PresolveConfig.AUTO, solutionSetSensitive = false)
        assertTrue(outcome.problem.numBoolVars > 0, "the columns must have become literals for this to test anything")

        val lifted = ArrayList<List<Long>>()
        for (mask in 0 until (1 shl outcome.problem.numBoolVars)) {
            val bools = BooleanArray(outcome.problem.numBoolVars) { ((mask shr it) and 1) == 1 }
            val ints = LongArray(outcome.problem.numIntVars) { outcome.problem.requireFiniteIntDomains()[it].min }
            var a = Assumptions.None
            for (b in bools.indices) a = a.withBool(b, bools[b])
            for (v in ints.indices) a = a.withInt(v, ints[v])
            if (outcome.problem.propagate(a) is PropagationResult.Unsat) continue
            val recon = outcome.reconstruct(Sample(bools, ints))
            assertTrue(isFeasible(binary, recon.ints), "reconstruct produced ${recon.ints.toList()}, not a solution")
            lifted.add(recon.ints.toList())
        }
        assertTrue(lifted.isNotEmpty(), "the substituted model must keep the original's solutions reachable")
    }

    @Test
    fun `a disabled presolve hands back the caller's own problem`() {
        val outcome = PresolvePipeline.run(model, null, PresolveConfig.NONE, solutionSetSensitive = false)
        assertFalse(outcome.changed, "no pass ran, so nothing changed")
        assertSame(model, outcome.problem, "a no-op presolve must preserve the caller's handle")
    }
}
