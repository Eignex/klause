package com.eignex.klause.bench.profile

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals

class SourceMappingAdmissionTest {
    private val model = Problem.of(0, 1, arrayOf(IntDomain(0, 1)), emptyList())
    private val objective = LinearObjective(intCoefficients = longArrayOf(1))
    private val authority = Class.forName("com.eignex.klause.solver.pipeline.LinearToleranceAuthority")
        .constructors.single().newInstance(model, model, objective)

    @ParameterizedTest
    @ValueSource(strings = ["constant", "integer"])
    fun `diagnosis should name the changed prepared objective`(part: String) {
        val costs = if (part == "constant") objective.copy(constant = 1L)
            else objective.copy(intCoefficients = longArrayOf(2))

        val report = SourceMappingAdmission.inspect(authority, null, model, costs, ExecutionControl.Never)

        assertEquals("true", report["complete"]?.jsonPrimitive?.content)
        assertEquals(if (part == "constant") "objective-constant-changed" else "objective-IntCoefficientValues-changed",
            report["firstDecline"]?.jsonPrimitive?.content)
    }

    @Test
    fun `diagnosis should distinguish a missing mapping from a changed objective`() {
        val report = SourceMappingAdmission.inspect(authority, null, model, objective, ExecutionControl.Never)

        assertEquals("source-mapping-required", report["firstDecline"]?.jsonPrimitive?.content)
    }

    @Test
    fun `an identity mapping should satisfy all accepted authority guards`() {
        val guaranteesType = Class.forName("com.eignex.klause.presolve.TransformationGuarantees")
        val guarantees = NumericalResidualProfile.call(guaranteesType.getField("Companion").get(null), "getIDENTITY")
        val rebuildType = Class.forName("com.eignex.klause.presolve.SourceRebuilds")
        val rebuild = NumericalResidualProfile.call(rebuildType.getField("Companion").get(null), "getNONE")
        val adjustment = Class.forName("com.eignex.klause.presolve.ObjectiveAdjustment").constructors
            .single { it.parameterCount == 2 }.newInstance(BigFraction.ONE, BigFraction.ZERO)
        val mapping = Class.forName("com.eignex.klause.presolve.SourceMapping").constructors
            .single { it.parameterCount == 9 }
            .newInstance(model, model, guarantees, rebuild, adjustment, emptyList<Any>(), null, false, null)

        val report = SourceMappingAdmission.inspect(authority, mapping, model, objective, ExecutionControl.Never)

        assertEquals("true", report["complete"]?.jsonPrimitive?.content)
        assertEquals("true", report["compatible"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, report["firstDecline"])
    }

    @Test
    fun `a stopped mapping diagnosis should leave compatibility unknown`() {
        val report = SourceMappingAdmission.inspect(authority, null, model, objective, ExecutionControl { true })

        assertEquals("false", report["complete"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, report["firstDecline"])
        assertEquals(JsonNull, report["compatible"])
    }
}
