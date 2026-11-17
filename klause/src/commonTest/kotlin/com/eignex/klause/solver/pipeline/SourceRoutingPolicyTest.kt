package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.formats.flatzinc.linearObjective
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.formats.flatzinc.sourceAttachment
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.theory.BuiltInEncoderProvider
import com.eignex.klause.theory.Encoder
import com.eignex.klause.theory.EncoderProvider
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SourceRoutingPolicyTest {
    @Test
    fun `native finite setup should avoid unselected arithmetic installation`() {
        val source = Problem(0, 2, arrayOf(IntDomain(0, 2), IntDomain(0, 2)),
            arrayOf(Linear(longArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 4)))
        for (engine in FiniteEngine.entries) {
            var charged = 0L
            var installations = 0
            val stop = object : ExecutionControl() {
                override fun isCancelled(): Boolean = charged >= 1000
                override val accounting: WorkMeter get() = WorkMeter { charged += it }
            }
            val providers = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
                val selected = BuiltInEncoderProvider.select(factor, target)
                Encoder(selected.support, selected.requiredIntegers, selected.requiredBooleans) { view, encoding ->
                    installations++
                    encoding.cancellation.charge(1000)
                    selected.install(view, encoding)
                }
            })

            val invocation = SourceSetup.prepare(SourceSetupRequest(
                SourceAttachment(source), ExecutionOperation.SATISFY, engine,
                providers = providers, cancellation = stop,
            ))

            val route = assertIs<SourceProblemRoute.Finite>(invocation.route)
            assertSame(source, route.preparedPlan.originalSource)
            assertSame(providers, route.preparedPlan.factorProjections?.providers)
            assertTrue(assertNotNull(invocation.admission).runnable)
            assertEquals(0, installations)
            assertTrue(charged in 1L until 1000L)
        }
    }

    @Test
    fun `native finite setup should retain LP observation cancellation`() {
        val source = Problem(0, 2, arrayOf(IntDomain(0, 2), IntDomain(0, 2)),
            arrayOf(Linear(longArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 4)))
        var stopped = false
        var observations = 0
        val stop = ExecutionControl { stopped }

        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(source), ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
            cancellation = stop,
        )) {
            observations++
            stopped = true
        }

        val route = assertIs<SourceProblemRoute.UnsupportedOpen>(invocation.route)
        assertEquals(1, observations)
        assertEquals(PreparedPlanDecline.RESOURCE, route.preparedPlan.decline)
        assertNull(route.preparedPlan.componentPlan)
        assertSame(source, route.preparedPlan.originalSource)
    }

    @Test
    fun `an exact objective preference should retain required arithmetic installation`() {
        val source = Problem(0, 2, arrayOf(IntDomain(-1, 1), IntDomain(-1, 1)),
            arrayOf(Linear(longArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 1)))
        val objective = LinearObjective(intCoefficients = longArrayOf((1L shl 53) + 1, 0), constant = 7)
        var installations = 0
        val providers = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            val selected = BuiltInEncoderProvider.select(factor, target)
            Encoder(selected.support, selected.requiredIntegers, selected.requiredBooleans) { view, encoding ->
                installations++
                selected.install(view, encoding)
            }
        })

        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(source), ExecutionOperation.OPTIMIZE, FiniteEngine.BACKTRACK,
            objective, maximize = true, providers = providers,
        ))

        val route = assertIs<SourceProblemRoute.OpenTheory>(invocation.route)
        assertEquals(ProblemPipeline.EXACT_LIRA, route.request.route)
        assertSame(objective, route.request.objective)
        assertTrue(route.request.maximize)
        assertTrue(installations > 0)
    }

    @Test
    fun `native declaration inspection should retain its containing preparation allowance`() {
        val source = Problem(0, 16, Array(16) { IntDomain(0, 1) }, emptyArray())
        var charged = 0L
        val stop = object : ExecutionControl() {
            override fun isCancelled(): Boolean = charged > 0
            override val accounting: WorkMeter get() = WorkMeter { charged += it }
        }

        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(source), ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
            cancellation = stop,
        ))

        val route = assertIs<SourceProblemRoute.UnsupportedOpen>(invocation.route)
        assertTrue(charged > 0)
        assertEquals(PreparedPlanDecline.RESOURCE, route.preparedPlan.decline)
        assertNull(route.preparedPlan.componentPlan)
    }

    @Test
    fun `pure real setup should select exact arithmetic independently of the requested engine`() {
        val source = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(-1.0),
            realUpper = doubleArrayOf(1.0),
        )

        for (engine in FiniteEngine.entries) {
            val invocation = SourceSetup.prepare(
                SourceSetupRequest(
                    SourceAttachment(source),
                    ExecutionOperation.SATISFY,
                    engine,
                )
            )

            val route = assertIs<SourceProblemRoute.OpenTheory>(invocation.route)
            assertEquals(ProblemPipeline.EXACT_LRA, route.request.route)
            assertSame(source, invocation.source.originalSource)
            assertSame(source, route.preparedPlan.originalSource)
        }
    }

    @Test
    fun `wide integral objective should retain its original direction on exact arithmetic`() {
        val source = Problem(
            0,
            2,
            arrayOf(IntDomain(-1, 1), IntDomain(-1, 1)),
            arrayOf(Linear(longArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 1)),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf((1L shl 53) + 1, 0), constant = 7)

        for (maximize in listOf(false, true)) {
            val invocation = SourceSetup.prepare(
                SourceSetupRequest(
                    SourceAttachment(source),
                    ExecutionOperation.OPTIMIZE,
                    FiniteEngine.BACKTRACK,
                    objective,
                    maximize,
                )
            )

            val route = assertIs<SourceProblemRoute.OpenTheory>(invocation.route)
            assertEquals(ProblemPipeline.EXACT_LIRA, route.request.route)
            assertSame(objective, route.request.objective)
            assertSame(objective, assertNotNull(invocation.preparedPlan).objective)
            assertEquals(maximize, route.request.maximize)
        }
    }

    @Test
    fun `an integral objective within exact floating range should retain finite execution`() {
        val source = Problem(
            0,
            2,
            arrayOf(IntDomain(-1, 1), IntDomain(-1, 1)),
            arrayOf(Linear(longArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 1)),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(1L shl 53, 0))

        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
                SourceAttachment(source),
                ExecutionOperation.OPTIMIZE,
                FiniteEngine.BACKTRACK,
                objective,
            )
        )

        assertIs<SourceProblemRoute.Finite>(invocation.route)
        assertSame(invocation.preparedPlan, invocation.finiteShape().preparedPlan)
        assertSame(objective, invocation.finiteShape().linearObjective)
    }

    @Test
    fun `a wide constant should select exact arithmetic without overflowing an interval endpoint`() {
        val source = Problem(
            0,
            2,
            arrayOf(IntDomain(-1, 1), IntDomain(-1, 1)),
            arrayOf(Linear(longArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 1)),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(-1, 0), constant = Long.MAX_VALUE)

        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
                SourceAttachment(source),
                ExecutionOperation.OPTIMIZE,
                FiniteEngine.LOCAL_SEARCH,
                objective,
            )
        )

        val route = assertIs<SourceProblemRoute.OpenTheory>(invocation.route)
        assertSame(objective, route.request.objective)
        assertEquals(ProblemPipeline.EXACT_LIRA, route.request.route)
    }

    @Test
    fun `a stopped objective scan should decline required preparation`() {
        val source = Problem(
            0,
            2,
            arrayOf(IntDomain(-1, 1), IntDomain(-1, 1)),
            arrayOf(Linear(longArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 1)),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1))
        var charged = 0L
        val stop = object : ExecutionControl() {
            override fun isCancelled(): Boolean = charged > 0
            override val accounting: WorkMeter get() = WorkMeter { charged += it }
        }

        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
                SourceAttachment(source),
                ExecutionOperation.OPTIMIZE,
                FiniteEngine.BACKTRACK,
                objective,
                cancellation = stop,
            )
        )

        val route = assertIs<SourceProblemRoute.UnsupportedOpen>(invocation.route)
        assertEquals(PreparedPlanDecline.RESOURCE, route.preparedPlan.decline)
        assertTrue(charged > 0)
    }

    @Test
    fun `LP observation cancellation should prevent publishing a runnable source plan`() {
        val source = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(-1.0),
            realUpper = doubleArrayOf(1.0),
        )
        var stopped = false
        var observations = 0
        val stop = ExecutionControl { stopped }

        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
                SourceAttachment(source),
                ExecutionOperation.SATISFY,
                FiniteEngine.BACKTRACK,
                cancellation = stop,
            )
        ) {
            observations++
            stopped = true
        }

        val route = assertIs<SourceProblemRoute.UnsupportedOpen>(invocation.route)
        assertEquals(1, observations)
        assertEquals(PreparedPlanDecline.RESOURCE, route.preparedPlan.decline)
    }

    private val incompleteEngines = listOf(FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS)

    private fun keepsIncompleteArm(engine: FiniteEngine, operation: ExecutionOperation): Boolean {
        val solve = if (operation == ExecutionOperation.OPTIMIZE) "minimize x" else "satisfy"
        val program = parseFlatZinc("var 0.0..1.0: x; constraint float_eq(x, 0.5); solve $solve;")
        val objective = program.sourceProgram.linearObjective()
        val selection = SourceSetup.selectRoute(SourceSetupRequest(program.sourceAttachment(), operation, engine,
            objective = objective))
        return SourceSetup.preservesOriginalIncompleteArm(selection)
    }

    @Test
    fun `an admitted local search or ALNS arm should keep satisfaction and optimization on the original source`() {
        val operations = listOf(ExecutionOperation.SATISFY, ExecutionOperation.OPTIMIZE)

        val kept = incompleteEngines.associateWith { engine -> operations.map { keepsIncompleteArm(engine, it) } }

        assertEquals(incompleteEngines.associateWith { listOf(true, true) }, kept)
    }

    @Test
    fun `local search or ALNS enumeration should not keep the original source`() {
        val kept = incompleteEngines.associateWith { keepsIncompleteArm(it, ExecutionOperation.ENUMERATE) }

        assertEquals(incompleteEngines.associateWith { false }, kept)
    }

    @Test
    fun `a polynomial route should not keep local search or ALNS on the original source`() {
        val program = parseFlatZinc("var 0.0..2.0: x; var 0.0..2.0: w; var 0.0..4.0: z; " +
            "constraint float_times(x, w, z); constraint float_eq(z, 0.3); solve satisfy;")

        val decisions = incompleteEngines.associateWith { engine ->
            val selection = SourceSetup.selectRoute(SourceSetupRequest(program.sourceAttachment(),
                ExecutionOperation.SATISFY, engine))
            SourceSetup.executionKind(selection.route, selection.source) to
                SourceSetup.preservesOriginalIncompleteArm(selection)
        }

        assertEquals(incompleteEngines.associateWith { SourceExecutionKind.POLYNOMIAL to false }, decisions)
    }

    @Test
    fun `a heuristic route should not keep an admitted local search or ALNS arm on the original source`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)),
            arrayOf(Linear(longArrayOf(1), intArrayOf(0), LinearOp.LE, 1)))
        val arms = mapOf(FiniteEngine.LOCAL_SEARCH to ExecutionStrategy.LOCAL_SEARCH,
            FiniteEngine.ALNS to ExecutionStrategy.ALNS)

        val decisions = arms.mapValues { (engine, arm) ->
            val selection = SourceRouteSelection(SourceSetupRequest(SourceAttachment(source),
                ExecutionOperation.SATISFY, engine), SourceProblemRoute.UnsupportedOpen(null, source),
                ExecutionControl.Never)
            (arm in assertNotNull(selection.admission).arms) to SourceSetup.preservesOriginalIncompleteArm(selection)
        }

        assertEquals(arms.mapValues { true to false }, decisions)
    }
}
