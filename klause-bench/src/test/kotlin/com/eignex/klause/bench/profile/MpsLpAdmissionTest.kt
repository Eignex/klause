package com.eignex.klause.bench.profile

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearSourceObjective
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.LinearSourceTerm
import com.eignex.klause.ir.LinearSourceVariable
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.PreparedSourcePlan
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MpsLpAdmissionTest {
    @Test
    fun `prepared source objective should retain its coefficients and minimization direction`() {
        val working = LinearObjective(intCoefficients = longArrayOf(7))
        val model = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyList())
        val initial = model.prepareExecutionPlan(working, preferFinite = true)
        val bind = initial.javaClass.methods.single { it.name.startsWith("withLinearSource") }
        for ((sense, expected) in listOf(ObjectiveSense.MINIMIZE to 7L, ObjectiveSense.MAXIMIZE to -7L)) {
            val source = LinearSourceProblem(
                listOf(LinearSourceVariable("x", true, SemanticRational.ZERO, SemanticRational.ONE)),
                emptyList(),
                LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ofLong(7))), sense = sense),
            )
            val plan = bind.invoke(initial, source, listOf(0), emptyList<Int>(), working) as PreparedSourcePlan

            val objective = assertNotNull(MpsLpAdmission.preparedObjective(plan))

            assertEquals(expected, objective.evaluateLong(Sample(BooleanArray(0), longArrayOf(1))))
        }
    }
}
