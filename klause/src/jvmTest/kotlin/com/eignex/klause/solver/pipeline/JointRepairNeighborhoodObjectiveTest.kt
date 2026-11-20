package com.eignex.klause.solver.pipeline

import com.eignex.klause.RuntimeInitializedTest
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.parseBigInt
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

@RunWith(Parameterized::class)
class JointRepairNeighborhoodObjectiveTest(private val kind: String) : RuntimeInitializedTest() {
    @Test
    fun `private support should omit only recognized leaf objective definitions`() {
        val integers = List(3) { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val boolean = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val coefficients = integers.associateWith { BigFraction.ONE } + (real to BigFraction.MINUS_ONE)
        val rhs = when (kind) {
            "wide" -> BigFraction.of(parseBigInt("1" + "0".repeat(1500)), BIG_ONE)
            "constant" -> BigFraction.ofLong(3)
            else -> BigFraction.ZERO
        }
        val guards = if (kind == "guarded") mapOf(0 to true) else emptyMap()
        val pins = if (kind == "pinned") mapOf(integers[0] to BigFraction.ZERO) else emptyMap()
        val sourceRows = linkedMapOf(
            "edge" to SourcePremise(SourceInequality(mapOf(integers[0] to BigFraction.ONE,
                integers[1] to BigFraction.ONE), BigFraction.ONE)),
            "definition-upper" to SourcePremise(SourceInequality(coefficients, rhs, kind == "strict"), guards, pins),
        )
        if (kind != "one-sided") sourceRows["definition-lower"] = SourcePremise(SourceInequality(
            coefficients.mapValues { (coordinate, value) ->
                if (kind == "coefficients" && coordinate == integers[2]) value else value.negated()
            }, if (kind == "rhs") BigFraction.ONE else rhs.negated()), guards, pins)
        if (kind == "third") sourceRows["other"] = SourcePremise(SourceInequality(
            mapOf(real to BigFraction.ONE, integers[2] to BigFraction.ONE), BigFraction.ONE))
        val sense = if (kind == "maximized") ObjectiveSense.MAXIMIZE else ObjectiveSense.MINIMIZE
        val source = SourceProofContext(SourceScope(SourceRevision("objective-$kind", "exact"),
            objective = SourceObjective("cost", sense)),
            integers.toSet() + real + boolean, sourceRows,
            objective = if (kind == "multiple-costs") mapOf(real to BigFraction.ONE, integers[0] to BigFraction.ONE)
            else mapOf(real to if (kind == "maximized") BigFraction.MINUS_ONE else BigFraction.ONE),
            objectiveConstant = if (kind == "constant") BigFraction.ofLong(5) else BigFraction.ZERO)
        val selector = assertNotNull(JointRepairNeighborhood.prepare(source, 3, 1, ExecutionControl.Never,
            privateObjective = true))
        val funding = SourceLpFunding(1_000_000, if (kind == "unfunded") 2_000 else 1_000_000)
            .also { it.grant(1_000_000) }

        val result = assertIs<JointRepairNeighborhood.AdvisorySelection.Ready>(selector.advisoryNeighborhood(
            List(3) { BigFraction.ZERO }, 0, BigFraction.ONE, SourceLpBudget(funding = funding),
            ExecutionControl.Never, expand = true))

        val expected = if (kind in setOf("recognized", "constant", "maximized")) mapOf(2 to BigFraction.ZERO)
        else emptyMap()
        assertEquals(expected, result.condition.lower)
        assertEquals(result.condition.lower, result.condition.upper)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "definition={0}")
        fun cases() = listOf("recognized", "third", "guarded", "pinned", "strict", "one-sided",
            "coefficients", "rhs", "multiple-costs", "wide", "unfunded", "constant", "maximized").map { arrayOf(it) }
    }
}
