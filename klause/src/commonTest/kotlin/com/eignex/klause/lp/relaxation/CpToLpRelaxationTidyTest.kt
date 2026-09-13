package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.RelaxationTidyConfig
import com.eignex.klause.lp.bounding.RelaxationTidyDecline
import com.eignex.klause.lp.engine.FloatLpStatus
import com.eignex.klause.lp.engine.solveLp
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class CpToLpRelaxationTidyTest {

    @Test
    fun `explicit root hook tidies while the default remains off`() {
        val problem = boundedProblem()
        val objective = LinearObjective(intCoefficients = longArrayOf(-1, 0))

        val off = CpToLpRelaxation(problem, objective).build(RootDomains(problem))
        val on = CpToLpRelaxation(
            problem,
            objective,
            tidy = RelaxationTidyConfig(enabled = true),
        ).build(RootDomains(problem))

        assertNull(off.tidyDerivation)
        assertEquals(2, off.model.m)
        assertNotNull(on.tidyDerivation)
        assertEquals(1, on.model.m)
        assertEquals(FloatLpStatus.OPTIMAL, solveLp(off.model).status)
        assertEquals(-2.5, solveLp(off.model).objectiveValue)
        assertEquals(-2.0, solveLp(on.model).objectiveValue)
        assertTrue(on.tidyDerivation.validate())
        val retained = on.withModel(on.model)
        assertSame(on.tidyDerivation, retained.tidyDerivation)
        assertTrue(assertNotNull(retained.tidyDerivation).validate())
        assertSame(problem, assertNotNull(retained.tidyProof).sources.model)
        val rebound = on.withModel(on.model.rebind(longArrayOf(0, 0), longArrayOf(10, 10)))
        assertSame(on.tidyDerivation, rebound.tidyDerivation)
        assertTrue(assertNotNull(rebound.tidyDerivation).validate())
        assertSame(problem, assertNotNull(rebound.tidyProof).sources.model)
    }

    @Test
    fun `session builds are explicitly excluded even at decision level zero`() {
        val problem = boundedProblem()
        val relaxation = CpToLpRelaxation(
            problem,
            null,
            tidy = RelaxationTidyConfig(enabled = true),
        ).build(PropagationSession(problem))

        assertNull(relaxation.tidyDerivation)
        assertEquals(1, relaxation.tidyStats?.declined(RelaxationTidyDecline.NOT_ROOT))
    }

    @Test
    fun `root fixed substitution retains source coordinates and disables persistent reuse`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(2, 2), IntDomain(0, 4)),
            factors = arrayOf<Factor>(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 5),
            ),
        )
        val relaxation = CpToLpRelaxation(
            problem,
            LinearObjective(intCoefficients = longArrayOf(7, -3)),
            tidy = RelaxationTidyConfig(enabled = true),
        ).build(RootDomains(problem))

        val derivation = assertNotNull(relaxation.tidyDerivation)
        assertEquals(2, relaxation.model.loShift[0])
        assertEquals(14, relaxation.model.objConstant)
        assertTrue(derivation.verifySourceWitness(longArrayOf(2, 3)))
        assertTrue(!relaxation.persistentEligible)
    }

    @Test
    fun `bounded labelled workload accounts for construction solve and proof cost`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 10), IntDomain(0, 10)),
            factors = arrayOf<Factor>(
                Linear(intArrayOf(2), intArrayOf(0), LinearOp.LE, 4),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 12),
            ),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(-1, 0))
        var offNanos = 0L
        var onNanos = 0L
        var proofNanos = 0L
        var reductions = 0
        repeat(20) {
            var mark = TimeSource.Monotonic.markNow()
            val off = CpToLpRelaxation(problem, objective).build(RootDomains(problem))
            val offSolution = solveLp(off.model)
            offNanos += mark.elapsedNow().inWholeNanoseconds

            mark = TimeSource.Monotonic.markNow()
            val on = CpToLpRelaxation(
                problem,
                objective,
                tidy = RelaxationTidyConfig(enabled = true),
            ).build(RootDomains(problem))
            val onSolution = solveLp(on.model)
            onNanos += mark.elapsedNow().inWholeNanoseconds

            mark = TimeSource.Monotonic.markNow()
            val derivation = assertNotNull(on.tidyDerivation)
            assertTrue(derivation.validate())
            proofNanos += mark.elapsedNow().inWholeNanoseconds
            reductions += off.model.m - on.model.m
            assertEquals(offSolution.objectiveValue, onSolution.objectiveValue)
        }
        println(
            "RELAXATION_TIDY_WORKLOAD label=exact-small-root repeats=20 " +
                "off_total_ns=$offNanos on_total_ns=$onNanos proof_total_ns=$proofNanos reductions=$reductions",
        )
        assertEquals(20, reductions)
    }

    private fun boundedProblem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(0, 10), IntDomain(0, 10)),
        factors = arrayOf<Factor>(
            Linear(intArrayOf(2), intArrayOf(0), LinearOp.LE, 5),
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 12),
        ),
    )
}
