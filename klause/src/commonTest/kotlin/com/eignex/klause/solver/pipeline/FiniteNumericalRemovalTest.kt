package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.InvariantNetwork
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.lp.optimization.LinearAuthority
import com.eignex.klause.lp.optimization.LinearAuthorityCapture
import com.eignex.klause.lp.optimization.LinearAuthorityFeedbackRequest
import com.eignex.klause.lp.optimization.atScope
import com.eignex.klause.meta.alns.DestroyOperator
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.sourceCondition
import com.eignex.klause.solver.objective.FunctionalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FiniteNumericalRemovalTest {
    private val problem = Problem(0, 4, Array(4) { IntDomain(0, 1) },
        listOf(Linear(longArrayOf(1, 1), intArrayOf(0, 3), LinearOp.EQ, 1L))).bake()
    private val objective = LinearObjective(intCoefficients = longArrayOf(1, 0, 0, 0))
    private val center = Sample(booleanArrayOf(), longArrayOf(1, 0, 0, 0))
    private val source = requireNotNull(problem.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
        Cancellation.Never))

    @Test
    fun `relaxation disagreement should free differing source inputs`() {
        val funding = SourceLpFunding(1_000_000, 4_000_000).apply { grant(1_000_000) }
        val publisher = SourceNumericalFeedback(funding, Cancellation.Never)
        val acquisition = LinearAuthority(SourceLpBudget(funding = funding), retainLp = true).use {
            publisher.acquire(it, source, cuts = false)
        }
        val removal = FiniteNumericalRemoval(problem, objective, source, funding,
            FiniteNumericalRemoval.Signal.Disagreement)

        removal.accept(center, Assumptions.None, source, null, acquisition.feedback)
        val freed = removal.destroy(Random(17), problem, center, objective, 0.5)

        assertEquals(FiniteNumericalRemoval.Status.Supported, removal.status)
        assertEquals(setOf(0, 3), freed.ints.toSet())
        assertTrue(freed.bools.isEmpty())
    }

    @Test
    fun `completed selections should decline a request smaller than every packet`() {
        val funding = SourceLpFunding(1_000_000, 4_000_000).apply { grant(1_000_000) }
        val publisher = SourceNumericalFeedback(funding, Cancellation.Never)
        val acquisition = LinearAuthority(SourceLpBudget(funding = funding), retainLp = true).use {
            publisher.acquire(it, source, cuts = false)
        }
        val removal = FiniteNumericalRemoval(problem, objective, source, funding,
            FiniteNumericalRemoval.Signal.Disagreement, completion = FiniteNumericalRemoval.Completion.SourceRows)

        removal.accept(center, Assumptions.None, source, null, acquisition.feedback)
        val freed = removal.destroy(Random(17), problem, center, objective, 0.25)

        assertTrue(freed.isEmpty)
        assertEquals(FiniteNumericalRemoval.Status.Supported, removal.status)
        assertEquals("packet-exceeds-request", removal.lastDecline)
    }

    @Test
    fun `completed selections should fill places left after their packets`() {
        val funding = SourceLpFunding(1_000_000, 4_000_000).apply { grant(1_000_000) }
        val publisher = SourceNumericalFeedback(funding, Cancellation.Never)
        val acquisition = LinearAuthority(SourceLpBudget(funding = funding), retainLp = true).use {
            publisher.acquire(it, source, cuts = false)
        }
        val removal = FiniteNumericalRemoval(problem, objective, source, funding,
            FiniteNumericalRemoval.Signal.Disagreement, completion = FiniteNumericalRemoval.Completion.SourceRows)

        removal.accept(center, Assumptions.None, source, null, acquisition.feedback)
        val freed = removal.destroy(Random(17), problem, center, objective, 0.75)

        assertEquals(setOf(0, 3), removal.lastCompletedInputs.toSet())
        assertEquals(1, removal.lastFilledInputs)
        assertEquals(3, freed.ints.size)
        assertTrue(freed.ints.toSet().containsAll(setOf(0, 3)))
    }

    @Test
    fun `an agreeing relaxation should retain agreement with random fallback`() {
        val funding = SourceLpFunding(1_000_000, 4_000_000).apply { grant(1_000_000) }
        val publisher = SourceNumericalFeedback(funding, Cancellation.Never)
        val acquisition = LinearAuthority(SourceLpBudget(funding = funding), retainLp = true).use {
            publisher.acquire(it, source, cuts = false)
        }
        val optimum = assertNotNull(acquisition.result.incumbent)
        val removal = FiniteNumericalRemoval(problem, objective, source, funding,
            FiniteNumericalRemoval.Signal.Disagreement)

        removal.accept(optimum, Assumptions.None, source, null, acquisition.feedback)
        val freed = removal.destroy(Random(17), problem, optimum, objective, 0.5)

        assertEquals(FiniteNumericalRemoval.Status.Agreement, removal.status)
        assertEquals(DestroyOperator.Random.destroy(Random(17), problem, optimum, objective, 0.5), freed)
    }

    @Test
    fun `a missing relaxation point should remain distinct from agreement`() {
        val model = Problem(1, 4, Array(4) { IntDomain(0, 1) },
            listOf(Linear(longArrayOf(1, 1), intArrayOf(0, 3), LinearOp.EQ, 1L))).bake()
        val original = requireNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            Cancellation.Never))
        val incumbent = Sample(booleanArrayOf(false), longArrayOf(1, 0, 0, 0))
        val funding = SourceLpFunding(1_000_000, 4_000_000).apply { grant(1_000_000) }
        val publisher = SourceNumericalFeedback(funding, Cancellation.Never)
        val acquisition = LinearAuthority(SourceLpBudget(funding = funding), retainLp = true).use {
            publisher.acquire(it, original, cuts = false)
        }
        val removal = FiniteNumericalRemoval(model, objective, original, funding,
            FiniteNumericalRemoval.Signal.Disagreement)

        removal.accept(incumbent, Assumptions.None, original, null, acquisition.feedback)
        val freed = removal.destroy(Random(17), model, incumbent, objective, 0.5)

        assertEquals(FiniteNumericalRemoval.Status.NoPoint, removal.status)
        assertEquals(DestroyOperator.Random.destroy(Random(17), model, incumbent, objective, 0.5), freed)
    }

    @Test
    fun `a delivered point projection should rank like the full relaxation record`() {
        val funding = SourceLpFunding(1_000_000, 4_000_000).apply { grant(1_000_000) }
        val removal = FiniteNumericalRemoval(problem, objective, source, funding,
            FiniteNumericalRemoval.Signal.Disagreement)
        val publisher = SourceNumericalFeedback(funding, Cancellation.Never, request = removal.request)
        val acquisition = LinearAuthority(SourceLpBudget(funding = funding), retainLp = true).use {
            it.capture = LinearAuthorityCapture(0, 0L, 0L, Cancellation.Never)
            publisher.acquire(it, source, cuts = false)
        }

        removal.accept(center, Assumptions.None, source, null, acquisition.feedback)
        val freed = removal.destroy(Random(17), problem, center, objective, 0.5)

        assertEquals(removal.request, assertIs<SourceNumericalFeedbackResult.Available>(acquisition.feedback)
            .query.projection)
        assertEquals(FiniteNumericalRemoval.Status.Supported, removal.status)
        assertEquals(setOf(0, 3), freed.ints.toSet())
    }

    @Test
    fun `a delivered projection without the signal field should be unavailable rather than agreement`() {
        val funding = SourceLpFunding(1_000_000, 4_000_000).apply { grant(1_000_000) }
        val publisher = SourceNumericalFeedback(funding, Cancellation.Never,
            request = LinearAuthorityFeedbackRequest(refutation = true))
        val acquisition = LinearAuthority(SourceLpBudget(funding = funding), retainLp = true).use {
            it.capture = LinearAuthorityCapture(0, 0L, 0L, Cancellation.Never)
            publisher.acquire(it, source, cuts = false)
        }
        val removal = FiniteNumericalRemoval(problem, objective, source, funding,
            FiniteNumericalRemoval.Signal.Disagreement)

        removal.accept(center, Assumptions.None, source, null, acquisition.feedback)

        assertEquals(FiniteNumericalRemoval.Status.Unavailable, removal.status)
        assertEquals("unrequested", removal.unavailableReason)
    }

    @Test
    fun `a conditioned refutation should rank its captured source support`() {
        val funding = SourceLpFunding(1_000_000, 4_000_000).apply { grant(1_000_000) }
        val pins = Assumptions.None.withInt(3, 0)
        val pinned = source.atScope(source.scope.copy(condition = pins.sourceCondition()))
        val cutoff = SourceNumericalCutoff(BigFraction.ONE, "repair-improvement")
        val query = assertNotNull(cutoff.applyTo(pinned, funding))
        val publisher = SourceNumericalFeedback(funding, Cancellation.Never)
        val acquisition = LinearAuthority(SourceLpBudget(funding = funding), retainLp = true).use {
            publisher.acquire(it, query, cutoff, cuts = false)
        }
        val feedback = assertIs<SourceNumericalFeedbackResult.Available>(acquisition.feedback)
        val support = publisher.refutationSupport(feedback)
        val removal = FiniteNumericalRemoval(problem, objective, source, funding,
            FiniteNumericalRemoval.Signal.Refutation)

        removal.accept(center, pins, query, cutoff, feedback, support)
        val freed = removal.destroy(Random(17), problem, center, objective, 0.5)

        assertEquals(FiniteNumericalRemoval.Status.Supported, removal.status)
        assertEquals(setOf(0, 3), freed.ints.toSet())
        assertEquals(query.scope, removal.originScope)
        assertEquals(cutoff, removal.originCutoff)
    }

    @Test
    fun `maximizing source cutoffs should retain original objective units`() {
        val funding = SourceLpFunding(1_000_000, 4_000_000).apply { grant(1_000_000) }
        val original = requireNotNull(problem.linearAuthorityContext(objective.negated(), ObjectiveSense.MAXIMIZE,
            Cancellation.Never))
        val pins = Assumptions.None.withInt(3, 0)
        val pinned = original.atScope(original.scope.copy(condition = pins.sourceCondition()))
        val cutoff = SourceNumericalCutoff(BigFraction.MINUS_ONE, "repair-improvement")
        val query = assertNotNull(cutoff.applyTo(pinned, funding))
        val publisher = SourceNumericalFeedback(funding, Cancellation.Never)
        val acquisition = LinearAuthority(SourceLpBudget(funding = funding), retainLp = true).use {
            publisher.acquire(it, query, cutoff, cuts = false)
        }
        val feedback = assertIs<SourceNumericalFeedbackResult.Available>(acquisition.feedback)
        val support = publisher.refutationSupport(feedback)
        val removal = FiniteNumericalRemoval(problem, objective, original, funding,
            FiniteNumericalRemoval.Signal.Refutation)

        removal.accept(center, pins, query, cutoff, feedback, support)
        val freed = removal.destroy(Random(17), problem, center, objective, 0.5)

        assertEquals(FiniteNumericalRemoval.Status.Supported, removal.status)
        assertEquals(setOf(0, 3), freed.ints.toSet())
        assertEquals(BigFraction.MINUS_ONE, removal.originCutoff?.value)
    }

    @Test
    fun `foreign finite models objectives and centers should retire numerical guidance`() {
        val funding = SourceLpFunding(1_000_000, 4_000_000).apply { grant(1_000_000) }
        val publisher = SourceNumericalFeedback(funding, Cancellation.Never)
        val acquisition = LinearAuthority(SourceLpBudget(funding = funding), retainLp = true).use {
            publisher.acquire(it, source, cuts = false)
        }
        for (edit in listOf("model", "objective", "center")) {
            val removal = FiniteNumericalRemoval(problem, objective, source, funding,
                FiniteNumericalRemoval.Signal.Disagreement)
            removal.accept(center, Assumptions.None, source, null, acquisition.feedback)

            removal.destroy(Random(17),
                if (edit == "model") Problem(0, 4, Array(4) { IntDomain(0, 1) }, emptyList()).bake() else problem,
                if (edit == "center") center.copy() else center,
                if (edit == "objective") objective.copy() else objective, 0.5)

            assertEquals(FiniteNumericalRemoval.Status.Incompatible, removal.status)
            assertNull(removal.originScope)
        }
    }

    @Test
    fun `caller pins should remain fixed in supported numerical removal`() {
        val funding = SourceLpFunding(1_000_000, 4_000_000).apply { grant(1_000_000) }
        val pins = Assumptions.None.withInt(2, 0)
        val original = source.atScope(source.scope.copy(condition = pins.sourceCondition()))
        val publisher = SourceNumericalFeedback(funding, Cancellation.Never)
        val acquisition = LinearAuthority(SourceLpBudget(funding = funding), retainLp = true).use {
            publisher.acquire(it, original, cuts = false)
        }
        val removal = FiniteNumericalRemoval(problem, objective, original, funding,
            FiniteNumericalRemoval.Signal.Disagreement, pins)

        removal.accept(center, pins, original, null, acquisition.feedback)
        val freed = removal.destroy(Random(17), problem, center, objective, 1.0, pins, Cancellation.Never)

        assertEquals(FiniteNumericalRemoval.Status.Supported, removal.status)
        assertEquals(setOf(0, 1, 3), freed.ints.toSet())
    }

    @Test
    fun `numerical selections should close through supplied definitions`() {
        val funding = SourceLpFunding(1_000_000, 4_000_000).apply { grant(1_000_000) }
        val publisher = SourceNumericalFeedback(funding, Cancellation.Never)
        val acquisition = LinearAuthority(SourceLpBudget(funding = funding), retainLp = true).use {
            publisher.acquire(it, source, cuts = false)
        }
        val network = InvariantNetwork(listOf(DefinitionalSweep.SweepNode.IntDef(
            FunctionalObjective.Lin(3, 1, longArrayOf(1), arrayOf(FunctionalObjective.Operand.v(0)), 1),
            intArrayOf(0))), 4, 0)
        val removal = FiniteNumericalRemoval(problem, objective, source, funding,
            FiniteNumericalRemoval.Signal.Disagreement, definitions = network)
        removal.accept(center, Assumptions.None, source, null, acquisition.feedback)

        val freed = removal.destroy(Random(17), problem, center, objective, 0.25)

        assertEquals(FiniteNumericalRemoval.Status.Supported, removal.status)
        assertEquals(setOf(0, 3), freed.ints.toSet())
    }

    @Test
    fun `a pinned dependent output should decline the entire numerical selection`() {
        val model = Problem(0, 4, Array(4) { IntDomain(0, 1) },
            listOf(Linear(longArrayOf(1, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0L))).bake()
        val incumbent = Sample(booleanArrayOf(), longArrayOf(1, 0, 1, 0))
        val pins = Assumptions.None.withInt(2, 1)
        val original = requireNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            Cancellation.Never)).let { it.atScope(it.scope.copy(condition = pins.sourceCondition())) }
        val funding = SourceLpFunding(1_000_000, 4_000_000).apply { grant(1_000_000) }
        val publisher = SourceNumericalFeedback(funding, Cancellation.Never)
        val acquisition = LinearAuthority(SourceLpBudget(funding = funding), retainLp = true).use {
            publisher.acquire(it, original, cuts = false)
        }
        val network = InvariantNetwork(listOf(DefinitionalSweep.SweepNode.IntDef(FunctionalObjective.Plus(2,
            FunctionalObjective.Operand.v(0), FunctionalObjective.Operand.v(1)), intArrayOf(0, 1))), 4, 0)
        val removal = FiniteNumericalRemoval(model, objective, original, funding,
            FiniteNumericalRemoval.Signal.Disagreement, pins, network)
        removal.accept(incumbent, pins, original, null, acquisition.feedback)
        assertEquals(FiniteNumericalRemoval.Status.Supported, removal.status)

        val freed = removal.destroy(Random(17), model, incumbent, objective, 0.25, pins, Cancellation.Never)

        assertTrue(freed.isEmpty)
        assertEquals(FiniteNumericalRemoval.Status.ClosureDeclined, removal.status)
        assertEquals("BlockedPin", removal.unavailableReason)
        assertNull(removal.originScope)
    }

    @Test
    fun `exhausted funding should decline numerical selection without partial freedoms`() {
        val funding = SourceLpFunding(1_000_000, 4_000_000).apply { grant(1_000_000) }
        val publisher = SourceNumericalFeedback(funding, Cancellation.Never)
        val acquisition = LinearAuthority(SourceLpBudget(funding = funding), retainLp = true).use {
            publisher.acquire(it, source, cuts = false)
        }
        val removal = FiniteNumericalRemoval(problem, objective, source, funding,
            FiniteNumericalRemoval.Signal.Disagreement)
        removal.accept(center, Assumptions.None, source, null, acquisition.feedback)
        funding.charge(1_000_000)

        val freed = removal.destroy(Random(17), problem, center, objective, 0.5)

        assertEquals(FiniteNumericalRemoval.Status.Funding, removal.status)
        assertTrue(freed.ints.isEmpty() && freed.bools.isEmpty())
        assertNull(removal.originScope)
    }

    @Test
    fun `cancellation should decline numerical selection and release the snapshot`() {
        var cancelled = false
        val funding = SourceLpFunding(1_000_000, 4_000_000).apply { grant(1_000_000) }
        val publisher = SourceNumericalFeedback(funding, Cancellation.Never)
        val acquisition = LinearAuthority(SourceLpBudget(funding = funding), retainLp = true).use {
            publisher.acquire(it, source, cuts = false)
        }
        val removal = FiniteNumericalRemoval(problem, objective, source, funding,
            FiniteNumericalRemoval.Signal.Disagreement)
        removal.accept(center, Assumptions.None, source, null, acquisition.feedback,
            cancellation = Cancellation { cancelled })
        cancelled = true

        val freed = removal.destroy(Random(17), problem, center, objective, 0.5)

        assertEquals(FiniteNumericalRemoval.Status.Cancelled, removal.status)
        assertTrue(freed.ints.isEmpty() && freed.bools.isEmpty())
        assertNull(removal.originScope)
    }

    @Test
    fun `the selection call cancellation should interrupt ranking without partial freedoms`() {
        val funding = SourceLpFunding(1_000_000, 4_000_000).apply { grant(1_000_000) }
        val publisher = SourceNumericalFeedback(funding, Cancellation.Never)
        val acquisition = LinearAuthority(SourceLpBudget(funding = funding), retainLp = true).use {
            publisher.acquire(it, source, cuts = false)
        }
        val removal = FiniteNumericalRemoval(problem, objective, source, funding,
            FiniteNumericalRemoval.Signal.Disagreement)
        removal.accept(center, Assumptions.None, source, null, acquisition.feedback)
        var polls = 0

        val freed = removal.destroy(Random(17), problem, center, objective, 0.5,
            Assumptions.None, Cancellation { ++polls >= 3 })

        assertTrue(freed.isEmpty)
        assertEquals(FiniteNumericalRemoval.Status.Cancelled, removal.status)
        assertNull(removal.originScope)
    }
}
