package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealArrayMinMax
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.projection.FloatGridVariable
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration

class FloatGridPublicationTest {
    @Test
    fun `interrupted declared grid enumeration should retain points without claiming completion`() {
        val source = Problem(0, 0, emptyArray<IntDomain>(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
            semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Grid(3, 1000)).resolve())
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source),
            ExecutionOperation.ENUMERATE, FiniteEngine.FIXED))
        assertIs<SelectedFloatGridPreparation.Prepared>(SourceSetup.prepareSelectedFloatGrid(invocation,
            listOf(FloatGridVariable(SourceRealId(0))), FloatGridConsumerPurpose.FINITE_COMPATIBILITY,
            declaredGridSemantics = true))
        val binding = assertNotNull(invocation.floatGrid)
        val publication = FloatGridPublication(binding, Cancellation.Never, null, FiniteSolveCallbacks({}, {}))
        publication.onSample(Sample(booleanArrayOf(), longArrayOf(1)))

        val result = publication.result(FinitePipelinePreparation(binding.projection.problem, null, { it }, null),
            Duration.ZERO, FiniteSolveOutcome.Completed(FiniteSolveVerdict.UNKNOWN, SolveStats.EMPTY, 1, null, 0))

        val outcome = assertIs<FiniteSolveOutcome.Completed>(result.outcome)
        assertEquals(FiniteSolveVerdict.UNKNOWN, outcome.verdict)
        assertEquals(BigFraction.ofDouble(0.5), assertNotNull(outcome.bestSample).exactReals?.single())
    }

    @Test
    fun `a rejected terminal grid optimum should retain only an accepted incumbent`() {
        val source = Problem(0, 0, emptyArray<IntDomain>(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
            semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Grid(3, 1000)).resolve())
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source),
            ExecutionOperation.OPTIMIZE, FiniteEngine.FIXED,
            LinearObjective(realCoefficients = doubleArrayOf(1.0)), maximize = true))
        assertIs<SelectedFloatGridPreparation.Prepared>(SourceSetup.prepareSelectedFloatGrid(invocation,
            listOf(FloatGridVariable(SourceRealId(0))), FloatGridConsumerPurpose.FINITE_COMPATIBILITY,
            declaredGridSemantics = true))
        val binding = assertNotNull(invocation.floatGrid)
        val publication = FloatGridPublication(binding, Cancellation.Never, { it.approximateRealValue(0) <= 0.5 },
            FiniteSolveCallbacks({}, {}))
        publication.onSample(Sample(booleanArrayOf(), longArrayOf(0)))

        val result = publication.result(FinitePipelinePreparation(binding.projection.problem, null, { it }, null),
            Duration.ZERO, FiniteSolveOutcome.Completed(FiniteSolveVerdict.OPTIMAL, SolveStats.EMPTY, 2,
                Sample(booleanArrayOf(), longArrayOf(2)), 0))

        val outcome = assertIs<FiniteSolveOutcome.Completed>(result.outcome)
        assertEquals(FiniteSolveVerdict.BEST_FOUND, outcome.verdict)
        assertEquals(BigFraction.ZERO, assertNotNull(outcome.bestSample).exactReals?.single())
    }

    @Test
    fun `private extrema publications should check the original relation without a finite real propagator`() {
        val source = Problem(0, 0, emptyArray<IntDomain>(), arrayOf(RealArrayMinMax(2, intArrayOf(0, 1), false)),
            numRealVars = 3, realLower = doubleArrayOf(-1.0, 0.0, -1.0),
            realUpper = doubleArrayOf(1.0, 2.0, 2.0),
            semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Grid(3, 1000)).resolve())
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source),
            ExecutionOperation.SATISFY, FiniteEngine.FIXED))
        assertIs<SelectedFloatGridPreparation.Prepared>(SourceSetup.prepareSelectedFloatGrid(invocation,
            List(3) { FloatGridVariable(SourceRealId(it)) }, FloatGridConsumerPurpose.FINITE_COMPATIBILITY))
        val binding = assertNotNull(invocation.floatGrid)
        val points = ArrayList<Sample>()
        val publication = FloatGridPublication(binding, Cancellation.Never, null, FiniteSolveCallbacks(points::add, {}))

        publication.onSample(Sample(booleanArrayOf(), longArrayOf(0, 0, 1)))
        publication.onSample(Sample(booleanArrayOf(), longArrayOf(0, 0, 0)))

        assertEquals(1, points.size)
        assertEquals(BigFraction.ofLong(-1), points.single().exactReals?.get(2))
        assertSame(source, points.single().coordinateFrame)
    }

    @Test
    fun `retained grid results should not change when later publications update the incumbent`() {
        val source = Problem(0, 0, emptyArray<IntDomain>(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
            semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Grid(3, 1000)).resolve())
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source),
            ExecutionOperation.OPTIMIZE, FiniteEngine.FIXED,
            LinearObjective(realCoefficients = doubleArrayOf(1.0)), maximize = true))
        assertIs<SelectedFloatGridPreparation.Prepared>(SourceSetup.prepareSelectedFloatGrid(invocation,
            listOf(FloatGridVariable(SourceRealId(0))), FloatGridConsumerPurpose.FINITE_COMPATIBILITY))
        val binding = assertNotNull(invocation.floatGrid)
        val publications = FloatGridPublication(binding, Cancellation.Never, null, FiniteSolveCallbacks({}, {}))
        val preparation = FinitePipelinePreparation(binding.projection.problem, null, { it }, null)
        val firstNative = FiniteSolveOutcome.Completed(FiniteSolveVerdict.BEST_FOUND, SolveStats.EMPTY, 1,
            Sample(booleanArrayOf(), longArrayOf(0)), 0)

        val first = publications.result(preparation, Duration.ZERO, firstNative)
        val second = publications.result(preparation, Duration.ZERO,
            FiniteSolveOutcome.Completed(FiniteSolveVerdict.OPTIMAL, SolveStats.EMPTY, 2,
                Sample(booleanArrayOf(), longArrayOf(2)), 0))

        assertEquals(BigFraction.ZERO, assertNotNull(assertIs<FiniteSolveOutcome.Completed>(first.outcome).bestSample)
            .exactReals?.single())
        assertEquals(BigFraction.ONE, assertNotNull(assertIs<FiniteSolveOutcome.Completed>(second.outcome).bestSample)
            .exactReals?.single())
        assertSame(firstNative, assertNotNull(first.nativeGridOutcome).outcome)
        assertEquals(FiniteSolveVerdict.BEST_FOUND, assertIs<FiniteSolveOutcome.Completed>(second.outcome).verdict)
    }

    @Test
    fun `interruption during original acceptance should withhold a private grid publication`() {
        val source = Problem(0, 0, emptyArray<IntDomain>(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
            semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Grid(3, 1000)).resolve())
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source),
            ExecutionOperation.SATISFY, FiniteEngine.FIXED))
        assertIs<SelectedFloatGridPreparation.Prepared>(SourceSetup.prepareSelectedFloatGrid(invocation,
            listOf(FloatGridVariable(SourceRealId(0))), FloatGridConsumerPurpose.FINITE_COMPATIBILITY))
        val binding = assertNotNull(invocation.floatGrid)
        var stopped = false
        val points = ArrayList<Sample>()
        val publications = FloatGridPublication(binding, Cancellation { stopped }, { stopped = true; true },
            FiniteSolveCallbacks(points::add, {}))
        val native = FiniteSolveOutcome.Completed(FiniteSolveVerdict.SAT, SolveStats.EMPTY, 1,
            Sample(booleanArrayOf(), longArrayOf(1)), 0)

        val result = publications.result(FinitePipelinePreparation(binding.projection.problem, null, { it }, null),
            Duration.ZERO, native)

        assertTrue(points.isEmpty())
        assertEquals(FiniteSolveVerdict.UNKNOWN, assertIs<FiniteSolveOutcome.Completed>(result.outcome).verdict)
        assertSame(native, assertNotNull(result.nativeGridOutcome).outcome)
    }

    @Test
    fun `private grid exhaustion should retain its refutation without refuting the original real source`() {
        val source = Problem(0, 0, emptyArray<IntDomain>(), arrayOf(Linear(intArrayOf(), doubleArrayOf(),
            intArrayOf(0), doubleArrayOf(1.0), LinearOp.EQ, 0.25)), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
            semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Grid(2, 1000)).resolve())
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source),
            ExecutionOperation.SATISFY, FiniteEngine.FIXED))
        assertIs<SelectedFloatGridPreparation.Prepared>(SourceSetup.prepareSelectedFloatGrid(invocation,
            listOf(FloatGridVariable(SourceRealId(0))), FloatGridConsumerPurpose.FINITE_COMPATIBILITY))
        val publications = ArrayList<Sample>()

        val execution = SourceSetup.execute(SourceExecutionRequest(invocation, finiteRequest = { shape ->
            FiniteSolveRequest(shape, FiniteEngine.FIXED, PresolveConfig.NONE, false, false, Cancellation.Never,
                presolveBudget = null, cores = 1, executionOptions = ExecutionOptions.parse(emptyList()),
                randomSeed = 0, defaultArms = 1, lpConfig = LpConfig.OFF,
                zeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT, nodeBudget = null,
                solveBudgetMillis = null, allSolutions = false, solutionCap = null, deadlineExceeded = { false },
                onEvent = null, onPortfolioEvent = null)
        }), SourceExecutionCallbacks(finite = FiniteSolveCallbacks(publications::add, {})))

        val result = assertIs<SourceExecutionResult.Finite>(execution).result
        assertEquals(FiniteSolveVerdict.UNKNOWN, assertIs<FiniteSolveOutcome.Completed>(result.outcome).verdict)
        val native = assertNotNull(result.nativeGridOutcome)
        assertEquals(FiniteSolveVerdict.UNSAT, assertIs<FiniteSolveOutcome.Completed>(native.outcome).verdict)
        assertSame(source, native.projection.source.originalSource)
        assertTrue(publications.isEmpty())
    }

    @Test
    fun `a rounded private product witness should not become an original source publication`() {
        val source = Problem(0, 0, emptyArray<IntDomain>(), arrayOf(ContinuousProduct(0, 0, 1)), numRealVars = 2,
            realLower = doubleArrayOf(0.0, 0.0), realUpper = doubleArrayOf(1.0, 1.0),
            semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Grid(3, 1000)).resolve())
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source),
            ExecutionOperation.SATISFY, FiniteEngine.FIXED))
        assertIs<SelectedFloatGridPreparation.Prepared>(SourceSetup.prepareSelectedFloatGrid(invocation,
            listOf(FloatGridVariable(SourceRealId(0)), FloatGridVariable(SourceRealId(1))),
            FloatGridConsumerPurpose.FINITE_COMPATIBILITY))
        val binding = assertNotNull(invocation.floatGrid)
        val native = FiniteSolveOutcome.Completed(FiniteSolveVerdict.SAT, SolveStats.EMPTY, 1,
            Sample(booleanArrayOf(), longArrayOf(1, 0)), 0)
        val points = ArrayList<Sample>()
        val publication = FloatGridPublication(binding, Cancellation.Never, null, FiniteSolveCallbacks(points::add, {}))

        val result = publication.result(FinitePipelinePreparation(binding.projection.problem, null, { it }, null),
            Duration.ZERO, native)

        assertEquals(FiniteSolveVerdict.UNKNOWN, assertIs<FiniteSolveOutcome.Completed>(result.outcome).verdict)
        assertSame(native, assertNotNull(result.nativeGridOutcome).outcome)
        assertTrue(points.isEmpty())
    }

    @Test
    fun `maximization should publish original units while retaining the private optimum scope`() {
        val source = Problem(0, 0, emptyArray<IntDomain>(), arrayOf(Linear(intArrayOf(), doubleArrayOf(),
            intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 1.0)), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0),
            semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Grid(3, 1000)).resolve())
        val objective = LinearObjective(realCoefficients = doubleArrayOf(3.0), constant = 7)
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source),
            ExecutionOperation.OPTIMIZE, FiniteEngine.FIXED, objective, maximize = true))
        assertIs<SelectedFloatGridPreparation.Prepared>(SourceSetup.prepareSelectedFloatGrid(invocation,
            listOf(FloatGridVariable(SourceRealId(0))), FloatGridConsumerPurpose.FINITE_COMPATIBILITY))
        val points = ArrayList<Sample>()

        val execution = SourceSetup.execute(SourceExecutionRequest(invocation, finiteRequest = { shape ->
            FiniteSolveRequest(shape, FiniteEngine.FIXED, PresolveConfig.NONE, false, false, Cancellation.Never,
                presolveBudget = null, cores = 1, executionOptions = ExecutionOptions.parse(emptyList()),
                randomSeed = 0, defaultArms = 1, lpConfig = LpConfig.OFF,
                zeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT, nodeBudget = null,
                solveBudgetMillis = null, allSolutions = false, solutionCap = null, deadlineExceeded = { false },
                onEvent = null, onPortfolioEvent = null)
        }), SourceExecutionCallbacks(finite = FiniteSolveCallbacks(points::add, {})))

        val result = assertIs<SourceExecutionResult.Finite>(execution).result
        val qualified = assertIs<FiniteSolveOutcome.Completed>(result.outcome)
        assertEquals(FiniteSolveVerdict.BEST_FOUND, qualified.verdict)
        val point = assertNotNull(qualified.bestSample)
        assertEquals(BigFraction.ofLong(10), objective.evaluateExact(point))
        assertSame(source, point.coordinateFrame)
        assertNull(point.witnessCertificate)
        assertTrue(points.isNotEmpty())
        assertEquals(FiniteSolveVerdict.OPTIMAL,
            assertIs<FiniteSolveOutcome.Completed>(assertNotNull(result.nativeGridOutcome).outcome).verdict)
        assertSame(objective, assertNotNull(invocation.floatProjection).originalObjective)
    }

    @Test
    fun `coordinate provenance should survive unchanged copies and clear after coordinate replacement`() {
        val frame = Any()
        val point = Sample(booleanArrayOf(), longArrayOf(1)).withCoordinateFrame(frame)

        val copied = point.copy()
        val changed = point.copy(ints = longArrayOf(2))

        assertSame(frame, copied.coordinateFrame)
        assertNull(changed.coordinateFrame)
    }
}
