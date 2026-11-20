package com.eignex.klause.bench.profile

import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toLinearSourceProblem
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.util.ExecutionControl
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SourceCutAdmissionTest {
    @Test
    fun `diagnosis should distinguish canonical binary rejection from widened coordinates`() {
        val text = """
            NAME canonical
            ROWS
             N cost
             E definition
            COLUMNS
             x definition -2
             r definition 1
             y cost 1
            RHS
             rhs definition 0
            BOUNDS
             BV bounds x
            ENDATA
        """.trimIndent()
        val source = assertNotNull(Mps.parse(text).toLinearSourceProblem())

        val report = SourceCutAdmission.inspect(source, ProblemSettings(), ExecutionControl.Never)

        assertEquals("true", report["complete"]?.jsonPrimitive?.content)
        assertEquals("canonical-binary-integers-required", report["firstDecline"]?.jsonPrimitive?.content)
        assertEquals("2", report["nominal"]?.jsonObject?.get("integers")?.jsonPrimitive?.content)
        assertEquals("1", report["accepted"]?.jsonObject?.get("integers")?.jsonPrimitive?.content)
    }

    @Test
    fun `diagnosis should identify compatible binary and continuous coordinates`() {
        val text = """
            NAME compatible
            ROWS
             N cost
             G demand
            COLUMNS
             x demand 1
             y demand 1 cost 1
            RHS
             rhs demand 1.5
            BOUNDS
             BV bounds x
            ENDATA
        """.trimIndent()
        val source = assertNotNull(Mps.parse(text).toLinearSourceProblem())

        val report = SourceCutAdmission.inspect(source, ProblemSettings(), ExecutionControl.Never)

        assertEquals("true", report["sharesToleranceCoordinates"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, report["firstDecline"])
    }

    @Test
    fun `a stopped diagnosis should leave setup eligibility unknown`() {
        val text = """
            NAME stopped
            ROWS
             N cost
            COLUMNS
             x cost 1
            RHS
            BOUNDS
             BV bounds x
            ENDATA
        """.trimIndent()
        val source = assertNotNull(Mps.parse(text).toLinearSourceProblem())

        val report = SourceCutAdmission.inspect(source, ProblemSettings(), ExecutionControl { true })

        assertEquals("false", report["complete"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, report["firstDecline"])
    }
}
