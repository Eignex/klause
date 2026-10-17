package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.projection.FloatGridDecline
import com.eignex.klause.solver.projection.FloatGridVariable
import com.eignex.klause.theory.BuiltInEncoderProvider
import com.eignex.klause.theory.Encoder
import com.eignex.klause.theory.EncoderProvider
import com.eignex.klause.theory.EncoderSupport
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SelectedFloatGridTest {
    @Test
    fun `nonfinite enumeration metadata should not suppress an available automatic finite compatibility view`() {
        val source = Problem(0, 0, emptyArray<IntDomain>(), arrayOf(Linear(intArrayOf(), doubleArrayOf(),
            intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 0.5)), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
            semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Grid(3, 1000)).resolve())
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source),
            ExecutionOperation.ENUMERATE, FiniteEngine.MIXED))
        assertIs<SourceProblemRoute.OpenTheory>(invocation.route)

        assertEquals(false, SourceSetup.preservesOriginalCompleteArm(invocation))
        val selected = SourceSetup.prepareSelectedFloatGrid(invocation, listOf(FloatGridVariable(SourceRealId(0))),
            FloatGridConsumerPurpose.AUTOMATIC_FINITE_COMPATIBILITY)

        assertIs<SelectedFloatGridPreparation.Prepared>(selected)
        assertNotNull(invocation.floatProjection)
    }

    @Test
    fun `automatic grid selection should preserve an admitted original complete arm for the actual operation`() {
        for (engine in listOf(FiniteEngine.BACKTRACK, FiniteEngine.MIXED)) {
            for (operation in listOf(ExecutionOperation.SATISFY, ExecutionOperation.OPTIMIZE)) {
                val source = Problem(0, 0, emptyArray<IntDomain>(), arrayOf(Linear(intArrayOf(), doubleArrayOf(),
                    intArrayOf(0), doubleArrayOf(1.0), LinearOp.EQ, 0.25)), numRealVars = 1,
                    realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
                    semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Grid(2, 1000)).resolve())
                val objective = if (operation == ExecutionOperation.OPTIMIZE) {
                    LinearObjective(realCoefficients = doubleArrayOf(1.0))
                } else null
                val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source), operation,
                    engine, objective, maximize = objective != null))
                val canonical = invocation.preparedPlan
                assertTrue(ExecutionStrategy.COMPLETE in assertNotNull(invocation.admission).arms)

                val result = assertIs<SelectedFloatGridPreparation.Declined>(SourceSetup.prepareSelectedFloatGrid(
                    invocation, listOf(FloatGridVariable(SourceRealId(0))),
                    FloatGridConsumerPurpose.AUTOMATIC_FINITE_COMPATIBILITY))

                assertEquals(SelectedGridDecline.CONSUMER, result.cause)
                assertNull(invocation.floatProjection)
                assertSame(canonical, invocation.enginePlan)
                assertSame(invocation.route, invocation.engineRoute)
            }
        }
    }

    @Test
    fun `a declined original complete arm should preserve an available automatic private view`() {
        for (engine in listOf(FiniteEngine.BACKTRACK, FiniteEngine.MIXED, FiniteEngine.LOCAL_SEARCH)) {
            val source = Problem(0, 0, emptyArray<IntDomain>(), arrayOf(Linear(intArrayOf(), doubleArrayOf(),
                intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 0.5)), numRealVars = 1,
                realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
                semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Grid(3, 1000)).resolve())
            val providers = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
                if (factor.variables.reals.isNotEmpty()) Encoder(EncoderSupport.UNSUPPORTED)
                else BuiltInEncoderProvider.select(factor, target)
            })
            val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source),
                ExecutionOperation.SATISFY, engine, providers = providers))
            assertTrue(ExecutionStrategy.COMPLETE !in assertNotNull(invocation.admission).arms)

            assertIs<SelectedFloatGridPreparation.Prepared>(SourceSetup.prepareSelectedFloatGrid(
                invocation, listOf(FloatGridVariable(SourceRealId(0))),
                FloatGridConsumerPurpose.AUTOMATIC_FINITE_COMPATIBILITY))

            assertNotNull(invocation.floatProjection)
            assertSame(source, invocation.source.originalSource)
        }
    }

    @Test
    fun `explicit private compatibility should remain available beside an original complete mixed arm`() {
        for (engine in listOf(FiniteEngine.BACKTRACK, FiniteEngine.MIXED)) {
            val source = Problem(0, 0, emptyArray<IntDomain>(), emptyArray(), numRealVars = 1,
                realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
                semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Grid(3, 1000)).resolve())
            val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source),
                ExecutionOperation.SATISFY, engine))
            assertTrue(ExecutionStrategy.COMPLETE in assertNotNull(invocation.admission).arms)

            assertIs<SelectedFloatGridPreparation.Prepared>(SourceSetup.prepareSelectedFloatGrid(
                invocation, listOf(FloatGridVariable(SourceRealId(0))),
                FloatGridConsumerPurpose.FINITE_COMPATIBILITY))

            assertNotNull(invocation.floatProjection)
        }
    }

    @Test
    fun `automatic private selection should preserve an original source refutation`() {
        val source = Problem(0, 0, emptyArray<IntDomain>(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
            semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Grid(3, 1000)).resolve())
        val request = SourceSetupRequest(SourceAttachment(source), ExecutionOperation.SATISFY, FiniteEngine.MIXED)
        val invocation = PreparedSourceInvocation(request, SourceProblemRoute.Refuted, Cancellation.Never)

        val result = assertIs<SelectedFloatGridPreparation.Declined>(SourceSetup.prepareSelectedFloatGrid(
            invocation, listOf(FloatGridVariable(SourceRealId(0))),
            FloatGridConsumerPurpose.AUTOMATIC_FINITE_COMPATIBILITY))

        assertEquals(SelectedGridDecline.SOURCE_REFUTED, result.cause)
        assertSame(SourceProblemRoute.Refuted, invocation.engineRoute)
        assertNull(invocation.floatProjection)
    }

    @Test
    fun `an explicit private view should preserve the canonical plan and recapture its own descriptors`() {
        val relation = Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 0.5)
        val source = Problem(0, 0, emptyArray<IntDomain>(), arrayOf(relation), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
            semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Grid(3, 1000)).resolve())
        val observed = ArrayList<Any>()
        val providers = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            observed += factor
            BuiltInEncoderProvider.select(factor, target)
        })
        val attachment = SourceAttachment(source)
        val invocation = SourceSetup.prepare(SourceSetupRequest(attachment, ExecutionOperation.SATISFY,
            FiniteEngine.BACKTRACK, providers = providers))
        val canonical = assertNotNull(invocation.preparedPlan)

        val selected = assertIs<SelectedFloatGridPreparation.Prepared>(SourceSetup.prepareSelectedFloatGrid(
            invocation, listOf(FloatGridVariable(SourceRealId(0))), FloatGridConsumerPurpose.FINITE_COMPATIBILITY,
        ))
        val shape = selected.invocation.finiteShape()

        assertSame(invocation, selected.invocation)
        assertSame(attachment, invocation.source)
        assertSame(source, invocation.source.originalSource)
        assertSame(canonical, invocation.preparedPlan)
        assertSame(shape.preparedPlan, invocation.enginePlan)
        assertTrue(shape.problem !== source)
        assertSame(shape.problem, shape.preparedPlan.originalSource)
        assertSame(providers, shape.preparedPlan.factorProjections?.providers)
        assertTrue(observed.any { it === relation })
        assertTrue(observed.any { it === shape.problem.factors[0] })
        assertEquals(1, source.numRealVars)
        assertEquals(0, source.numIntVars)
        assertEquals(0, shape.problem.numRealVars)
        assertEquals(1, shape.problem.numIntVars)
    }

    @Test
    fun `ordinary source setup should not materialize an approximate grid`() {
        val source = Problem(0, 0, emptyArray<IntDomain>(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
            semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Grid(3, 1000)).resolve())

        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source),
            ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK))

        assertNull(invocation.floatGrid)
        assertSame(invocation.preparedPlan, invocation.enginePlan)
        assertEquals(0, source.numIntVars)
    }

    @Test
    fun `a grid cannot replace a finite view after its shape has escaped`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
            semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Grid(3, 1000)).resolve())
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source),
            ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK))
        val shape = invocation.finiteShape()

        assertFailsWith<IllegalArgumentException> {
            SourceSetup.prepareSelectedFloatGrid(invocation, listOf(FloatGridVariable(SourceRealId(0))),
                FloatGridConsumerPurpose.FINITE_COMPATIBILITY)
        }

        assertSame(shape, invocation.finiteShape())
        assertNull(invocation.floatGrid)
    }

    @Test
    fun `a continuous policy should decline only the requested private view`() {
        val source = Problem(0, 0, emptyArray<IntDomain>(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
            semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Continuous).resolve())
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source),
            ExecutionOperation.SATISFY, FiniteEngine.LOCAL_SEARCH))
        val canonical = invocation.preparedPlan

        val result = assertIs<SelectedFloatGridPreparation.Declined>(SourceSetup.prepareSelectedFloatGrid(
            invocation, listOf(FloatGridVariable(SourceRealId(0))), FloatGridConsumerPurpose.HEURISTIC_VIEW,
        ))

        assertEquals(SelectedGridDecline.PROJECTION, result.cause)
        assertEquals(FloatGridDecline.POLICY, result.projectionCause)
        assertSame(canonical, invocation.preparedPlan)
        assertNull(invocation.floatGrid)
    }

    @Test
    fun `a stopped source invocation cannot renew its grid preparation allowance`() {
        val source = Problem(0, 0, emptyArray<IntDomain>(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
            semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Grid(3, 1000)).resolve())
        var stopped = false
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source),
            ExecutionOperation.SATISFY, FiniteEngine.LOCAL_SEARCH, cancellation = Cancellation { stopped }))
        stopped = true

        val result = assertIs<SelectedFloatGridPreparation.Declined>(SourceSetup.prepareSelectedFloatGrid(
            invocation, listOf(FloatGridVariable(SourceRealId(0))),
            FloatGridConsumerPurpose.AUTOMATIC_FINITE_COMPATIBILITY,
        ))
        stopped = false
        val repeated = assertIs<SelectedFloatGridPreparation.Declined>(SourceSetup.prepareSelectedFloatGrid(
            invocation, listOf(FloatGridVariable(SourceRealId(0))),
            FloatGridConsumerPurpose.AUTOMATIC_FINITE_COMPATIBILITY,
        ))

        assertEquals(SelectedGridDecline.RESOURCE, result.cause)
        assertEquals(SelectedGridDecline.RESOURCE, repeated.cause)
        assertNull(invocation.floatGrid)
    }
}
