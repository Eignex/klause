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
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.FinitePipelinePreparation
import com.eignex.klause.solver.pipeline.PreparedSourcePlan
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.ExecutionControl
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MpsLpAdmissionTest {
    @ParameterizedTest
    @ValueSource(ints = [Int.MIN_VALUE, 0, 121, Int.MAX_VALUE])
    fun `preparation deadlines reject values outside the bounded diagnostic range`(seconds: Int) {
        assertFailsWith<IllegalArgumentException> { MpsLpAdmission.PreparationLimits(seconds, 1L) }
    }

    @ParameterizedTest
    @ValueSource(longs = [Long.MIN_VALUE, 0L, 6_600_000_001L, Long.MAX_VALUE])
    fun `preparation work rejects values outside the bounded diagnostic range`(work: Long) {
        assertFailsWith<IllegalArgumentException> { MpsLpAdmission.PreparationLimits(20, work) }
    }

    @ParameterizedTest
    @ValueSource(ints = [Int.MIN_VALUE, 0, 65, Int.MAX_VALUE])
    fun `input limits reject values outside the bounded diagnostic range`(mebibytes: Int) {
        assertFailsWith<IllegalArgumentException> { MpsLpAdmission.inputByteLimit(mebibytes) }
    }

    @Test
    fun `a resource declined plan cannot report complete preparation from a baked model`() {
        val problem = Problem.of(0, 1, arrayOf(IntDomain(0, 2)),
            listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1))).bake()
        val cancelled = ExecutionControl(Cancellation { true })
        val plan = problem.prepareExecutionPlan(preferFinite = true, cancellation = cancelled)
        val preparation = FinitePipelinePreparation(problem, null, { it }, null)
        preparation.javaClass.methods.single { it.name.startsWith("setExecutionPlan") }
            .invoke(preparation, plan)

        val report = MpsLpAdmission.preparationStatus(preparation, PresolveBudget(100L), ExecutionControl.Never)

        assertTrue(assertNotNull(report["preparedModelBaked"]).jsonPrimitive.boolean)
        assertEquals("RESOURCE", assertNotNull(report["preparedPlanDecline"]).jsonPrimitive.content)
        assertFalse(assertNotNull(report["preparationCancelled"]).jsonPrimitive.boolean)
        assertEquals(0L, assertNotNull(report["preparationWork"]).jsonPrimitive.long)
        assertFalse(assertNotNull(report["preparationComplete"]).jsonPrimitive.boolean)
    }

    @Test
    fun `component cost accounts for grouped numerical owners`() {
        val problem = Problem.of(0, 4, Array(4) { IntDomain(0, 3) }, listOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 3),
            Linear(intArrayOf(1), intArrayOf(2), LinearOp.GE, 1),
        ))

        val report = MpsLpAdmission.shape(problem, LinearObjective(intCoefficients = LongArray(4) { 1L }),
            false, ExecutionControl.Never)

        assertEquals(21L, assertNotNull(report["componentDenseProxy"]).jsonPrimitive.long)
        assertEquals(13L, assertNotNull(report["naturalComponentDenseProxy"]).jsonPrimitive.long)
        assertEquals(1L, assertNotNull(report["numericalOwners"]).jsonPrimitive.long)
        assertEquals(64L, assertNotNull(report["ownerMergeWork"]).jsonPrimitive.long)
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
