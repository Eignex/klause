package com.eignex.klause.bench.profile

import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toLinearSourceProblem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MpsLpAdmissionTest {
    @Test
    fun `prepared MPS objective should retain its coefficients and minimization direction`() {
        for ((sense, expected) in listOf("MIN" to 7L, "MAX" to -7L)) {
            val source = assertNotNull(Mps.parse("""
                NAME objective
                OBJSENSE
                 $sense
                ROWS
                 N OBJ
                 L LIM
                COLUMNS
                 X OBJ 7 LIM 1
                RHS
                 RHS1 LIM 1
                BOUNDS
                 BV BND X
                ENDATA
            """.trimIndent()).toLinearSourceProblem())
            val invocation = SourceSetup.prepare(SourceSetupRequest(
                SourceAttachment(source, ProblemSettings()), ExecutionOperation.OPTIMIZE, FiniteEngine.BACKTRACK,
                maximize = source.objective?.sense == ObjectiveSense.MAXIMIZE,
            ))

            val objective = assertNotNull(MpsLpAdmission.preparedObjective(assertNotNull(invocation.preparedPlan)))

            assertEquals(expected, objective.evaluateLong(Sample(BooleanArray(0), longArrayOf(1))))
        }
    }
}
