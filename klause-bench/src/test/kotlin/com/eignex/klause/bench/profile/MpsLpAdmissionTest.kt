package com.eignex.klause.bench.profile

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearSourceObjective
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.LinearSourceTerm
import com.eignex.klause.ir.LinearSourceVariable
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipelinePreparation
import com.eignex.klause.solver.pipeline.PreparedSourcePlan
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.util.ExecutionControl
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MpsLpAdmissionTest {
    @Test
    fun `interrupted companion inspection should leave structural admission unknown`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", true, SemanticRational.ZERO, SemanticRational.ONE)),
            emptyList(), LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ONE))),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.OPTIMIZE, FiniteEngine.MIXED))
        val plan = assertNotNull(invocation.preparedPlan)
        val preparation = FinitePipelinePreparation(assertNotNull(plan.model).bake(),
            MpsLpAdmission.preparedObjective(plan), { it }, null)

        val report = MpsLpAdmission.companionAdmission(invocation, preparation, ExecutionControl { true })

        assertEquals(JsonNull, report["automaticPolicyStructurallyAdmitted"])
    }

    @Test
    fun `companion inspection should report the missing continuous fragment`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", true, SemanticRational.ZERO, SemanticRational.ONE)),
            emptyList(), LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ONE))),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.OPTIMIZE, FiniteEngine.MIXED))
        val plan = assertNotNull(invocation.preparedPlan)
        val preparation = FinitePipelinePreparation(assertNotNull(plan.model).bake(),
            MpsLpAdmission.preparedObjective(plan), { it }, null)

        val report = MpsLpAdmission.companionAdmission(invocation, preparation, ExecutionControl.Never)

        assertTrue(assertNotNull(report["declines"]).jsonArray.any {
            it.jsonPrimitive.content == "integer-continuous-fragment-required"
        })
    }

    @Test
    fun `root inspection retains an emitted row with appended constructor defaults`() {
        val problem = Problem.of(0, 1, arrayOf(IntDomain(0, 2)),
            listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1)))
        val objective = LinearObjective(intCoefficients = longArrayOf(1))

        val relaxation = MpsLpAdmission.rootRelaxation(problem, objective, false, ExecutionControl.Never)
        val model = relaxation.javaClass.getMethod("getModel").invoke(relaxation)

        assertEquals(1, model.javaClass.getMethod("getM").invoke(model))
    }

    @ParameterizedTest
    @EnumSource(value = EngineMix::class, names = ["MIXED", "BACKTRACK"])
    fun `recipe inspection accepts the current portfolio fact API`(mix: EngineMix) {
        val problem = Problem.of(0, 1, arrayOf(IntDomain(0, 2)),
            listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1))).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1))

        val report = MpsLpAdmission.recipes(problem, objective, mix)

        assertTrue(assertNotNull(report["arms"]).jsonArray.isNotEmpty())
    }

    @Test
    fun `prepared source objective should retain its coefficients and minimization direction`() {
        val working = LinearObjective(intCoefficients = longArrayOf(7))
        val model = Problem.of(0, 1, arrayOf(IntDomain(0, 1)), emptyList())
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
