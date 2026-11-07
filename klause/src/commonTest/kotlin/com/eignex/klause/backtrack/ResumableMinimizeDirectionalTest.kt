package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.backtrack.selector.VariableSelector
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.portfolioExecutor
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchBranchAttemptObserver
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.Cancellation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ResumableMinimizeDirectionalTest {
    @Test
    fun `finite search records an immediate child pruned by its objective bound`() {
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 10) }, arrayOf<Factor>(
            Linear(longArrayOf(1L, 1L), intArrayOf(0, 1), LinearOp.GE, 3L),
            Linear(longArrayOf(1L, 1L), intArrayOf(1, 2), LinearOp.GE, 2L),
            Linear(longArrayOf(3L, 1L), intArrayOf(0, 2), LinearOp.GE, 4L),
        )).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(2L, 3L, 4L))
        val params = BacktrackParams(randomSeed = 3L, maxDecisions = 64L, objectiveBoundSupplier = { 10.0 },
            lpDirectionalGains = true,
            lpPlan = LpPlan(bounding = true, rootBudgetMillis = 0L, rootCutHarvest = false,
                boundMaxOpsPerNode = 0L))
        ResumableMinimize(BacktrackSolver(problem), objective, params,
            rootBasisGuidance = false).use { search ->
            var slices = 0
            while (slices++ < 8 && !search.isDone) search.runSlice(Cancellation.Never, 1000L, 8L) {}

            val observations = search.stats.lp.phases["DIRECTIONAL_BRANCH_NODE"]?.outcomes
            assertTrue((observations?.get("FINITE_BOUND_RECORDED") ?: 0L) > 0L, observations.toString())
        }
    }

    @Test
    fun `root child basis queries require explicit finite search opt in`() {
        for (basis in listOf(false, true)) {
            val problem = Problem(0, 3, Array(3) { IntDomain(0, 10) }, arrayOf<Factor>(
                Linear(longArrayOf(1L, 1L), intArrayOf(0, 1), LinearOp.GE, 3L),
                Linear(longArrayOf(1L, 1L), intArrayOf(1, 2), LinearOp.GE, 2L),
                Linear(longArrayOf(3L, 1L), intArrayOf(0, 2), LinearOp.GE, 4L),
            )).bake()
            val objective = LinearObjective(intCoefficients = longArrayOf(2L, 3L, 4L))
            val params = BacktrackParams(randomSeed = 3L, maxDecisions = 64L, objectiveBoundSupplier = { 10.0 },
                lpDirectionalGains = true,
                lpPlan = LpPlan(bounding = true, rootBudgetMillis = 0L, rootCutHarvest = false,
                    boundMaxOpsPerNode = 0L))
            val search = if (basis) {
                ResumableMinimize(BacktrackSolver(problem), objective, params, rootBasisGuidance = true)
            } else ResumableMinimize(BacktrackSolver(problem), objective, params)
            search.use {
                var slices = 0
                while (slices++ < 8 && !search.isDone) search.runSlice(Cancellation.Never, 1000L, 8L) {}

                val observations = search.stats.lp.phases["DIRECTIONAL_BRANCH_NODE"]?.outcomes.orEmpty()
                val expected = if (basis) 1L else 0L
                assertEquals(expected, observations["BASIS_ATTEMPT"] ?: 0L, observations.toString())
                assertEquals(expected, observations["BASIS_SIDE_CONSUMED"] ?: 0L, observations.toString())
            }
        }
    }

    @Test
    fun `root variable selection does not request child probes`() {
        val factors = Array<Factor>(3) { edge ->
            Linear(longArrayOf(1L, 1L), intArrayOf(edge, (edge + 1) % 3), LinearOp.GE, 1L)
        }
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 1) }, factors).bake()
        var attempted = false
        val params = BacktrackParams(randomSeed = 3L, maxDecisions = 64L, objectiveBoundSupplier = { 100.0 },
            branchObserver = object : SearchBranchAttemptObserver {
                override fun onAttempt(decision: SearchDecision, context: SearchContext) { attempted = true }
            },
            lpDirectionalGains = true,
            lpPlan = LpPlan(bounding = true, rootBudgetMillis = 0L, rootCutHarvest = false,
                boundMaxOpsPerNode = 0L))
        val objective = LinearObjective(intCoefficients = longArrayOf(3L, 2L, 4L))
        ResumableMinimize(BacktrackSolver(problem), objective, params).use { search ->
            search.runSlice(Cancellation { attempted }, 1000L, 8L) {}

            val observations = search.stats.lp.phases.getValue("DIRECTIONAL_BRANCH_NODE").outcomes
            assertTrue(attempted)
            assertEquals(0L, observations["VARIABLE_PROBE_ATTEMPT"] ?: 0L, observations.toString())
            assertEquals(0L, observations["VARIABLE_PROBE_CONSUMED"] ?: 0L, observations.toString())
            assertEquals(0L, observations["SIDE_CONSUMED"] ?: 0L, observations.toString())
            assertEquals(null, search.stats.lp.phases["DIRECTIONAL_PROBES_NODE"])
        }
    }

    @Test
    fun `finite search consumes passive advice without child solves`() {
        val sink = SolveStatsSink("directional-passive-consumer")
        var consumed = false
        val blocks = 3
        val variables = blocks * 3
        val factors = ArrayList<Factor>()
        repeat(blocks) { block ->
            val first = block * 3
            factors.add(Linear(longArrayOf(2L, 3L, 4L),
                intArrayOf(first, first + 1, first + 2), LinearOp.GE, 5L))
        }
        val problem = Problem(0, variables, Array(variables) { IntDomain(0, 1) }, factors.toTypedArray()).bake()
        var completionAttempt = false
        val params = BacktrackParams(
            variableSelector = object : VariableSelector {
                override fun fresh() = this
                override fun pick(session: PropagationSession, rng: Random): VarRef? {
                    completionAttempt = true
                    return InputOrder.pick(session, rng)
                }
            },
            valueSelector = IndomainMin,
            randomSeed = 3L,
            maxDecisions = 1000L,
            branchObserver = object : SearchBranchAttemptObserver {
                override fun onAttempt(decision: SearchDecision, context: SearchContext) {
                    val outcomes = sink.lp.snapshot().phases["DIRECTIONAL_BRANCH_NODE"]?.outcomes
                    consumed = (outcomes?.get("DOWN") ?: 0L) >= 2L &&
                        (outcomes?.get("UP") ?: 0L) >= 2L &&
                        (outcomes?.get("VARIABLE_ESTIMATED") ?: 0L) > 0L &&
                        (outcomes?.get("VARIABLE_CONSUMED") ?: 0L) > 0L
                }
            },
            lpPlan = LpPlan(bounding = true, rootBudgetMillis = 0L, rootCutHarvest = false,
                boundMaxOpsPerNode = 0L, componentSplit = false),
            lpDirectionalGains = true,
            componentFactory = { listOf(object : SearchComponent {
                override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
                    if (!completionAttempt) return ComponentResult.Consistent
                    completionAttempt = false
                    return ComponentResult.Conflict()
                }
                override fun retract(decisionLevel: Int) { completionAttempt = false }
                override fun check(context: SearchContext): ComponentCheck = ComponentCheck.Infeasible()
            }) },
        )

        val objective = LinearObjective(intCoefficients = longArrayOf(
            6L, 6L, 11L, 5L, 5L, 9L, 4L, 4L, 5L,
        ))
        ResumableMinimize(BacktrackSolver(problem), objective, params,
            rootBasisGuidance = false, basisVariableGuidance = false, sink = sink).use { search ->
            val stopAfterConsumption = Cancellation { consumed }
            var slices = 0
            while (slices++ < 128 && !search.isDone && !consumed) {
                search.runSlice(stopAfterConsumption, 1000L, 128L) {}
            }

            val stats = search.stats
            val observations = stats.lp.phases.getValue("DIRECTIONAL_BRANCH_NODE").outcomes
            val diagnostic = "$observations; solves=${stats.lp.solves.sum}; " +
                "probes=${stats.lp.phases["DIRECTIONAL_PROBES_NODE"]}"
            assertTrue(consumed, diagnostic)
            assertTrue((observations["DOWN"] ?: 0L) >= 2L, diagnostic)
            assertTrue((observations["UP"] ?: 0L) >= 2L, diagnostic)
            assertTrue((observations["VARIABLE_CONSUMED"] ?: 0L) > 0L, diagnostic)
            assertTrue((observations["VARIABLE_ESTIMATED"] ?: 0L) > 0L, diagnostic)
            assertEquals(0L, observations["VARIABLE_PROBE_ATTEMPT"] ?: 0L, diagnostic)
            assertEquals(0L, observations["VARIABLE_BASIS_ATTEMPT"] ?: 0L, diagnostic)
            assertEquals(0L, observations["SIDE_CONSUMED"] ?: 0L, diagnostic)
            assertEquals(0L, observations["HISTORY_SIDE_CONSUMED"] ?: 0L, diagnostic)
            assertEquals(0L, observations["BASIS_SIDE_CONSUMED"] ?: 0L, diagnostic)
            assertEquals(0L, observations["BASIS_ATTEMPT"] ?: 0L, diagnostic)
        }
    }

    @Test
    fun `finite search consumes basis advice without child solves`() {
        val blocks = 3
        val variables = blocks * 3
        val factors = ArrayList<Factor>()
        repeat(blocks) { block ->
            val first = block * 3
            factors.add(Linear(longArrayOf(2L, 3L, 4L),
                intArrayOf(first, first + 1, first + 2), LinearOp.GE, 5L))
        }
        val problem = Problem(0, variables, Array(variables) { IntDomain(0, 1) }, factors.toTypedArray()).bake()
        var completionAttempt = false
        val attempted = ArrayList<Int>()
        val params = BacktrackParams(
            variableSelector = object : VariableSelector {
                override fun fresh() = this
                override fun pick(session: PropagationSession, rng: Random): VarRef? {
                    completionAttempt = true
                    return InputOrder.pick(session, rng)
                }
            },
            valueSelector = IndomainMin,
            randomSeed = 3L,
            maxDecisions = 64L,
            branchObserver = object : SearchBranchAttemptObserver {
                override fun onAttempt(decision: SearchDecision, context: SearchContext) {
                    val variable = when (decision) {
                        is SearchDecision.IntAtMost -> decision.variable
                        is SearchDecision.IntAtLeast -> decision.variable
                        else -> null
                    }
                    if (variable != null) attempted.add(variable)
                }
            },
            lpPlan = LpPlan(bounding = true, rootBudgetMillis = 0L, rootCutHarvest = false,
                boundMaxOpsPerNode = 0L, componentSplit = false),
            lpDirectionalGains = true,
            componentFactory = { listOf(object : SearchComponent {
                override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
                    if (!completionAttempt) return ComponentResult.Consistent
                    completionAttempt = false
                    return ComponentResult.Conflict()
                }
                override fun retract(decisionLevel: Int) { completionAttempt = false }
                override fun check(context: SearchContext): ComponentCheck = ComponentCheck.Infeasible()
            }) },
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(
            6L, 6L, 11L, 5L, 5L, 9L, 40L, 40L, 50L,
        ))
        ResumableMinimize(BacktrackSolver(problem), objective, params,
            rootBasisGuidance = false, verifyBasisVariableGuidance = false).use { search ->
            val consumed = Cancellation {
                (search.stats.lp.phases["DIRECTIONAL_BRANCH_NODE"]?.outcomes
                    ?.get("VARIABLE_BASIS_CONSUMED") ?: 0L) > 0L && 7 in attempted
            }
            var slices = 0
            while (slices++ < 16 && !search.isDone && !consumed()) {
                search.runSlice(consumed, 1000L, 128L) {}
            }

            val observations = search.stats.lp.phases.getValue("DIRECTIONAL_BRANCH_NODE").outcomes
            assertTrue((observations["VARIABLE_BASIS_CONSUMED"] ?: 0L) > 0L, observations.toString())
            assertEquals(listOf(2, 7), attempted.take(2))
            assertEquals(0L, observations["VARIABLE_PROBE_ATTEMPT"] ?: 0L, observations.toString())
            assertEquals(0L, observations["SIDE_CONSUMED"] ?: 0L, observations.toString())
            assertEquals(0L, observations["BASIS_ATTEMPT"] ?: 0L, observations.toString())
            assertEquals(null, search.stats.lp.phases["DIRECTIONAL_PROBES_NODE"])
        }
    }

    @Test
    fun `finite search confirms basis advice with one child comparison`() {
        val sink = SolveStatsSink("confirmed-basis-consumer")
        var consumed = false
        val blocks = 3
        val variables = blocks * 3
        val factors = ArrayList<Factor>()
        repeat(blocks) { block ->
            val first = block * 3
            factors.add(Linear(longArrayOf(2L, 3L, 4L),
                intArrayOf(first, first + 1, first + 2), LinearOp.GE, 5L))
        }
        val problem = Problem(0, variables, Array(variables) { IntDomain(0, 1) }, factors.toTypedArray()).bake()
        var completionAttempt = false
        val attempted = ArrayList<Int>()
        val params = BacktrackParams(
            variableSelector = object : VariableSelector {
                override fun fresh() = this
                override fun pick(session: PropagationSession, rng: Random): VarRef? {
                    completionAttempt = true
                    return InputOrder.pick(session, rng)
                }
            },
            valueSelector = IndomainMin,
            randomSeed = 3L,
            maxDecisions = 64L,
            branchObserver = object : SearchBranchAttemptObserver {
                override fun onAttempt(decision: SearchDecision, context: SearchContext) {
                    val variable = when (decision) {
                        is SearchDecision.IntAtMost -> decision.variable
                        is SearchDecision.IntAtLeast -> decision.variable
                        else -> null
                    }
                    if (variable != null) attempted.add(variable)
                    val outcomes = sink.lp.snapshot().phases["DIRECTIONAL_BRANCH_NODE"]?.outcomes
                    consumed = (outcomes?.get("VARIABLE_PROBE_CONSUMED") ?: 0L) > 0L && 7 in attempted
                }
            },
            lpPlan = LpPlan(bounding = true, rootBudgetMillis = 0L, rootCutHarvest = false,
                boundMaxOpsPerNode = 0L, componentSplit = false),
            lpDirectionalGains = true,
            componentFactory = { listOf(object : SearchComponent {
                override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
                    if (!completionAttempt) return ComponentResult.Consistent
                    completionAttempt = false
                    return ComponentResult.Conflict()
                }
                override fun retract(decisionLevel: Int) { completionAttempt = false }
                override fun check(context: SearchContext): ComponentCheck = ComponentCheck.Infeasible()
            }) },
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(
            6L, 6L, 11L, 5L, 5L, 9L, 40L, 40L, 50L,
        ))
        ResumableMinimize(BacktrackSolver(problem), objective, params,
            rootBasisGuidance = false, probeChildGuidance = false, sink = sink).use { search ->
            val stopAfterConsumption = Cancellation { consumed }
            var slices = 0
            while (slices++ < 16 && !search.isDone && !consumed) {
                search.runSlice(stopAfterConsumption, 1000L, 128L) {}
            }

            val stats = search.stats.lp
            val observations = stats.phases.getValue("DIRECTIONAL_BRANCH_NODE").outcomes
            val diagnostic = "$observations; probes=${stats.phases["DIRECTIONAL_PROBES_NODE"]}"
            assertTrue(consumed, diagnostic)
            assertEquals(listOf(2, 7), attempted.take(2), diagnostic)
            assertEquals(1L, observations["VARIABLE_BASIS_VERIFICATION_ATTEMPT"], diagnostic)
            assertEquals(1L, observations["VARIABLE_PROBE_ATTEMPT"], diagnostic)
            assertEquals(0L, observations["VARIABLE_BASIS_CONSUMED"] ?: 0L, diagnostic)
            assertEquals(0L, observations["SIDE_CONSUMED"] ?: 0L, diagnostic)
            assertEquals(0L, observations["BASIS_ATTEMPT"] ?: 0L, diagnostic)
            val probes = stats.phases.getValue("DIRECTIONAL_PROBES_NODE")
            assertEquals(1L, probes.calls, diagnostic)
            assertEquals(4L, probes.outcomes["CHILDREN_COMPLETED"], diagnostic)
        }
    }

    @Test
    fun `finite search applies or withholds confirmed advice at the actual branch`() {
        val queryWork = ArrayList<Long>()
        for ((requested, applyAdvice) in listOf(null to false, false to false, true to true)) {
            val blocks = 3
            val variables = blocks * 3
            val factors = ArrayList<Factor>()
            repeat(blocks) { block ->
                val first = block * 3
                factors.add(Linear(longArrayOf(2L, 3L, 4L),
                    intArrayOf(first, first + 1, first + 2), LinearOp.GE, 5L))
            }
            val problem = Problem(0, variables, Array(variables) { IntDomain(0, 1) }, factors.toTypedArray()).bake()
            var completionAttempt = false
            val attempted = ArrayList<SearchDecision>()
            val params = BacktrackParams(
                variableSelector = object : VariableSelector {
                    override fun fresh() = this
                    override fun pick(session: PropagationSession, rng: Random): VarRef? {
                        completionAttempt = true
                        return InputOrder.pick(session, rng)
                    }
                },
                valueSelector = IndomainMin,
                randomSeed = 3L,
                maxDecisions = 64L,
                branchObserver = object : SearchBranchAttemptObserver {
                    override fun onAttempt(decision: SearchDecision, context: SearchContext) {
                        attempted.add(decision)
                    }
                },
                lpPlan = LpPlan(bounding = true, rootBudgetMillis = 0L, rootCutHarvest = false,
                    boundMaxOpsPerNode = 0L, componentSplit = false),
                lpDirectionalGains = true,
                lpDirectionalAdvice = requested != null,
                componentFactory = { listOf(object : SearchComponent {
                    override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
                        if (!completionAttempt) return ComponentResult.Consistent
                        completionAttempt = false
                        return ComponentResult.Conflict()
                    }
                    override fun retract(decisionLevel: Int) { completionAttempt = false }
                    override fun check(context: SearchContext): ComponentCheck = ComponentCheck.Infeasible()
                }) },
            )
            val objective = LinearObjective(intCoefficients = longArrayOf(
                6L, 6L, 11L, 5L, 5L, 9L, 40L, 40L, 50L,
            ))
            val scenario = PortfolioScenario(1, 1, Kind.COP, EngineMix.BACKTRACK, seed = 3L,
                btPool = listOf { BacktrackRecipe("probe-child-order") { _, _ -> params } })
            val prepared = problem.prepareExecutionPlan(objective, preferFinite = true)
            val cancellation = Cancellation { attempted.size >= 2 }
            FinitePipeline.portfolioExecutor(
                problem, scenario, objective, null, null, null, null,
                prepared, cancellation, numericalCompletion = false, lpDirectionalAdvice = requested,
            ).use { invocation ->
                val result = invocation.minimize(cancellation, null)

                val stats = result.stats.lp
                val observations = stats.phases.getValue("DIRECTIONAL_BRANCH_NODE").outcomes
                val diagnostic = "$observations; probes=${stats.phases["DIRECTIONAL_PROBES_NODE"]}"
                val second = if (applyAdvice) SearchDecision.IntAtLeast(7, 1) else SearchDecision.IntAtMost(5, 0)
                assertEquals(listOf(SearchDecision.IntAtMost(2, 0), second), attempted.take(2), diagnostic)
                assertEquals(1L, observations["VARIABLE_BASIS_VERIFICATION_ATTEMPT"], diagnostic)
                assertEquals(1L, observations["VARIABLE_PROBE_ATTEMPT"], diagnostic)
                assertEquals(1L, observations["PROBE_SIDES_PREPARED"], diagnostic)
                assertEquals(if (applyAdvice) 1L else 0L, observations["PROBE_SIDE_CONSUMED"] ?: 0L, diagnostic)
                assertEquals(if (applyAdvice) 0L else 1L, observations["PROBE_SIDE_WITHHELD"] ?: 0L, diagnostic)
                assertEquals(if (applyAdvice) 1L else 0L, observations["PROBE_SIDE_BASELINE_CHANGED"] ?: 0L, diagnostic)
                assertEquals(if (applyAdvice) 1L else 0L, observations["VARIABLE_PROBE_CONSUMED"] ?: 0L, diagnostic)
                assertEquals(if (applyAdvice) 0L else 1L, observations["VARIABLE_PROBE_WITHHELD"] ?: 0L, diagnostic)
                assertEquals(0L, observations["HISTORY_SIDE_CONSUMED"] ?: 0L, diagnostic)
                assertEquals(0L, observations["BASIS_SIDE_CONSUMED"] ?: 0L, diagnostic)
                val probes = stats.phases.getValue("DIRECTIONAL_PROBES_NODE")
                assertEquals(1L, probes.calls, diagnostic)
                assertEquals(4L, probes.outcomes["CHILDREN_COMPLETED"], diagnostic)
                queryWork += probes.work
            }
        }
        assertEquals(listOf(queryWork[0], queryWork[0], queryWork[0]), queryWork)
    }

    @Test
    fun `assumption reseeding clears the finite lp history scope`() {
        val problem = Problem(2, 0, emptyArray(), arrayOf<Factor>(
            Cardinality(intArrayOf(Lit.make(0, true), Lit.make(1, true)), min = 1, max = 2),
        )).bake()
        val params = BacktrackParams(randomSeed = 3L, lpDirectionalGains = true,
            lpPlan = LpPlan(bounding = true, rootBudgetMillis = 0L, rootCutHarvest = false))
        val objective = LinearObjective(boolWeights = longArrayOf(1L, 1L))
        ResumableMinimize(BacktrackSolver(problem), objective, params, rebindable = true,
            rootBasisGuidance = false).use { search ->
            assertNotNull(search.runSlice(Cancellation.Never, 1000L, 1000L) {})
            val before = search.stats.lp.phases.getValue("DIRECTIONAL_BRANCH_NODE").outcomes["RESET"] ?: 0L

            search.rebind(Assumptions.None.withBool(0, false), 1000L)

            val after = search.stats.lp.phases.getValue("DIRECTIONAL_BRANCH_NODE").outcomes.getValue("RESET")
            assertEquals(before + 1L, after)
        }
    }
}
