package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.IndomainMax
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.LpEngineFactory
import com.eignex.klause.lp.engine.LpFloatAllowance
import com.eignex.klause.lp.engine.LpFloatTermination
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpSolveCursor
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.klause.lp.engine.PersistentLpSolver
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.lp.engine.RecordingLpEngineFactory
import com.eignex.klause.lp.engine.ResumableLpSolver
import com.eignex.klause.lp.engine.TableauCutSolver
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.search.SearchBranchAttemptObserver
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.util.ExecutionControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResumableMinimizeGuidanceTest {
    @Test
    fun `component repair revisits earlier pins before passing root coordinates to search`() {
        val problem = Problem(0, 8, Array(8) { IntDomain(0, if (it % 4 == 0) 2 else 1) },
            (0 until 2).flatMap { group ->
                listOf(1 to 2, 2 to 3, 3 to 1).flatMap { (a, b) ->
                    val variables = intArrayOf(4 * group, 4 * group + a, 4 * group + b)
                    listOf(Linear(intArrayOf(1, 1, 1), variables, LinearOp.GE, 2),
                        Linear(intArrayOf(1, -1, -1), variables, LinearOp.GE, 0))
                }
            }).bake()
        val objective = LinearObjective(intCoefficients = LongArray(8) { if (it % 4 == 0) 1L else 0L })
        val factory = RecordingLpEngineFactory()
        val params = BacktrackParams(variableSelector = InputOrder, valueSelector = IndomainMax,
            lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                rootCutHarvest = false, boundMaxDepth = -1))
        val offered = ArrayList<Sample>()
        BacktrackSolver(problem, LpSolveContext(factory)).resumable(objective, params).use { search ->
            search.runSlice(ExecutionControl { offered.isNotEmpty() }, Long.MAX_VALUE,
                100_000L) { offered += it.sample }

            val sample = offered.single()
            assertEquals(2L, sample.ints[0])
            assertEquals(2L, sample.ints[4])
            assertTrue(sample.ints.indices.all { sample.ints[it] in 0L..(if (it % 4 == 0) 2L else 1L) })
            assertTrue(problem.factors.all { factor ->
                val linear = factor as Linear
                val row = assertNotNull(linear.integerConstants)
                assertEquals(LinearOp.LE, linear.op)
                linear.vars.indices.sumOf { row.coeff(it) * sample.ints[linear.vars[it]] } <= row.bound
            })
            assertEquals(2, factory.floatAllowances.size)
            assertEquals(0.0, search.stats.lp.nodePasses.sum)
            assertEquals(2L, search.stats.lp.phases["PRIMAL_COMPONENT_REPAIR_ROOT"]
                ?.outcomes?.get("COMPLETED"))
        }
    }

    @Test
    fun `returned fractional child targets round before their coordinates pass to search`() {
        val problem = Problem(0, 8, Array(8) { IntDomain(0, if (it == 4) 2 else 1) },
            (0 until 2).flatMap { group ->
                (0 until 8).map { mask ->
                    val coefficients = IntArray(4) { v ->
                        if (v == 0) {
                            if (group == 0) 2 else -2
                        } else if (mask and (1 shl (v - 1)) == 0) 1 else -1
                    }
                    Linear(coefficients, IntArray(4) { 4 * group + it }, LinearOp.GE,
                        (if (group == 0) 2 else -2) - coefficients.drop(1).count { it < 0 })
                }
            }).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 0, 0, 0, -1, 0, 0, 0))
        var queries = 0
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel, cancellation: ExecutionControl, refactorUpdateLimit: Int, iterationLimit: Int,
                workLimit: Long, trackDegeneracy: Boolean, pricing: LpPricingOptions,
            ): PersistentLpSolver {
                val delegate = ProductionLpEngineFactory.newPersistentSolver(model, cancellation,
                    refactorUpdateLimit, iterationLimit, workLimit, trackDegeneracy, pricing)
                val unfinished = (0 until model.numVars).any { model.costD(it) < 0.0 }
                return object : PersistentLpSolver by delegate {
                    private var charged = 0L
                    override val lastMetrics: LpSolveMetrics get() =
                        delegate.lastMetrics.copy(workOps = delegate.lastMetrics.workOps + charged)

                    override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? =
                        delegate.resolveBounds(if (unfinished) {
                            LpFloatAllowance(allowance?.work ?: 0L, 1)
                        } else allowance).also {
                            queries++
                            if (unfinished) charged = 5_000L * LP_WORK_PER_NODE
                        }
                }
            }
        }
        val params = BacktrackParams(variableSelector = InputOrder, valueSelector = IndomainMax,
            lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                rootCutHarvest = false, boundMaxDepth = -1))
        val offered = ArrayList<Sample>()
        val stopAtPoint = ExecutionControl { offered.isNotEmpty() }
        BacktrackSolver(problem, LpSolveContext(factory)).resumable(objective, params).use { search ->
            search.runSlice(stopAtPoint, Long.MAX_VALUE, 5_000L) { offered += it.sample }
            assertTrue(offered.isEmpty())
            assertEquals(2, queries)

            search.runSlice(stopAtPoint, Long.MAX_VALUE, 100_000L) { offered += it.sample }

            val sample = offered.single()
            assertEquals(listOf(1L, 1L, 1L, 1L), sample.ints.take(4))
            assertTrue(sample.ints.indices.all {
                sample.ints[it] in 0L..(if (it == 4) 2L else 1L)
            })
            assertTrue(problem.factors.all { factor ->
                val linear = factor as Linear
                val row = assertNotNull(linear.integerConstants)
                assertEquals(LinearOp.LE, linear.op)
                linear.vars.indices.sumOf { row.coeff(it) * sample.ints[linear.vars[it]] } <= row.bound
            })
            assertEquals(2, queries)
            assertEquals(0.0, search.stats.lp.nodePasses.sum)
            assertEquals(1L, search.stats.lp.phases["PRIMAL_LP_GUIDANCE_ROOT"]
                ?.outcomes?.get("PARTIAL_REUSED"))
            assertEquals(1L, search.stats.lp.phases["PRIMAL_COMPONENT_ROUNDING_ROOT"]
                ?.outcomes?.get("HANDOFF_TRIAL_PREPARED"))
            assertEquals(2L, search.stats.lp.phases["PRIMAL_COMPONENT_ROUNDING_ROOT"]
                ?.outcomes?.get("GROUP_COMPLETED"))
            assertEquals(1L, search.stats.lp.phases["PRIMAL_PROPOSAL_ROOT"]?.outcomes?.get("IMPROVING"))
        }
    }

    @Test
    fun `complete root components round before their coordinates pass to search`() {
        val problem = Problem(0, 8, Array(8) { IntDomain(0, if (it == 4) 2 else 1) },
            (0 until 2).flatMap { group ->
                (0 until 8).map { mask ->
                    val coefficients = IntArray(4) { v ->
                        if (v == 0) {
                            if (group == 0) 2 else -2
                        } else if (mask and (1 shl (v - 1)) == 0) 1 else -1
                    }
                    Linear(coefficients, IntArray(4) { 4 * group + it }, LinearOp.GE,
                        (if (group == 0) 2 else -2) - coefficients.drop(1).count { it < 0 })
                }
            }).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 0, 0, 0, -1, 0, 0, 0))
        val factory = RecordingLpEngineFactory()
        val params = BacktrackParams(variableSelector = InputOrder, valueSelector = IndomainMax,
            lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                rootCutHarvest = false, boundMaxDepth = -1))
        val offered = ArrayList<Sample>()
        BacktrackSolver(problem, LpSolveContext(factory)).resumable(objective, params).use { search ->

            search.runSlice(ExecutionControl { offered.isNotEmpty() }, Long.MAX_VALUE, -1L) { offered += it.sample }

            assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 0L, 0L, 0L), offered.single().ints.toList())
            assertEquals(2, factory.floatAllowances.size)
            assertEquals(2L, search.stats.lp.phases["PRIMAL_COMPONENT_ROUNDING_ROOT"]
                ?.outcomes?.get("GROUP_COMPLETED"))
            assertEquals(1L, search.stats.lp.phases["PRIMAL_COMPONENT_ROUNDING_ROOT"]
                ?.outcomes?.get("HANDOFF_TRIAL_PREPARED"))
            assertEquals(1L, search.stats.lp.phases["PRIMAL_PROPOSAL_ROOT"]?.outcomes?.get("IMPROVING"))
        }
    }

    @Test
    fun `restarted component traversal obtains fresh root preferences before its first point`() {
        val factors = (0 until 8).map { mask ->
            val coefficients = IntArray(4) { v ->
                if (v == 0) 2 else if (mask and (1 shl (v - 1)) == 0) 1 else -1
            }
            Linear(coefficients, IntArray(4) { it }, LinearOp.GE, 2 - coefficients.count { it < 0 })
        } + listOf(
            Linear(intArrayOf(1, 1), intArrayOf(4, 5), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(6, 7), LinearOp.GE, 1),
        )
        val problem = Problem(0, 8, Array(8) { IntDomain(0, 1) }, factors).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 0, 0, 0, 1, 2, 1, 2))
        var queries = 0
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel, cancellation: ExecutionControl, refactorUpdateLimit: Int, iterationLimit: Int,
                workLimit: Long, trackDegeneracy: Boolean, pricing: LpPricingOptions,
            ): PersistentLpSolver {
                val delegate = ProductionLpEngineFactory.newPersistentSolver(model, cancellation,
                    refactorUpdateLimit, iterationLimit, workLimit, trackDegeneracy, pricing)
                return object : PersistentLpSolver by delegate {
                    private var charged = 0L
                    override val lastMetrics: LpSolveMetrics get() =
                        delegate.lastMetrics.copy(workOps = delegate.lastMetrics.workOps + charged)

                    override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? =
                        delegate.resolveBounds(LpFloatAllowance(allowance?.work ?: 0L, 1)).also {
                            if (++queries == 3) charged = 5_000L * LP_WORK_PER_NODE
                        }
                }
            }
        }
        val params = BacktrackParams(variableSelector = InputOrder, valueSelector = IndomainMin,
            phaseSaving = false, targetPhasing = false, solutionPhasing = false, lubyRestartBase = 1,
            lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true, rootCutHarvest = false))
        BacktrackSolver(problem, LpSolveContext(factory)).resumable(objective, params).use { search ->
            var firstRefreshes: Long? = null
            val stopAtPoint = ExecutionControl { firstRefreshes != null }
            search.runSlice(stopAtPoint, Long.MAX_VALUE, 5_000L) {
                firstRefreshes = 0L
            }
            assertNull(firstRefreshes)

            val result = search.runSlice(stopAtPoint, Long.MAX_VALUE, 100_000L) {
                firstRefreshes = search.stats.lp.phases["PRIMAL_RESTART_GUIDANCE_ROOT"]
                    ?.outcomes?.get("RETAINED") ?: 0L
            }

            val diagnostic = if (firstRefreshes == null || firstRefreshes == 0L) {
                val phases = search.stats.lp.phases.filterKeys {
                    it.contains("RESTART") || it.contains("COMPONENT") || it.contains("GUIDANCE") ||
                        it.contains("PRIMAL_CONTINUATION")
                }.mapValues { it.value.outcomes }
                "result=${result?.let { it::class.simpleName }}, queries=$queries, " +
                    "nodes=${search.stats.search.nodes.sum}, nodeLp=${search.stats.lp.nodePasses.sum}, phases=$phases"
            } else null
            assertTrue(assertNotNull(firstRefreshes, diagnostic) > 0L, diagnostic)
            assertTrue((search.stats.lp.phases["PRIMAL_RESTART_REPLAY_ROOT"]
                ?.outcomes?.get("RETAINED") ?: 0L) > 0L)
        }
    }

    @Test
    fun `retained component traversal defers node lp until the first incumbent`() {
        val problem = Problem(0, 10, Array(10) { IntDomain(0, 1) }, listOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(2, 4), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(0, 4), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(1, 3), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(3, 5), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(1, 5), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(6, 7), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(8, 9), LinearOp.GE, 1),
        )).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(2, 2, 2, 2, 3, 3, 0, 0, 0, 0))
        var queries = 0
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel, cancellation: ExecutionControl, refactorUpdateLimit: Int, iterationLimit: Int,
                workLimit: Long, trackDegeneracy: Boolean, pricing: LpPricingOptions,
            ): PersistentLpSolver {
                val delegate = ProductionLpEngineFactory.newPersistentSolver(model, cancellation,
                    refactorUpdateLimit, iterationLimit, workLimit, trackDegeneracy, pricing)
                return object : PersistentLpSolver by delegate {
                    private var charged = 0L
                    override val lastMetrics: LpSolveMetrics get() =
                        delegate.lastMetrics.copy(workOps = delegate.lastMetrics.workOps + charged)

                    override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? =
                        delegate.resolveBounds(LpFloatAllowance(allowance?.work ?: 0L, 1)).also {
                            if (++queries == 3) charged = 5_000L * LP_WORK_PER_NODE
                        }
                }
            }
        }
        val samples = ArrayList<Sample>()
        val params = BacktrackParams(variableSelector = InputOrder, valueSelector = IndomainMin,
            phaseSaving = false, targetPhasing = false, solutionPhasing = false,
            lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true, rootCutHarvest = false))
        BacktrackSolver(problem, LpSolveContext(factory)).resumable(objective, params).use { search ->
            var firstPasses = Double.NaN
            var firstDeferrals = 0L
            val observed = ExecutionControl { samples.isNotEmpty() && search.stats.lp.nodePasses.sum > 0.0 }
            search.runSlice(observed, Long.MAX_VALUE, 5_000L) { samples += it.sample }
            assertTrue(samples.isEmpty())

            search.runSlice(observed, Long.MAX_VALUE, 100_000L) {
                if (samples.isEmpty()) {
                    firstPasses = search.stats.lp.nodePasses.sum
                    firstDeferrals = search.stats.lp.phases["PRIMAL_COMPONENT_ORDER_ROOT"]
                        ?.outcomes?.get("NODE_QUERY_DEFERRED") ?: 0L
                }
                samples += it.sample
            }

            assertEquals(10.0, objective.evaluate(samples.first()))
            assertEquals(0.0, firstPasses)
            assertTrue(firstDeferrals > 0L)
            assertTrue(search.stats.lp.nodePasses.sum > 0.0)
        }
    }

    @Test
    fun `retained guides complete one declared component before starting another`() {
        val problem = Problem(0, 6, Array(6) { IntDomain(0, 1) }, listOf(
            Linear(intArrayOf(1, 1, 1), intArrayOf(0, 2, 4), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1, 1), intArrayOf(1, 3, 5), LinearOp.GE, 1),
        )).bake()
        val objective = LinearObjective(intCoefficients = LongArray(6) { 1L })
        val attempts = ArrayList<Int>()
        val offered = ArrayList<Sample>()
        val params = BacktrackParams(variableSelector = InputOrder, valueSelector = IndomainMax,
            phaseSaving = false, targetPhasing = false, solutionPhasing = false,
            lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                rootCutHarvest = false, boundMaxDepth = -1),
            branchObserver = object : SearchBranchAttemptObserver {
                override fun onAttempt(decision: SearchDecision, context: SearchContext) {
                    when (decision) {
                        is SearchDecision.IntAtMost -> attempts.add(decision.variable)
                        is SearchDecision.IntAtLeast -> attempts.add(decision.variable)
                        else -> error("Expected an integer decision")
                    }
                }
            })
        BacktrackSolver(problem).resumable(objective, params).use { search ->
            search.runSlice(ExecutionControl { offered.isNotEmpty() }, Long.MAX_VALUE, -1L) { offered += it.sample }

            assertEquals(listOf(0, 2), attempts.take(2))
            assertTrue(assertNotNull(search.stats.lp.phases["PRIMAL_COMPONENT_ORDER_ROOT"]).work > 0L)
            val sample = offered.single()
            assertTrue(sample.ints[0] + sample.ints[2] + sample.ints[4] >= 1L)
            assertTrue(sample.ints[1] + sample.ints[3] + sample.ints[5] >= 1L)
            assertEquals(1L, search.stats.lp.phases["PRIMAL_COMPONENT_ORDER_ROOT"]
                ?.outcomes?.get("COMPONENT_PICK_OFFERED"))
        }
    }

    @Test
    fun `root rounding outside the run assumptions should not become an incumbent`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1))).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))
        val params = BacktrackParams(assumptions = Assumptions(ints = mapOf(0 to 0L)),
            lpPlan = LpPlan(bounding = true, probe = true, rootCutHarvest = false))
        val offered = ArrayList<Sample>()

        BacktrackSolver(problem).resumable(objective, params).use { search ->
            search.runSlice(ExecutionControl { offered.isNotEmpty() }, Long.MAX_VALUE, -1L) { offered += it.sample }

            assertEquals(listOf(0L, 1L), offered.first().ints.toList())
            assertTrue((search.stats.lp.phases["PRIMAL_PROPOSAL_ROOT"]?.outcomes?.get("PINS_REJECTED") ?: 0L) > 0L)
        }
    }

    @Test
    fun `ordinary search backtracks from root value targets without another node lp query`() {
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 1) }, listOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
        )).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(2, 2, 3))
        for (branching in listOf(false, true)) {
            val attempts = mutableListOf<SearchDecision>()
            val offers = mutableListOf<Double>()
            val params = BacktrackParams(variableSelector = InputOrder, valueSelector = IndomainMax,
                phaseSaving = false, targetPhasing = false, solutionPhasing = false,
                lpPlan = LpPlan(bounding = true, probe = true, branching = branching,
                    componentOwnership = true, rootCutHarvest = false, boundMaxDepth = -1),
                branchObserver = object : SearchBranchAttemptObserver {
                    override fun onAttempt(decision: SearchDecision, context: SearchContext) {
                        attempts.add(decision)
                    }
                })

            BacktrackSolver(problem).resumable(objective, params).use { search ->
                search.runSlice(ExecutionControl.Never, Long.MAX_VALUE, -1L) { offers.add(it.objective) }

                val expected = if (branching) SearchDecision.IntAtMost(0, 0L)
                else SearchDecision.IntAtLeast(0, 1L)
                assertEquals(expected, attempts.first())
                assertEquals(listOf(5.0, 4.0), offers)
                val guidance = search.stats.lp.phases["PRIMAL_VALUE_GUIDANCE_ROOT"]?.outcomes
                assertEquals(if (branching) 1L else null, guidance?.get("VALUE_ORDER_OFFERED"))
                assertEquals(0.0, search.stats.lp.nodePasses.sum)
                if (branching) {
                    assertNull(search.stats.lp.phases["PRIMAL_PROPOSAL_ROOT"])
                    assertEquals(1L, search.stats.lp.phases["PRIMAL_CP_GUIDANCE_ROOT"]
                        ?.outcomes?.get("SEARCH_HANDOFF"))
                }
            }
        }
    }

    @Test
    fun `returned child targets reach complete search without another root query`() {
        val problem = Problem(0, 7, Array(7) { IntDomain(0, 1) }, listOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(3, 4), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(5, 6), LinearOp.GE, 1),
        )).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1, 1, 1, 2, 1, 2))
        var queries = 0
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel, cancellation: ExecutionControl, refactorUpdateLimit: Int, iterationLimit: Int,
                workLimit: Long, trackDegeneracy: Boolean, pricing: LpPricingOptions,
            ): PersistentLpSolver {
                val delegate = ProductionLpEngineFactory.newPersistentSolver(model, cancellation,
                    refactorUpdateLimit, iterationLimit, workLimit, trackDegeneracy, pricing)
                return object : PersistentLpSolver by delegate {
                    private var charged = 0L
                    override val lastMetrics: LpSolveMetrics get() =
                        delegate.lastMetrics.copy(workOps = delegate.lastMetrics.workOps + charged)

                    override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? =
                        delegate.resolveBounds(LpFloatAllowance(allowance?.work ?: 0L, 1)).also {
                            if (++queries == 3) charged = 5_000L * LP_WORK_PER_NODE
                        }
                }
            }
        }
        val params = BacktrackParams(variableSelector = InputOrder, valueSelector = IndomainMax,
            lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                rootCutHarvest = false, boundMaxDepth = -1))
        val offered = ArrayList<Sample>()
        val stopAtWitness = ExecutionControl { offered.isNotEmpty() }
        BacktrackSolver(problem, LpSolveContext(factory)).resumable(objective, params).use { search ->
            search.runSlice(stopAtWitness, Long.MAX_VALUE, 5_000L) { offered += it.sample }
            assertTrue(offered.isEmpty())
            assertEquals(3, queries)

            search.runSlice(stopAtWitness, Long.MAX_VALUE, 100_000L) { offered += it.sample }

            val sample = offered.single()
            assertTrue(sample.ints.all { it in 0L..1L } &&
                listOf(0 to 1, 1 to 2, 0 to 2, 3 to 4, 5 to 6).all { (left, right) ->
                    sample.ints[left] + sample.ints[right] >= 1L
                })
            assertEquals(3, queries)
            assertEquals(0.0, search.stats.lp.nodePasses.sum)
            assertNull(search.stats.lp.phases["PRIMAL_PROPOSAL_ROOT"])
            assertEquals(1L, search.stats.lp.phases["PRIMAL_CP_GUIDANCE_ROOT"]
                ?.outcomes?.get("SEARCH_HANDOFF"))
            assertEquals(1L, search.stats.lp.phases["PRIMAL_VALUE_GUIDANCE_ROOT"]
                ?.outcomes?.get("VALUE_ORDER_OFFERED"))
        }
    }

    @Test
    fun `completed automatic root points reach search before private pinning`() {
        for (mode in listOf("automatic", "explicit", "zero-decisions", "no-branching")) {
            val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
                listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)),
                settings = ProblemSettings(lpMaxTableauCells = 1L, lpCeilingTableauCells = 1L)).bake()
            val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))
            var queries = 0
            var firstCompletionQuery: Int? = null
            val factory = object : LpEngineFactory by ProductionLpEngineFactory {
                override fun newPersistentSolver(
                    model: LpModel, cancellation: ExecutionControl, refactorUpdateLimit: Int, iterationLimit: Int,
                    workLimit: Long, trackDegeneracy: Boolean, pricing: LpPricingOptions,
                ): PersistentLpSolver {
                    val delegate = ProductionLpEngineFactory.newPersistentSolver(model, cancellation,
                        refactorUpdateLimit, iterationLimit, workLimit, trackDegeneracy, pricing)
                    return object : PersistentLpSolver by delegate, ResumableLpSolver {
                        private var pausedWork = 0L
                        override val currentWork: Long get() = pausedWork + delegate.lastMetrics.workOps
                        override val lastMetrics: LpSolveMetrics get() =
                            delegate.lastMetrics.copy(workOps = currentWork)
                        override fun openSolve(
                            allowance: LpFloatAllowance?, retainFundingStop: Boolean,
                        ): LpSolveCursor<FloatLpResult?> = LpSolveCursor {
                            queries++
                            repeat(2) {
                                pausedWork += 5_000L * LP_WORK_PER_NODE
                                pauseAt(pausedWork)
                            }
                            delegate.resolveBounds(allowance).also {
                                if (it?.optimal == true && firstCompletionQuery == null) firstCompletionQuery = queries
                            }
                        }
                    }
                }
            }
            val params = BacktrackParams(maxDecisions = if (mode == "zero-decisions") 0L else 16L,
                variableSelector = InputOrder, valueSelector = IndomainMax,
                lpConfig = if (mode == "explicit") LpConfig.parse("default,+sparse-trial") else LpConfig.AUTO,
                lpPlan = LpPlan(rootCutHarvest = false, branching = mode != "no-branching"))
            val offered = ArrayList<Sample>()
            val stopAtWitness = ExecutionControl { offered.isNotEmpty() }
            BacktrackSolver(problem, LpSolveContext(factory)).resumable(objective, params).use { search ->
                repeat(6) {
                    search.runSlice(stopAtWitness, Long.MAX_VALUE, 5_000L) { offered += it.sample }
                }

                assertEquals(listOf(1L, 0L), offered.single().ints.toList(), mode)
                val handoff = search.stats.lp.phases["PRIMAL_ROUNDING_ROOT"]?.outcomes?.get("SEARCH_HANDOFF")
                assertEquals(if (mode == "automatic") 1L else null, handoff, mode)
                assertEquals(1, firstCompletionQuery, mode)
                if (mode == "automatic") {
                    assertNull(search.stats.lp.phases["PRIMAL_PROPOSAL_ROOT"])
                    assertEquals(1L, search.stats.lp.phases["PRIMAL_VALUE_GUIDANCE_ROOT"]
                        ?.outcomes?.get("VALUE_ORDER_OFFERED"))
                    assertEquals(1L, search.stats.lp.phases["PRIMAL_VALUE_GUIDANCE_ROOT"]
                        ?.outcomes?.get("SOURCE_ROOT_VALUE_ORDER_OFFERED"))
                } else assertNotNull(search.stats.lp.phases["PRIMAL_PROPOSAL_ROOT"], mode)
            }
        }
    }

    @Test
    fun `completed automatic root points preserve a prepared private component trial`() {
        val problem = Problem(0, 5, Array(5) { IntDomain(0, 1) }, listOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(3, 4), LinearOp.GE, 1),
        ), settings = ProblemSettings(lpMaxTableauCells = 1L, lpCeilingTableauCells = 1L)).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1, 1, 1, 2))
        var queries = 0
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel, cancellation: ExecutionControl, refactorUpdateLimit: Int, iterationLimit: Int,
                workLimit: Long, trackDegeneracy: Boolean, pricing: LpPricingOptions,
            ): PersistentLpSolver {
                val delegate = ProductionLpEngineFactory.newPersistentSolver(model, cancellation,
                    refactorUpdateLimit, iterationLimit, workLimit, trackDegeneracy, pricing)
                return object : PersistentLpSolver by delegate, ResumableLpSolver {
                    private var pausedWork = 0L
                    override val currentWork: Long get() = pausedWork + delegate.lastMetrics.workOps
                    override val lastMetrics: LpSolveMetrics get() = delegate.lastMetrics.copy(workOps = currentWork)
                    override fun openSolve(
                        allowance: LpFloatAllowance?, retainFundingStop: Boolean,
                    ): LpSolveCursor<FloatLpResult?> = LpSolveCursor {
                        queries++
                        repeat(2) {
                            pausedWork += 5_000L * LP_WORK_PER_NODE
                            pauseAt(pausedWork)
                        }
                        delegate.resolveBounds(allowance)
                    }
                }
            }
        }
        val params = BacktrackParams(maxDecisions = 16L, variableSelector = InputOrder,
            valueSelector = IndomainMax, lpConfig = LpConfig.AUTO,
            lpPlan = LpPlan(rootCutHarvest = false, boundMaxDepth = -1))
        val offered = ArrayList<Sample>()
        BacktrackSolver(problem, LpSolveContext(factory)).resumable(objective, params).use { search ->
            repeat(6) {
                search.runSlice(ExecutionControl { offered.isNotEmpty() }, Long.MAX_VALUE, 5_000L) {
                    offered += it.sample
                }
            }

            val sample = offered.single()
            assertTrue(sample.ints.all { it in 0L..1L } &&
                listOf(0 to 1, 1 to 2, 0 to 2, 3 to 4).all { (left, right) ->
                    sample.ints[left] + sample.ints[right] >= 1L
                })
            assertEquals(1, queries)
            assertEquals(1L, search.stats.lp.phases["PRIMAL_COMPONENT_ROUNDING_ROOT"]
                ?.outcomes?.get("HANDOFF_TRIAL_PREPARED"))
            assertEquals(1L, search.stats.lp.phases["PRIMAL_CP_GUIDANCE_ROOT"]?.outcomes?.get("REUSED"))
        }
    }

    @Test
    fun `paused root queries finish before the worker starts discrete traversal`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)),
            settings = ProblemSettings(lpCeilingTableauCells = 1L)).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))
        var constructions = 0
        var queries = 0
        val published = ArrayList<Double>()
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel, cancellation: ExecutionControl, refactorUpdateLimit: Int, iterationLimit: Int,
                workLimit: Long, trackDegeneracy: Boolean, pricing: LpPricingOptions,
            ): PersistentLpSolver {
                constructions++
                val delegate = ProductionLpEngineFactory.newPersistentSolver(model, cancellation,
                    refactorUpdateLimit, iterationLimit, workLimit, trackDegeneracy, pricing)
                return object : PersistentLpSolver by delegate, ResumableLpSolver {
                    private var pausedWork = 0L
                    override val currentWork: Long get() = pausedWork + delegate.lastMetrics.workOps
                    override val lastMetrics: LpSolveMetrics get() =
                        delegate.lastMetrics.copy(workOps = currentWork)
                    override fun openSolve(
                        allowance: LpFloatAllowance?, retainFundingStop: Boolean,
                    ): LpSolveCursor<FloatLpResult?> = LpSolveCursor {
                        queries++
                        repeat(2) {
                            pausedWork += 5_000L * LP_WORK_PER_NODE
                            pauseAt(pausedWork)
                        }
                        delegate.resolveBounds(allowance)
                    }
                }
            }
        }
        val params = BacktrackParams(maxDecisions = 0L,
            objectiveLowerBoundSink = published::add,
            lpConfig = LpConfig.parse("default,+sparse-trial"),
            lpPlan = LpPlan(rootCutHarvest = false))
        val offered = ArrayList<Sample>()
        val stopAtWitness = ExecutionControl { offered.isNotEmpty() }
        val accept: (MinimizeResult.WithSample) -> Unit = {
            assertEquals(1, queries)
            assertEquals(1, constructions)
            offered += it.sample
        }

        BacktrackSolver(problem, LpSolveContext(factory)).resumable(objective, params).use { search ->
            search.runSlice(stopAtWitness, Long.MAX_VALUE, 5_000L, accept)
            search.runSlice(stopAtWitness, Long.MAX_VALUE, 100_000L, accept)
            search.runSlice(stopAtWitness, Long.MAX_VALUE, 100_000L, accept)
            assertEquals(1L, search.stats.lp.phases["CLAIM_CONSUMPTION_ROOT"]?.outcomes?.get("BOUND"))
        }

        assertEquals(listOf(1L, 0L), offered.single().ints.toList())
        assertEquals(1, queries)
        assertEquals(1, constructions)
        assertEquals(listOf(1.0), published)
    }

    @Test
    fun `automatic sparse handoff waits for an improvement over its first checked floor`() {
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.GE, 2)),
            settings = ProblemSettings(lpMaxTableauCells = 1L, lpCeilingTableauCells = 1L)).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 2, 3))
        var pauses = 0
        val published = ArrayList<Double>()
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel, cancellation: ExecutionControl, refactorUpdateLimit: Int, iterationLimit: Int,
                workLimit: Long, trackDegeneracy: Boolean, pricing: LpPricingOptions,
            ): PersistentLpSolver {
                val delegate = ProductionLpEngineFactory.newPersistentSolver(model, cancellation,
                    refactorUpdateLimit, iterationLimit, workLimit, trackDegeneracy, pricing)
                return object : PersistentLpSolver by delegate, ResumableLpSolver {
                    private var pausedWork = 0L
                    override val currentWork: Long get() = pausedWork
                    override val lastMetrics: LpSolveMetrics get() = LpSolveMetrics(workOps = pausedWork)
                    override fun pausedDualProposal(cancellation: ExecutionControl): DoubleArray? =
                        if (cancellation()) null else DoubleArray(model.m) { if (pauses >= 2) -2.0 else -1.0 }
                    override fun openSolve(
                        allowance: LpFloatAllowance?, retainFundingStop: Boolean,
                    ): LpSolveCursor<FloatLpResult?> = LpSolveCursor {
                        while (true) {
                            pauses++
                            pausedWork += 5_000L * LP_WORK_PER_NODE
                            pauseAt(pausedWork)
                        }
                        @Suppress("UNREACHABLE_CODE")
                        null
                    }
                }
            }
        }
        val params = BacktrackParams(maxDecisions = 16L, variableSelector = InputOrder,
            valueSelector = IndomainMin, lpConfig = LpConfig.AUTO, objectiveLowerBoundSink = published::add,
            lpPlan = LpPlan(rootCutHarvest = false))
        val offered = ArrayList<Sample>()
        BacktrackSolver(problem, LpSolveContext(factory)).resumable(objective, params).use { search ->
            repeat(2) {
                search.runSlice(ExecutionControl.Never, Long.MAX_VALUE, 5_000L) { offered += it.sample }
            }
            assertTrue(offered.isEmpty())
            assertTrue(published.any { it == 2.0 })

            repeat(4) {
                search.runSlice(ExecutionControl.Never, Long.MAX_VALUE, 5_000L) { offered += it.sample }
            }

            assertEquals(1L, search.stats.lp.phases.getValue("PRIMAL_CONTINUATION_ROOT")
                .outcomes["BOUND_SEARCH_HANDOFF"])
            assertTrue(published.any { it == 3.0 })
            assertTrue(offered.isNotEmpty())
        }
    }

    @Test
    fun `automatic sparse handoff defers only for fresh funded root progress`() {
        for (observation in listOf("fresh", "missing", "capped", "stale-sample", "stale-work",
            "phase-change", "ambiguous-phase", "invalid-phase", "unknown-phase", "stagnant",
            "invalid-violation", "no-op", "tall", "exhausted")) {
            val rows = listOf(Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.GE, 2)) +
                if (observation == "tall" || observation == "exhausted") List(7) {
                    Linear(intArrayOf(1, it + 2, it + 3), intArrayOf(0, 1, 2), LinearOp.GE, 2)
                } else emptyList()
            val problem = Problem(0, 3, Array(3) { IntDomain(0, 1) },
                rows,
                settings = ProblemSettings(lpMaxTableauCells = 1L, lpCeilingTableauCells = 1L)).bake()
            val objective = LinearObjective(intCoefficients = longArrayOf(1, 2, 3))
            var pauses = 0
            var queries = 0
            val published = ArrayList<Double>()
            val factory = object : LpEngineFactory by ProductionLpEngineFactory {
                override fun newPersistentSolver(
                    model: LpModel, cancellation: ExecutionControl, refactorUpdateLimit: Int, iterationLimit: Int,
                    workLimit: Long, trackDegeneracy: Boolean, pricing: LpPricingOptions,
                ): PersistentLpSolver {
                    val delegate = ProductionLpEngineFactory.newPersistentSolver(model, cancellation,
                        refactorUpdateLimit, iterationLimit, workLimit, trackDegeneracy, pricing)
                    if (observation == "tall") assertTrue(model.m > model.n)
                    return object : PersistentLpSolver by delegate, ResumableLpSolver {
                        private var pausedWork = 0L
                        override val currentWork: Long get() = pausedWork
                        override val lastMetrics: LpSolveMetrics get() = LpSolveMetrics(workOps = pausedWork)
                        override val trialProgress: Map<String, Long> get() {
                            val count = if (observation == "no-op" && pauses >= 4) 3L else pauses.toLong()
                            if (observation == "missing" && count >= 3L) return emptyMap()
                            return buildMap {
                                put("ProgressSamples", when {
                                    count < 3L -> count
                                    observation == "capped" -> 256L
                                    observation == "stale-sample" -> 2L
                                    else -> count
                                })
                                put("NumericalWork", if (observation == "stale-work" && count >= 3L) 0L
                                else pausedWork)
                                put("LastViolatedBasics", when {
                                    observation == "invalid-violation" && count >= 3L -> model.m + 1L
                                    observation == "exhausted" -> (model.m + 1L - count).coerceAtLeast(0L)
                                    observation == "stagnant" || count < 3L -> model.m.toLong()
                                    else -> model.m - 1L
                                })
                                val phase = if (observation == "phase-change" && count >= 3L)
                                    "PRIMAL_FEASIBILITY" else "DUAL"
                                put("ProgressPhase$phase", 1L)
                                if (observation == "ambiguous-phase" && count >= 3L)
                                    put("ProgressPhaseBOXED_DUAL", 1L)
                                if (observation == "invalid-phase" && count >= 3L)
                                    put("ProgressPhaseBOXED_DUAL", 2L)
                                if (observation == "unknown-phase" && count >= 3L)
                                    put("ProgressPhaseUNKNOWN", 1L)
                            }
                        }
                        override fun pausedDualProposal(cancellation: ExecutionControl): DoubleArray? =
                            if (cancellation()) null else DoubleArray(model.m) {
                                if (it != 0) 0.0 else if (pauses >= 2) -2.0 else -1.0
                            }
                        override fun openSolve(
                            allowance: LpFloatAllowance?, retainFundingStop: Boolean,
                        ): LpSolveCursor<FloatLpResult?> {
                            queries++
                            return LpSolveCursor {
                                while (true) {
                                    pauses++
                                    if (observation != "no-op" || pauses < 4)
                                        pausedWork += 5_000L * LP_WORK_PER_NODE
                                    pauseAt(pausedWork)
                                }
                                @Suppress("UNREACHABLE_CODE")
                                null
                            }
                        }
                    }
                }
            }
            val params = BacktrackParams(maxDecisions = 16L, variableSelector = InputOrder,
                valueSelector = IndomainMin, lpConfig = LpConfig.AUTO, objectiveLowerBoundSink = published::add,
                lpPlan = LpPlan(rootCutHarvest = false,
                    rootMaxWork = if (observation == "exhausted") 22_500L * LP_WORK_PER_NODE else 200_000_000L))
            val offered = ArrayList<Sample>()
            BacktrackSolver(problem, LpSolveContext(factory)).resumable(objective, params).use { search ->
                repeat(8) {
                    if (pauses < 3) search.runSlice(ExecutionControl.Never, Long.MAX_VALUE, 5_000L) {
                        offered += it.sample
                    }
                }
                assertTrue(published.any { it == 3.0 }, observation)
                val progresses = observation in listOf("fresh", "no-op", "tall", "exhausted")
                val phase = search.stats.lp.phases.getValue("PRIMAL_CONTINUATION_ROOT")
                assertEquals(!progresses, phase.outcomes.containsKey("BOUND_SEARCH_HANDOFF"), observation)
                if (progresses) assertTrue(offered.isEmpty(), observation)

                repeat(4) {
                    search.runSlice(ExecutionControl.Never, Long.MAX_VALUE, 5_000L) { offered += it.sample }
                }

                val outcomes = search.stats.lp.phases.getValue("PRIMAL_CONTINUATION_ROOT").outcomes
                if (observation == "exhausted") {
                    assertTrue(outcomes.containsKey("PROGRESS_DEFERRED"))
                    assertEquals(5, pauses)
                } else assertEquals(1L, outcomes["BOUND_SEARCH_HANDOFF"], observation)
                assertTrue(offered.isNotEmpty(), observation)
                assertEquals(1, queries, observation)
            }
        }
    }

    @Test
    fun `checked sparse root bounds allow discrete search before a primal point`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)),
            settings = ProblemSettings(lpCeilingTableauCells = 1L)).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel, cancellation: ExecutionControl, refactorUpdateLimit: Int, iterationLimit: Int,
                workLimit: Long, trackDegeneracy: Boolean, pricing: LpPricingOptions,
            ): PersistentLpSolver {
                val delegate = ProductionLpEngineFactory.newPersistentSolver(model, cancellation,
                    refactorUpdateLimit, iterationLimit, workLimit, trackDegeneracy, pricing)
                return object : PersistentLpSolver by delegate, ResumableLpSolver {
                    private var pausedWork = 0L
                    override val currentWork: Long get() = pausedWork
                    override val lastMetrics: LpSolveMetrics get() = LpSolveMetrics(workOps = pausedWork)
                    override fun pausedDualProposal(cancellation: ExecutionControl): DoubleArray? =
                        if (cancellation()) null else DoubleArray(model.m)
                    override fun openSolve(
                        allowance: LpFloatAllowance?, retainFundingStop: Boolean,
                    ): LpSolveCursor<FloatLpResult?> = LpSolveCursor {
                        while (true) {
                            pausedWork += 5_000L * LP_WORK_PER_NODE
                            pauseAt(pausedWork)
                        }
                        @Suppress("UNREACHABLE_CODE")
                        null
                    }
                }
            }
        }
        val params = BacktrackParams(maxDecisions = 16L, variableSelector = InputOrder,
            valueSelector = IndomainMin, lpConfig = LpConfig.parse("default,+sparse-trial"),
            lpPlan = LpPlan(rootCutHarvest = false))
        val offered = ArrayList<Sample>()
        BacktrackSolver(problem, LpSolveContext(factory)).resumable(objective, params).use { search ->
            var result: MinimizeResult? = null
            repeat(3) {
                result = search.runSlice(ExecutionControl.Never, Long.MAX_VALUE, 5_000L) { offered += it.sample }
            }

            assertEquals(1L, search.stats.lp.phases.getValue("PRIMAL_CONTINUATION_ROOT")
                .outcomes["BOUND_SEARCH_HANDOFF"])
            assertEquals(listOf(listOf(0L, 1L), listOf(1L, 0L)), offered.map { it.ints.toList() })
            assertTrue(result is MinimizeResult.Optimal)
        }
    }

    @Test
    fun `sliced root rounding should resume only while its LP allowance remains`() {
        val charged = 5_000L * LP_WORK_PER_NODE
        for (allowance in listOf(charged, 2L * charged)) {
            val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) }, arrayOf<Factor>(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            )).bake()
            val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))
            var primalSolves = 0
            val factory = object : LpEngineFactory by ProductionLpEngineFactory {
                override fun newTableauSolver(
                    model: LpModel,
                    cancellation: ExecutionControl,
                    iterationLimit: Int,
                    workLimit: Long,
                    trackDegeneracy: Boolean,
                    pricing: LpPricingOptions,
                ): TableauCutSolver {
                    val delegate = ProductionLpEngineFactory.newTableauSolver(
                        model, cancellation, iterationLimit, workLimit, trackDegeneracy, pricing,
                    )
                    if (workLimit == 0L) return object : TableauCutSolver by delegate {
                        override fun solve(warm: Basis?): FloatLpResult? = null
                    }
                    val first = primalSolves++ == 0
                    return object : TableauCutSolver by delegate {
                        override val lastTermination get() =
                            if (first) LpFloatTermination.CANCELLED else delegate.lastTermination
                        override val lastMetrics get() =
                            if (first) LpSolveMetrics(workOps = charged) else delegate.lastMetrics
                        override fun solve(warm: Basis?): FloatLpResult? {
                            if (!first) return if (warm == null) null else delegate.solve(warm)
                            val result = delegate.solve(warm) ?: return null
                            return FloatLpResult(result.basis, result.objective, result.duals, result.primal,
                                optimal = false)
                        }
                    }
                }
            }
            val params = BacktrackParams(
                maxDecisions = 0L,
                lpPlan = LpPlan(bounding = true, probe = true, rootCutHarvest = false,
                    boundAdaptiveWork = false, rootMaxWork = allowance),
            )
            val offered = ArrayList<Sample>()

            BacktrackSolver(problem, LpSolveContext(factory)).resumable(objective, params).use { search ->
                search.runSlice(ExecutionControl.Never, Long.MAX_VALUE, 5_000L) { offered += it.sample }
                val outcome = if (allowance > charged) "PENDING" else "ROOT_WORK_SPENT"
                assertEquals(1L, search.stats.lp.phases.getValue("PRIMAL_CONTINUATION_ROOT").outcomes[outcome])
                search.runSlice(ExecutionControl.Never, Long.MAX_VALUE, 100_000L) { offered += it.sample }
            }

            if (allowance > charged) {
                assertEquals(listOf(1L, 0L), offered.single().ints.toList())
                assertEquals(2, primalSolves)
            } else {
                assertTrue(offered.isEmpty())
                assertEquals(1, primalSolves)
            }
        }
    }
}
