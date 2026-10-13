package com.eignex.klause.solver.incumbent

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Bits
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EngineInvocationTest {
    private val source = Problem(1, 1, arrayOf(IntDomain(0, 9)), emptyArray())
    private val objective = LinearObjective(intCoefficients = longArrayOf(1))
    private val plan = source.prepareExecutionPlan(objective)
    private val model = ModelIdentity.of(Any(), objective)

    @Test
    fun `runtime binding retains exact source real windows`() {
        val original = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(3.0))
        val coordinate = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val condition = SourceCondition(lower = mapOf(coordinate to BigFraction.ONE),
            upper = mapOf(coordinate to BigFraction.ofLong(2)))
        val scope = SourceScope(SourceRevision("original", "klause-ir-exact-v1"), condition)
        val binding = EngineInvocation(original.prepareExecutionPlan(), ModelIdentity.of(original),
            scope = scope, condition = condition)
        val point = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(1.0), exactReals = listOf(BigFraction.ONE))

        val checked = binding.checkWitness(point, Cancellation.Never)

        assertIs<SourceEvidenceCheck.Checked>(checked)
    }

    @Test
    fun `runtime binding rejects points outside exact source real windows`() {
        val original = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(3.0))
        val coordinate = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val condition = SourceCondition(lower = mapOf(coordinate to BigFraction.ONE),
            upper = mapOf(coordinate to BigFraction.ofLong(2)))
        val binding = EngineInvocation(original.prepareExecutionPlan(), ModelIdentity.of(original),
            condition = condition)

        for (value in listOf(BigFraction.ZERO, BigFraction.ofLong(3))) {
            val point = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(value.toDouble()),
                exactReals = listOf(value))

            assertIs<SourceEvidenceCheck.Rejected>(binding.checkWitness(point, Cancellation.Never))
        }
    }

    @Test
    fun `runtime binding checks arbitrary precision integer source restrictions`() {
        val original = Problem(0, IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0),
            Bits(1), Bits(1)), emptyArray())
        val wide = parseBigInt("9223372036854775808")
        val coordinate = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val exact = BigFraction.of(wide, BIG_ONE)
        val condition = SourceCondition(lower = mapOf(coordinate to exact), upper = mapOf(coordinate to exact))
        val binding = EngineInvocation(original.prepareExecutionPlan(), ModelIdentity.of(original),
            condition = condition)
        val point = Sample(booleanArrayOf(), longArrayOf(), exactInts = listOf(wide))

        val checked = binding.checkWitness(point, Cancellation.Never)

        assertIs<SourceEvidenceCheck.Checked>(checked)
    }

    @Test
    fun `runtime binding rejects an integer below an arbitrary precision source bound`() {
        val coordinate = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val lower = BigFraction.of(parseBigInt("9223372036854775808"), BIG_ONE)
        val binding = EngineInvocation(plan, model, objective,
            condition = SourceCondition(lower = mapOf(coordinate to lower)))
        val point = Sample(booleanArrayOf(false), longArrayOf(Long.MAX_VALUE))

        val checked = binding.checkWitness(point, Cancellation.Never)

        assertIs<SourceEvidenceCheck.Rejected>(checked)
    }

    @Test
    fun `runtime binding cannot omit explicit source pins from an exact condition`() {
        val assumptions = listOf(Assumptions(bools = mapOf(0 to true)), Assumptions(ints = mapOf(0 to 3L)))

        for (pins in assumptions) {
            assertFailsWith<IllegalArgumentException> {
                EngineInvocation(plan, model, objective, sourceAssumptions = pins, condition = SourceCondition())
            }
        }
    }

    @Test
    fun `runtime binding cannot capture a different exact source condition`() {
        val coordinate = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val condition = SourceCondition(lower = mapOf(coordinate to BigFraction.ONE))
        val scope = SourceScope(SourceRevision("original", "klause-ir-exact-v1"),
            objective = SourceObjective("objective", ObjectiveSense.MINIMIZE))

        assertFailsWith<IllegalArgumentException> {
            EngineInvocation(plan, model, objective, scope = scope, condition = condition)
        }
    }

    @Test
    fun `runtime binding refuses undeclared exact source coordinates`() {
        val conditions = listOf(
            SourceCondition(bools = mapOf(1 to true)),
            SourceCondition(lower = mapOf(SourceCoordinate(SourceCoordinate.Kind.Integer, 1) to BigFraction.ONE)),
            SourceCondition(upper = mapOf(SourceCoordinate(SourceCoordinate.Kind.Real, 0) to BigFraction.ONE)),
        )

        for (condition in conditions) {
            assertFailsWith<IllegalArgumentException> { EngineInvocation(plan, model, objective, condition = condition) }
        }
    }

    @Test
    fun `runtime matching retains exact source restrictions without revision capture`() {
        val coordinate = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val condition = SourceCondition(lower = mapOf(coordinate to BigFraction.ONE))
        val restricted = EngineInvocation(plan, model, objective, condition = condition)
        val root = EngineInvocation(plan, model, objective)

        assertFalse(restricted.matches(root))
        assertTrue(restricted.matches(EngineInvocation(plan, model, objective, condition = condition)))
    }

    @Test
    fun `cancelled exact source condition checking withholds runtime witness admission`() {
        val coordinate = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val condition = SourceCondition(lower = mapOf(coordinate to BigFraction.ONE))
        val binding = EngineInvocation(plan, model, objective, condition = condition)
        val point = Sample(booleanArrayOf(false), longArrayOf(3))

        val checked = binding.checkWitness(point, Cancellation { true })

        assertIs<SourceEvidenceCheck.Unavailable>(checked)
    }

    @Test
    fun `approximate real values cannot establish an exact runtime condition`() {
        val original = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(3.0))
        val coordinate = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val binding = EngineInvocation(original.prepareExecutionPlan(), ModelIdentity.of(original),
            condition = SourceCondition(lower = mapOf(coordinate to BigFraction.ONE)))
        val point = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(1.0))

        val checked = binding.checkWitness(point, Cancellation.Never)

        assertIs<SourceEvidenceCheck.Unavailable>(checked)
    }

    @Test
    fun `runtime binding retains caller pins independently of mutable assumption arrays`() {
        val assumptions = Assumptions(bools = mapOf(0 to true), ints = mapOf(0 to 3L))
        val binding = EngineInvocation(plan, model, objective, assumptions)
        assumptions.boolValues[0] = false
        assumptions.intValues[0] = 4
        val exported = binding.assumptions
        exported.intValues[0] = 5
        val point = Sample(booleanArrayOf(true), longArrayOf(3))

        val checked = binding.checkWitness(point, Cancellation.Never)

        assertIs<SourceEvidenceCheck.Checked>(checked)
    }

    @Test
    fun `runtime binding cannot import another plan model objective or condition`() {
        val binding = EngineInvocation(plan, model, objective)
        val otherObjective = LinearObjective(intCoefficients = longArrayOf(1))
        val variants = listOf(
            EngineInvocation(source.prepareExecutionPlan(objective), model, objective),
            EngineInvocation(plan, ModelIdentity.of(Any(), objective), objective),
            EngineInvocation(plan, model.forObjective(otherObjective), otherObjective),
            EngineInvocation(plan, model, objective, Assumptions(bools = mapOf(0 to true))),
            EngineInvocation(plan, model, objective, executionAssumptions = Assumptions().withIntHole(0, 3)),
        )

        for (other in variants) assertFalse(binding.matches(other))
        assertTrue(binding.matches(EngineInvocation(plan, model, objective)))
    }

    @Test
    fun `execution deductions remain part of runtime witness admission`() {
        val point = Sample(booleanArrayOf(true), longArrayOf(3))
        val variants = listOf(
            Assumptions.None.withTightenedMin(0, 4),
            Assumptions.None.withTightenedMax(0, 2),
            Assumptions.None.withIntHole(0, 3),
        )

        for (assumptions in variants) {
            val binding = EngineInvocation(plan, model, objective, executionAssumptions = assumptions)

            assertIs<SourceEvidenceCheck.Rejected>(binding.checkWitness(point, Cancellation.Never))
        }
    }

    @Test
    fun `weaker execution assumptions cannot admit a point outside the source query`() {
        val point = Sample(booleanArrayOf(false), longArrayOf(3))
        val variants = listOf(
            Assumptions(bools = mapOf(0 to true)),
            Assumptions.None.withTightenedMin(0, 4),
            Assumptions.None.withIntHole(0, 3),
        )

        for (assumptions in variants) {
            val binding = EngineInvocation(plan, model, objective, assumptions, Assumptions.None)

            assertIs<SourceEvidenceCheck.Rejected>(binding.checkWitness(point, Cancellation.Never))
        }
    }
}
