package com.eignex.klause.bench.profile

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.toLinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class TrajectoryModelTest {
    private val source = """
        NAME trajectory
        ROWS
         N cost
         G lower
        COLUMNS
         x cost 2 lower 1
         y cost 3 lower 1
        RHS
         rhs lower 1
        BOUNDS
         BV bound x
         BV bound y
        ENDATA
    """.trimIndent()

    @Test
    fun `MPS trajectories should retain declared integer coordinates and objective`() {
        val parsed = trajectoryModel(source.encodeToByteArray(), "mps")

        assertEquals(SourceFormat.MPS, parsed.semantics.effective.format)
        assertEquals(mapOf("x" to 0, "y" to 1), parsed.intVarNames)
        assertEquals(ObjectiveSense.MINIMIZE, parsed.sense)
        assertEquals(0, parsed.problem.numRealVars)
        assertEquals(2.0, assertNotNull(parsed.objective).toLinearObjective()
            .evaluate(Sample(booleanArrayOf(), longArrayOf(1, 0))))
    }

    @ParameterizedTest
    @ValueSource(strings = ["continuous", "maximize"])
    fun `unsupported MPS trajectories should decline before finite preparation`(variant: String) {
        val unsupported = if (variant == "continuous") source.replace("BV bound y", "UP bound y 1")
        else source.replace("ROWS", "OBJSENSE\n MAX\nROWS")

        assertFailsWith<IllegalArgumentException> {
            trajectoryModel(unsupported.encodeToByteArray(), "mps")
        }
    }
}
