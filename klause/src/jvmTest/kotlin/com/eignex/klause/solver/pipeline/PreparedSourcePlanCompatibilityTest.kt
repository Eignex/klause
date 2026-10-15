package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PreparedSourcePlanCompatibilityTest {
    @Test
    fun `legacy constructor descriptors retain finite ownership and default mappings`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)), emptyArray())
        val plan = source.prepareExecutionPlan(preferFinite = true)
        val type = PreparedSourcePlan::class.java
        val parameters = arrayOf(Any::class.java, Problem::class.java, PreparedExecutionKind::class.java,
            ComponentPlan::class.java, SharedPolynomialModel::class.java, List::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            LinearObjective::class.java, Boolean::class.javaPrimitiveType, PreparedPlanDecline::class.java,
            List::class.java, List::class.java, Set::class.java, QuadraticObjectiveSpec::class.java,
            ObjectiveSense::class.java, PreparedFactorProjections::class.java)
        val arguments = arrayOf<Any?>(source, source, PreparedExecutionKind.FINITE_LINEAR, plan.componentPlan,
            null, plan.components, 0, 1, 0, null, false, null, listOf(0), emptyList<Int>(),
            finiteOperations, null, ObjectiveSense.MINIMIZE, plan.factorProjections)

        val direct = type.getConstructor(*parameters).newInstance(*arguments) as PreparedSourcePlan
        val defaulted = type.getConstructor(*parameters, Int::class.javaPrimitiveType,
            Class.forName("kotlin.jvm.internal.DefaultConstructorMarker"))
            .newInstance(*arguments, ((1 shl 18) - 1) xor ((1 shl 9) - 1), null) as PreparedSourcePlan

        for (captured in listOf(direct, defaulted)) {
            assertSame(source, captured.originalSource)
            assertSame(plan.componentPlan, captured.componentPlan)
            assertEquals(0, captured.integerCoordinate(0))
            assertNull(captured.objective)
            assertTrue(captured.capabilities.admission(FiniteEngine.BACKTRACK).runnable)
        }
    }

    @Test
    fun `legacy objective default descriptors retain source sense and captured structure`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)), emptyArray())
        val plan = source.prepareExecutionPlan(preferFinite = true)
        val type = PreparedSourcePlan::class.java
        val linear = LinearObjective(intCoefficients = longArrayOf(1), constant = 7)
        val quadratic = QuadraticObjectiveSpec(emptyList())

        val boundLinear = type.getMethod("withObjective\$default", type, LinearObjective::class.java,
            Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, Any::class.java)
            .invoke(null, plan, linear, true, 2, null) as PreparedSourcePlan
        val boundQuadratic = type.getMethod("withObjective\$default", type, QuadraticObjectiveSpec::class.java,
            ObjectiveSense::class.java, Int::class.javaPrimitiveType, Any::class.java)
            .invoke(null, boundLinear, quadratic, ObjectiveSense.MAXIMIZE, 2, null) as PreparedSourcePlan

        assertSame(linear, boundLinear.objective)
        assertEquals(false, boundLinear.maximize)
        assertSame(quadratic, boundQuadratic.quadraticObjective)
        assertEquals(ObjectiveSense.MINIMIZE, boundQuadratic.quadraticSense)
        assertNull(boundQuadratic.objective)
        assertSame(plan.componentPlan, boundQuadratic.componentPlan)
        assertSame(source, boundQuadratic.originalSource)
    }
}
