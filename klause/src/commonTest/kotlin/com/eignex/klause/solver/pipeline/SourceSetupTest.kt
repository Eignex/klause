package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.formats.flatzinc.linearObjective
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SearchGroup
import com.eignex.klause.ir.SearchHints
import com.eignex.klause.localsearch.Move
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.retainedCoordinates
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Assignment
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.theory.BuiltInEncoderProvider
import com.eignex.klause.theory.EncoderProvider
import com.eignex.klause.theory.EncodingTarget
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SourceSetupTest {
    @Test
    fun `setup should retain baked source providers unless explicitly replaced`() {
        val selections = mutableMapOf<EncodingTarget, Int>()
        val providers = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            selections[target] = (selections[target] ?: 0) + 1
            BuiltInEncoderProvider.select(factor, target)
        })
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)),
            arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2))).bake(providers)

        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source),
            ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK))

        assertSame(providers, assertNotNull(invocation.preparedPlan).factorProjections?.providers)
        assertEquals(3, selections.size)
        assertTrue(selections.values.all { it == 1 })
        val replacements = mutableMapOf<EncodingTarget, Int>()
        val replacement = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            replacements[target] = (replacements[target] ?: 0) + 1
            BuiltInEncoderProvider.select(factor, target)
        })

        val overridden = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source),
            ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK, providers = replacement))

        assertSame(replacement, assertNotNull(overridden.preparedPlan).factorProjections?.providers)
        assertEquals(3, replacements.size)
        assertTrue(replacements.values.all { it == 1 })
        assertTrue(selections.values.all { it == 1 })
    }

    @Test
    fun `maximization should retain source units while finite execution minimizes its negation`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(3), constant = 7)
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source),
            ExecutionOperation.OPTIMIZE, FiniteEngine.FIXED, objective, maximize = true))
        val shape = invocation.finiteShape()
        val publications = ArrayList<Sample>()

        val result = FinitePipeline.solve(FiniteSolveRequest(
            shape, FiniteEngine.FIXED, PresolveConfig.NONE, false, false, Cancellation.Never,
            presolveBudget = null, cores = 1, executionOptions = ExecutionOptions.parse(emptyList()),
            randomSeed = 3, defaultArms = 1, lpConfig = LpConfig.OFF,
            zeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT, nodeBudget = null,
            solveBudgetMillis = null, allSolutions = false, solutionCap = null, deadlineExceeded = { false },
            onEvent = null, onPortfolioEvent = null,
        ), FiniteSolveCallbacks(publications::add, {}))

        assertSame(objective, assertNotNull(invocation.preparedPlan).objective)
        assertEquals(-13L, assertNotNull(shape.linearObjective).evaluateLong(Sample(BooleanArray(0), longArrayOf(2))))
        assertEquals(FiniteSolveVerdict.OPTIMAL, assertIs<FiniteSolveOutcome.Completed>(result.outcome).verdict)
        assertEquals(13L, objective.evaluateLong(publications.last()))
    }

    @Test
    fun `finite maximization should reject an unrepresentable normalized word coefficient`() {
        val source = Problem(1, 1, arrayOf(IntDomain(0, 2)), emptyArray())
        for (objective in listOf(LinearObjective(boolWeights = longArrayOf(Long.MIN_VALUE)),
            LinearObjective(intCoefficients = longArrayOf(Long.MIN_VALUE)),
            LinearObjective(constant = Long.MIN_VALUE))) {
            val request = SourceSetupRequest(SourceAttachment(source), ExecutionOperation.OPTIMIZE,
                FiniteEngine.FIXED, objective, maximize = true)

            assertFailsWith<IllegalArgumentException> { SourceSetup.prepare(request) }

            assertSame(objective, request.objective)
        }
    }

    @Test
    fun `an enumeration cap of one should retain its captured operation`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)), emptyArray())
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source),
            ExecutionOperation.ENUMERATE, FiniteEngine.FIXED))
        val shape = invocation.finiteShape()
        val publications = ArrayList<Sample>()
        val request = FiniteSolveRequest(
            shape, FiniteEngine.FIXED, PresolveConfig.NONE, false, false, Cancellation.Never,
            presolveBudget = null, cores = 1, executionOptions = ExecutionOptions.parse(emptyList()),
            randomSeed = 3, defaultArms = 1, lpConfig = LpConfig.OFF,
            zeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT, nodeBudget = null,
            solveBudgetMillis = null, allSolutions = false, solutionCap = 1, deadlineExceeded = { false },
            onEvent = null, onPortfolioEvent = null,
        )

        FinitePipeline.solve(request, FiniteSolveCallbacks(publications::add, {}))

        assertEquals(ExecutionOperation.ENUMERATE, request.operation)
        assertEquals(1, publications.size)
        assertFailsWith<IllegalArgumentException> {
            invocation.requireFiniteExecution(shape, FiniteEngine.FIXED, ExecutionOperation.SATISFY)
        }
    }

    @Test
    fun `a captured invocation should reject another source or engine binding`() {
        val first = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(Problem(0, 1, arrayOf(IntDomain(0, 2)), emptyArray())),
            ExecutionOperation.SATISFY, FiniteEngine.FIXED))
        val second = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(Problem(0, 1, arrayOf(IntDomain(0, 2)), emptyArray())),
            ExecutionOperation.SATISFY, FiniteEngine.FIXED))

        assertFailsWith<IllegalArgumentException> {
            first.requireFiniteExecution(second.finiteShape(), FiniteEngine.FIXED, ExecutionOperation.SATISFY)
        }
        assertFailsWith<IllegalArgumentException> {
            first.requireFiniteExecution(first.finiteShape(), FiniteEngine.LOCAL_SEARCH, ExecutionOperation.SATISFY)
        }
    }

    @Test
    fun `a stopped setup should not be restarted with an unlimited execution token`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)), emptyArray())
        var stopped = false
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source),
            ExecutionOperation.SATISFY, FiniteEngine.FIXED, cancellation = Cancellation { stopped }))
        stopped = true
        var publications = 0

        val request = FiniteSolveRequest(
            invocation.finiteShape(), FiniteEngine.FIXED, PresolveConfig.NONE, false, false, Cancellation.Never,
            presolveBudget = null, cores = 1, executionOptions = ExecutionOptions.parse(emptyList()),
            randomSeed = 3, defaultArms = 1, lpConfig = LpConfig.OFF,
            zeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT, nodeBudget = null,
            solveBudgetMillis = null, allSolutions = false, solutionCap = null, deadlineExceeded = { false },
            onEvent = null, onPortfolioEvent = null,
        )

        val result = FinitePipeline.solve(request, FiniteSolveCallbacks({ publications++ }, {}))
        stopped = false
        val retry = FinitePipeline.solve(request, FiniteSolveCallbacks({ publications++ }, {}))

        assertEquals(FiniteSolveVerdict.UNKNOWN, assertIs<FiniteSolveOutcome.Completed>(result.outcome).verdict)
        assertEquals(FiniteSolveVerdict.UNKNOWN, assertIs<FiniteSolveOutcome.Completed>(retry.outcome).verdict)
        assertSame(source, result.preparation.problem)
        assertEquals(0, publications)
    }

    @Test
    fun `an admitted heuristic view should follow original definition hints`() {
        val program = parseFlatZinc("var -3..3: x; var 0..3: a; " +
            "constraint int_abs(x,a) :: defines_var(a); solve minimize a;")
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(program.problem, program.definitionHints), ExecutionOperation.OPTIMIZE,
            FiniteEngine.LOCAL_SEARCH, assertNotNull(program.linearObjective())))
        val shape = invocation.finiteShape()
        val preparation = FinitePipeline.prepare(FinitePipelineRequest(shape.problem, FiniteEngine.LOCAL_SEARCH,
            shape.linearObjective, PresolveConfig.NONE, preparedPlan = shape.preparedPlan))

        val view = assertNotNull(invocation.heuristicHints(preparation.problem, preparation.objective,
            preparation.sourceMapping, Cancellation.Never, setOf(ExecutionStrategy.LOCAL_SEARCH),
            assertNotNull(preparation.executionPlan).capabilities))
        val objective = assertNotNull(view.objective)
        val assignment = Assignment(program.problem.numBoolVars, program.problem.numIntVars)
        val x = program.intVarsByName.getValue("x")
        assignment.setInt(x, -3)

        assertEquals(-2.0, objective.deltaIfApplied(assignment, Move.IntSet(x, 1)))
        assertNull(shape.localSearchObjective)
        assertNull(shape.definitionalSweep)
    }

    @Test
    fun `source rewrites should retain only hints for direct execution coordinates`() {
        val program = parseFlatZinc("var -3..3: x; var 0..3: a; var -2..4: t; " +
            "constraint int_abs(x,a) :: defines_var(a); " +
            "constraint int_plus(x,1,t) :: defines_var(t); solve minimize a;")
        val x = program.intVarsByName.getValue("x")
        val t = program.intVarsByName.getValue("t")
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(program.problem,
            program.definitionHints, SearchHints(listOf(SearchGroup(intArrayOf(), intArrayOf(t, x))))),
            ExecutionOperation.OPTIMIZE, FiniteEngine.LOCAL_SEARCH, assertNotNull(program.linearObjective())))
        val shape = invocation.finiteShape()

        val preparation = FinitePipeline.prepare(FinitePipelineRequest(shape.problem, FiniteEngine.LOCAL_SEARCH,
            shape.linearObjective, PresolveConfig.parse("affine"), explicitPresolveConfig = true,
            preparedPlan = shape.preparedPlan))
        val coordinates = assertNotNull(assertNotNull(preparation.sourceMapping).retainedCoordinates())
        val search = assertNotNull(assertNotNull(shape.searchHints).remapped(coordinates, Cancellation.Never))
        val view = assertNotNull(invocation.heuristicHints(preparation.problem, preparation.objective,
            preparation.sourceMapping, Cancellation.Never, setOf(ExecutionStrategy.LOCAL_SEARCH),
            assertNotNull(preparation.executionPlan).capabilities))

        assertEquals(-1, coordinates.int(t))
        assertEquals(listOf(x), search.tiers.single().intVars.toList())
        assertSame(program.problem, assertNotNull(preparation.executionPlan).originalSource)
        assertSame(preparation.problem, assertNotNull(preparation.executionPlan).model)
        assertNotNull(view.objective)
    }
}
