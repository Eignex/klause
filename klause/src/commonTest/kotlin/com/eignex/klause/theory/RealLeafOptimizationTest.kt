package com.eignex.klause.theory

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.ResumableMinimize
import com.eignex.klause.backtrack.selector.IndomainMax
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.backtrack.selector.ValueSelector
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealAbs
import com.eignex.klause.factor.arithmetic.RealArrayMinMax
import com.eignex.klause.factor.arithmetic.RealFiniteDomain
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.localsearch.Completion
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.LpEngineFactory
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpFloatAllowance
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.klause.lp.engine.PersistentLpSolver
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.lp.engine.VarStatus
import com.eignex.klause.portfolio.LeafRealCompletion
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.ExactContinuationLimits
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.validation.FiniteSourceAcceptance
import com.eignex.klause.solver.validation.RealOptimizationResult
import com.eignex.klause.solver.validation.RealOptimizationStep
import com.eignex.klause.solver.validation.RealOptimizationStop
import com.eignex.klause.solver.validation.capturedRealAcceptance
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.WorkMeter
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RealLeafOptimizationTest {
    // `r1 = abs(x)`, `r2 = abs(w)` and `x + w = 0`, so `r1 - r2` is zero everywhere while the leaf relaxation, which
    // states only `r(i) >= abs(.)`, reaches -1 and 1 at `x = 0` with the other result at its bound.
    private val oppositeMagnitudes = Problem(
        0, 0, emptyArray(),
        arrayOf(
            RealAbs(0, 2),
            RealAbs(1, 3),
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(1.0, 1.0), LinearOp.EQ, 0.0),
        ),
        numRealVars = 4,
        realLower = doubleArrayOf(-1.0, -1.0, 0.0, 0.0),
        realUpper = doubleArrayOf(1.0, 1.0, 1.0, 1.0),
    )

    // `y` in `members` and `y = u` with `u` in `[0.25, 0.75]`; the relaxation takes the hull of the members.
    private fun membership(vararg members: Double) = Problem(
        0, 0, emptyArray(),
        arrayOf(
            RealFiniteDomain(0, members),
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(1.0, -1.0), LinearOp.EQ, 0.0),
        ),
        numRealVars = 2,
        realLower = doubleArrayOf(0.0, 0.25),
        realUpper = doubleArrayOf(1.0, 0.75),
    )

    private val leaf = Sample(BooleanArray(0), LongArray(0))

    private fun costs(vararg values: Long) = values.map(BigFraction::ofLong)

    @Test
    fun `a feasible leaf should reach its exact optimum after correction exhaustion`() {
        val source = Problem(
            0, 0, emptyArray(),
            arrayOf(Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(2.0), LinearOp.GE, 1.0)),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0),
        )
        var corrections = 0
        var cell: LpExactState? = null
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: ExecutionControl,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver {
                val delegate = ProductionLpEngineFactory.newPersistentSolver(
                    model, cancellation, refactorUpdateLimit, iterationLimit, workLimit, trackDegeneracy, pricing,
                )
                if (model.n == 0 || model.costD(0) == 0.0) return delegate
                if (cell == null) cell = model.exactState
                return object : PersistentLpSolver by delegate {
                    private var active = model.exactState
                    override val solvedExactState get() = active
                    override val lastPivots = 1
                    override val lastMetrics = LpSolveMetrics(workOps = 1L, pivots = 1)
                    override fun adopt(state: LpExactState, token: ExecutionControl): Boolean =
                        delegate.adopt(state, token).also { if (it) active = state }
                    override fun solve(warm: Basis?): FloatLpResult {
                        val sourceModel = checkNotNull(active).model
                        val headings = IntArray(sourceModel.m) { sourceModel.n + it }
                        val statuses = Array(sourceModel.numVars) {
                            if (it < sourceModel.n) VarStatus.AT_LOWER else VarStatus.BASIC
                        }
                        val primal = if (active === cell) {
                            checkNotNull(delegate.resolveBounds()).primal
                        } else {
                            corrections++
                            if (headings.isNotEmpty()) {
                                statuses[headings[0]] = VarStatus.FIXED
                                headings[0] = 0
                                statuses[0] = VarStatus.BASIC
                            }
                            DoubleArray(sourceModel.n)
                        }
                        return FloatLpResult(Basis(headings, statuses), 0.0, DoubleArray(sourceModel.m), primal,
                            exactState = active)
                    }
                    override fun resolveBounds(allowance: LpFloatAllowance?) = solve(null)
                }
            }
        }
        val context = LpSolveContext(engineFactory = factory)
        val step = AbsRealCompletion(source).optimize(leaf, costs(1), context).use {
            it.advance(ExecutionControl.Never)
        }

        val optimum = assertIs<RealOptimizationResult.Optimum>(assertIs<RealOptimizationStep.Completed>(step).result)
        assertEquals(BigFraction.ofDouble(0.5), optimum.value)
        assertEquals(64, corrections)
    }

    @Test
    fun `a resource stopped cell should keep its checked point without opening an optimization query`() {
        var objectiveQuery = false
        val context = LpSolveContext(
            exactContinuationLimits = ExactContinuationLimits(maxAllocation = 0),
            onQuery = { query ->
                objectiveQuery = objectiveQuery || (0 until query.model.n).any { query.model.costD(it) != 0.0 }
            },
        )

        val step = AbsRealCompletion(oppositeMagnitudes).optimize(leaf, costs(0, 0, 1, -1), context).use {
            it.advance(ExecutionControl.Never)
        }

        val stopped = assertIs<RealOptimizationResult.Undecided>(assertIs<RealOptimizationStep.Completed>(step).result)
        assertEquals(RealOptimizationStop.ALLOCATION, stopped.stop)
        assertEquals(BigFraction.ZERO, stopped.incumbent?.let { it[2] - it[3] })
        assertFalse(objectiveQuery)
    }

    @Test
    fun `a resource stopped leaf should surface its incumbent before the incomplete result`() {
        val objective = LinearObjective(realCoefficients = doubleArrayOf(0.0, 0.0, 1.0, -1.0))
        val context = LpSolveContext(exactContinuationLimits = ExactContinuationLimits(maxAllocation = 0))
        val params = BacktrackParams(randomSeed = 0L,
            lpPlan = LpPlan(bounding = false, probe = false, rootCutHarvest = false, componentSplit = false))

        val results = BacktrackSolver(oppositeMagnitudes.bake(), context).improvements(objective, params).toList()

        assertEquals(listOf(0.0, 0.0), results.map { it.objectiveValue })
        assertEquals(TerminationReason.RepresentationDeclined, assertIs<MinimizeResult.BestFound>(results.last()).reason)
    }

    @Test
    fun `minimization should reach the optimum of opposite absolute values the leaf relaxation misses`() {
        for (sign in listOf(1.0, -1.0)) {
            val objective = LinearObjective(realCoefficients = doubleArrayOf(0.0, 0.0, sign, -sign))

            val result = BacktrackSolver(oppositeMagnitudes.bake()).minimize(objective)

            assertEquals(0.0, assertIs<MinimizeResult.Optimal>(result).objective, "sign $sign")
        }
    }

    @Test
    fun `minimization should reach the member a coupled interval admits`() {
        for (sign in listOf(1.0, -1.0)) {
            val objective = LinearObjective(realCoefficients = doubleArrayOf(0.0, sign))

            val result = BacktrackSolver(membership(0.0, 0.5, 1.0).bake()).minimize(objective)

            val optimum = assertIs<MinimizeResult.Optimal>(result)
            assertEquals(BigFraction.ofDouble(0.5), optimum.sample.exactReals?.get(1), "sign $sign")
        }
    }

    @Test
    fun `minimization should reach an optimum one side of a disequality attains`() {
        // `x != y` over the unit square: the side `x > y` never attains `x = 0`, the side `x < y` does.
        val source = Problem(
            0, 0, emptyArray(),
            arrayOf(
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(1.0, -1.0), LinearOp.NE, 0.0),
            ),
            numRealVars = 2, realLower = doubleArrayOf(0.0, 0.0), realUpper = doubleArrayOf(1.0, 1.0),
        )
        val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0, 0.0))

        val result = BacktrackSolver(source.bake()).minimize(objective)

        assertEquals(0.0, assertIs<MinimizeResult.Optimal>(result).objective)
    }

    @Test
    fun `minimization should prove an optimum below a leaf infimum in either branch order`() {
        // `b` holds exactly when `x > 1`: the side `b` approaches 1 without attaining it, the other side attains 0.
        val source = Problem(
            1, 0, emptyArray(),
            arrayOf(ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE,
                1.0, strict = true)),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0),
        ).bake()
        val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))

        val results = listOf(IndomainMin, IndomainMax).associateWith { selector ->
            BacktrackSolver(source).minimize(objective, BacktrackParams(randomSeed = 0L, valueSelector = selector))
        }

        assertEquals(listOf(0.0, 0.0), results.values.map { assertIs<MinimizeResult.Optimal>(it).objective })
    }

    private val dyadic = SourceTolerance(SemanticRational.of(bigIntOf(1), bigIntOf(1024)))

    // `minimize v` over `v/16 + 100k >= 100.125`, `v/32 - 100k >= -199.9375` and `v != 2` with `k` and a free `j` in
    // `[1, 2]`: each `k` states `v >= 2` through one row, the disequality keeps every leaf from the LP's vertex, and
    // within a slack of 1/1024 the leaves of `k = 1` reach `2 - 16/1024` and those of `k = 2` reach `2 - 32/1024`.
    private val sensitiveRows = Problem(
        0, 2, arrayOf(IntDomain(1, 2), IntDomain(1, 2)),
        arrayOf(
            Linear(intArrayOf(0), doubleArrayOf(100.0), intArrayOf(0), doubleArrayOf(0.0625), LinearOp.GE, 100.125),
            Linear(intArrayOf(0), doubleArrayOf(-100.0), intArrayOf(0), doubleArrayOf(0.03125), LinearOp.GE,
                -199.9375),
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.NE, 2.0),
        ),
        numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(10.0),
        semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Continuous,
            feasibilityTolerance = dyadic).resolve(),
    )

    @Test
    fun `node bounds should keep a subtree whose only improvement lies within the slack in either branch order`() {
        val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))

        val optima = listOf(IndomainMin, IndomainMax).map { selector ->
            assertIs<MinimizeResult.Optimal>(tolerantMinimum(sensitiveRows, dyadic, objective, selector)).objective
        }

        assertEquals(listOf(2.0 - 32.0 / 1024, 2.0 - 32.0 / 1024), optima)
    }

    // `minimize 5k + v` over `k + v >= 1` with `k` and a free `j` in `[0, 1]` and `v` in `[0, bound]`: `k = 0` needs
    // `v >= 1 - 1/1024` within a slack of 1/1024, which a bound of 0.9995 admits, past the float LP's own tolerance,
    // and a bound of 0.99 misses by more than the slack.
    private fun slackGap(bound: Double) = Problem(
        0, 2, arrayOf(IntDomain(0, 1), IntDomain(0, 1)),
        arrayOf(Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 1.0)),
        numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(bound),
        semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Continuous,
            feasibilityTolerance = dyadic).resolve(),
    )

    private fun tolerantMinimum(
        source: Problem,
        policy: SourceTolerance,
        objective: LinearObjective,
        selector: ValueSelector = IndomainMin,
    ): MinimizeResult {
        val accepts = checkNotNull(capturedRealAcceptance(source, ExecutionControl.Never))
        val acceptance = FiniteSourceAcceptance(accepts, true, search = SourceToleranceSearch(source, policy))
        val params = BacktrackParams(randomSeed = 0L, variableSelector = InputOrder, valueSelector = selector,
            toleranceCheck = acceptance,
            lpPlan = LpPlan(bounding = true, branching = false, probe = false, rootCutHarvest = false))
        return BacktrackSolver(source.bake(), LpSolveContext.finite(numerical = true)).minimize(objective, params)
    }

    @Test
    fun `a node infeasible only within the slack should not be refuted in either branch order`() {
        val objective = LinearObjective(intCoefficients = longArrayOf(5L, 0L), realCoefficients = doubleArrayOf(1.0))

        val optima = listOf(IndomainMin, IndomainMax).map { selector ->
            assertIs<MinimizeResult.Optimal>(tolerantMinimum(slackGap(0.9995), dyadic, objective, selector))
                .objective
        }

        assertEquals(listOf(1.0 - 1.0 / 1024, 1.0 - 1.0 / 1024), optima)
    }

    @Test
    fun `a node infeasible beyond the slack should be refuted`() {
        val objective = LinearObjective(intCoefficients = longArrayOf(5L, 0L), realCoefficients = doubleArrayOf(1.0))

        val result = tolerantMinimum(slackGap(0.99), dyadic, objective)

        assertEquals(5.0, assertIs<MinimizeResult.Optimal>(result).objective)
    }

    @Test
    fun `minimization should refute a leaf whose relaxation holds but no member does`() {
        val result = BacktrackSolver(membership(0.0, 1.0).bake())
            .minimize(LinearObjective(realCoefficients = doubleArrayOf(0.0, 1.0)))

        assertIs<MinimizeResult.Infeasible>(result)
    }

    @Test
    fun `candidate completion should take the optimum the leaf relaxation misses`() {
        val objective = LinearObjective(realCoefficients = doubleArrayOf(0.0, 0.0, 1.0, -1.0))

        val completed = LeafRealCompletion(oppositeMagnitudes, objective).complete(leaf, ExecutionControl.Never)

        val reals = assertIs<Completion.Witness>(completed).sample.reals
        assertEquals(0.0, reals[2] - reals[3])
    }

    // `y = abs(x)` with `x` in `[-2, 3]` and `y` in `[0, 5]`, exact or within a tenth.
    private fun magnitude(tolerance: SourceTolerance?) = Problem(
        0, 0, emptyArray(), arrayOf(RealAbs(0, 1)),
        numRealVars = 2, realLower = doubleArrayOf(-2.0, 0.0), realUpper = doubleArrayOf(3.0, 5.0),
        semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Continuous,
            feasibilityTolerance = tolerance).resolve(),
    )

    private val tenth = SourceTolerance(SemanticRational.of(bigIntOf(1), bigIntOf(10)))

    // Each direction's objective over `(x, y)` with its exact optimum and its optimum within a tenth. The LP certifies
    // the vertex of its envelope in three directions; maximizing `y` leaves the envelope and is minimized within the
    // tenth, by which each declared bound and the residual `abs(y - abs(x))` move.
    private val magnitudeOptima = listOf(
        doubleArrayOf(0.0, 1.0) to (0.0 to 0.0),
        doubleArrayOf(0.0, -1.0) to (-3.0 to -3.2),
        doubleArrayOf(1.0, 0.0) to (-2.0 to -2.0),
        doubleArrayOf(-1.0, 0.0) to (-3.0 to -3.0),
    )

    // The objective candidate completion reaches over [source] under [tolerance].
    private fun completedObjective(source: Problem, costs: DoubleArray, tolerance: SourceTolerance?): Double {
        val acceptance = tolerance?.let { policy ->
            FiniteSourceAcceptance(checkNotNull(capturedRealAcceptance(source, ExecutionControl.Never)), true,
                search = SourceToleranceSearch(source, policy))
        }
        val objective = LinearObjective(realCoefficients = costs)
        val completed = LeafRealCompletion(source, objective, acceptance).complete(leaf, ExecutionControl.Never)
        return objective.evaluate(assertIs<Completion.Witness>(completed).sample)
    }

    @Test
    fun `candidate completion should reach the exact optimum of an absolute value without a tolerance`() {
        val reached = magnitudeOptima.map { (costs, _) -> completedObjective(magnitude(null), costs, null) }

        assertEquals(magnitudeOptima.map { it.second.first }, reached)
    }

    @Test
    fun `candidate completion should reach the optimum of an absolute value within the tolerance`() {
        val reached = magnitudeOptima.map { (costs, _) -> completedObjective(magnitude(tenth), costs, tenth) }

        assertEquals(magnitudeOptima.map { it.second.second }, reached)
    }

    // `m = max(a, b)` or `m = min(a, b)`, leaf-enforced, with `a` in `[0, 1]`, `b` in `[0, 2]`, within a tenth.
    private fun extremum(max: Boolean) = Problem(
        0, 0, emptyArray(), arrayOf(RealArrayMinMax(2, intArrayOf(0, 1), max).leafEnforcedCopy()),
        numRealVars = 3, realLower = doubleArrayOf(0.0, 0.0, 0.0), realUpper = doubleArrayOf(1.0, 2.0, 3.0),
        semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Continuous,
            feasibilityTolerance = tenth).resolve(),
    )

    @Test
    fun `candidate completion should reach the optimum of a leaf enforced extremum within the tolerance`() {
        val optima =
            mapOf((true to 1.0) to 0.0, (true to -1.0) to -2.2, (false to 1.0) to 0.0, (false to -1.0) to -1.0)

        val reached = optima.keys.associateWith { (max, sign) ->
            val source = extremum(max)
            val accepts = checkNotNull(capturedRealAcceptance(source, ExecutionControl.Never))
            val acceptance = FiniteSourceAcceptance(accepts, true, search = SourceToleranceSearch(source, tenth))
            val objective = LinearObjective(realCoefficients = doubleArrayOf(0.0, 0.0, sign))
            val completed = LeafRealCompletion(source, objective, acceptance).complete(leaf, ExecutionControl.Never)
            objective.evaluate(assertIs<Completion.Witness>(completed).sample)
        }

        assertEquals(optima, reached)
    }

    @Test
    fun `a paused optimization should resume to the optimum`() {
        val optimization = AbsRealCompletion(oppositeMagnitudes).optimize(leaf, costs(0, 0, 1, -1))

        val paused = optimization.advance(ExecutionControl.Never, shouldPause = { true })
        val spent = optimization.work
        val resumed = optimization.advance(ExecutionControl.Never)

        assertEquals(RealOptimizationStep.Paused, paused)
        assertTrue(spent > 0L)
        val optimum = assertIs<RealOptimizationResult.Optimum>(assertIs<RealOptimizationStep.Completed>(resumed).result)
        assertEquals(BigFraction.ZERO, optimum.value)
    }

    @Test
    fun `small slices should retain the rounds search and cumulative work`() {
        val objective = costs(0, 0, 1, -1)
        val expectedQueries = ArrayList<Long>()
        val uninterrupted = AbsRealCompletion(oppositeMagnitudes).optimize(leaf, objective,
            LpSolveContext(onQuery = { expectedQueries += it.sequence }))
        val expected = assertIs<RealOptimizationResult.Optimum>(
            assertIs<RealOptimizationStep.Completed>(uninterrupted.advance(ExecutionControl.Never)).result)

        for (quantum in listOf(1L, 7L, 64L)) {
            val queries = ArrayList<Pair<Any, Long>>()
            var cellStarted = false
            val context = LpSolveContext(onQuery = { query ->
                queries += query.owner to query.sequence
                cellStarted = cellStarted || (0 until oppositeMagnitudes.numRealVars).any {
                    query.model.costD(it) != 0.0
                }
            })
            val optimization = AbsRealCompletion(oppositeMagnitudes).optimize(leaf, objective, context)
            var insideRound = false
            var slices = 0
            var previousWork = 0L
            var step: RealOptimizationStep = RealOptimizationStep.Paused
            optimization.use {
                while (step == RealOptimizationStep.Paused && slices++ < 256) {
                    step = it.advance(ExecutionControl.Never, quantum)
                    insideRound = insideRound || (step == RealOptimizationStep.Paused && queries.isNotEmpty() &&
                        !cellStarted)
                    assertTrue(it.work >= previousWork)
                    previousWork = it.work
                }
            }

            val actual = assertIs<RealOptimizationResult.Optimum>(assertIs<RealOptimizationStep.Completed>(step).result)
            assertEquals(expected.value, actual.value, "quantum $quantum")
            assertEquals(expected.reals, actual.reals, "quantum $quantum")
            assertEquals(uninterrupted.work, optimization.work, "quantum $quantum")
            assertEquals(expectedQueries, queries.map { it.second }, "quantum $quantum")
            assertTrue(insideRound, "quantum $quantum")
            assertTrue(slices > 2, "quantum $quantum")
            assertTrue(queries.groupBy { it.first }.values.any { group -> group.size > 1 })
        }
    }

    @Test
    fun `slice resumption should not renew the containing work allowance`() {
        val objective = costs(0, 0, 1, -1)
        var queried = false
        val optimization = AbsRealCompletion(oppositeMagnitudes).optimize(leaf, objective,
            LpSolveContext(onQuery = { queried = true }))
        var charged = 0L
        var limit = Long.MAX_VALUE
        val cancellation = object : ExecutionControl(accounting = WorkMeter { charged += it }) {
            override fun isCancelled(): Boolean = charged >= limit
        }
        optimization.use {
            repeat(32) {
                if (!queried) assertEquals(RealOptimizationStep.Paused, optimization.advance(cancellation, 1L))
            }
            assertTrue(queried)
            limit = charged + 1L
            var step: RealOptimizationStep = RealOptimizationStep.Paused
            repeat(32) {
                if (step == RealOptimizationStep.Paused) step = optimization.advance(cancellation, 1L)
            }
            val result = assertIs<RealOptimizationStep.Completed>(step).result

            assertIs<RealOptimizationResult.Undecided>(result)
            assertTrue(charged >= limit)
            assertEquals(charged, optimization.work)
            val stoppedWork = charged
            assertIs<RealOptimizationResult.Undecided>(
                assertIs<RealOptimizationStep.Completed>(optimization.advance(cancellation, 1L)).result)
            assertEquals(stoppedWork, charged)
        }
    }

    @Test
    fun `closing a suspended round should reject another slice`() {
        var queried = false
        val context = LpSolveContext(onQuery = { queried = true })
        val pending = AbsRealCompletion(oppositeMagnitudes).optimize(leaf, costs(0, 0, 1, -1), context)
        repeat(32) {
            if (!queried) assertEquals(RealOptimizationStep.Paused, pending.advance(ExecutionControl.Never, 1L))
        }
        assertTrue(queried)

        pending.close()

        assertFailsWith<IllegalStateException> { pending.advance(ExecutionControl.Never) }
    }

    @Test
    fun `a run end after a paused incumbent should retain its point without an optimality proof`() {
        var cellStarted = false
        val context = LpSolveContext(onQuery = { query ->
            cellStarted = cellStarted || (0 until oppositeMagnitudes.numRealVars).any {
                query.model.costD(it) != 0.0
            }
        })
        AbsRealCompletion(oppositeMagnitudes).optimize(leaf, costs(0, 0, 1, -1), context).use { optimization ->
            repeat(64) {
                if (!cellStarted) {
                    assertEquals(RealOptimizationStep.Paused, optimization.advance(ExecutionControl.Never, 1L))
                }
            }
            assertTrue(cellStarted)
            val stopped = optimization.advance(object : ExecutionControl() {
                override fun isCancelled(): Boolean = true
            }, 1L)

            val result =
                assertIs<RealOptimizationResult.Undecided>(assertIs<RealOptimizationStep.Completed>(stopped).result)
            assertEquals(BigFraction.ZERO, result.incumbent?.let { it[2] - it[3] })
            assertEquals(null, result.infimum)
        }
    }

    @Test
    fun `small slices should exhaust a refuted leaf without an incumbent`() {
        val source = membership(0.0, 1.0)
        AbsRealCompletion(source).optimize(leaf, costs(0, 1)).use { optimization ->
            var step: RealOptimizationStep = RealOptimizationStep.Paused
            var slices = 0
            while (step == RealOptimizationStep.Paused && slices++ < 128) {
                step = optimization.advance(ExecutionControl.Never, 1L)
            }

            assertIs<RealOptimizationResult.Refuted>(assertIs<RealOptimizationStep.Completed>(step).result)
            assertTrue(slices > 2)
        }
    }

    @Test
    fun `small slices should retain an unattained infimum until its cutoff is refuted`() {
        val source = Problem(
            0, 0, emptyArray(),
            arrayOf(Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE,
                0.0, strict = true)),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
        )
        val uninterrupted = AbsRealCompletion(source).optimize(leaf, costs(1))
        val expected = assertIs<RealOptimizationResult.Undecided>(
            assertIs<RealOptimizationStep.Completed>(uninterrupted.advance(ExecutionControl.Never)).result)
        AbsRealCompletion(source).optimize(leaf, costs(1)).use { optimization ->
            var step: RealOptimizationStep = RealOptimizationStep.Paused
            var slices = 0
            while (step == RealOptimizationStep.Paused && slices++ < 128) {
                step = optimization.advance(ExecutionControl.Never, 1L)
            }

            val actual =
                assertIs<RealOptimizationResult.Undecided>(assertIs<RealOptimizationStep.Completed>(step).result)
            assertEquals(BigFraction.ZERO, actual.infimum)
            assertEquals(expected.incumbent, actual.incumbent)
            assertEquals(uninterrupted.work, optimization.work)
            assertTrue(slices > 2)
        }
    }

    private class PollStop(private val firing: Long = Long.MAX_VALUE) : ExecutionControl() {
        var polls = 0L
            private set

        override fun isCancelled(): Boolean = polls++ >= firing
    }

    private fun polls(source: Problem, costs: List<BigFraction>): Pair<Long, RealOptimizationResult> {
        val counted = PollStop()
        val step = AbsRealCompletion(source).optimize(leaf, costs).advance(counted)
        return counted.polls to assertIs<RealOptimizationStep.Completed>(step).result
    }

    @Test
    fun `a stopped optimization should decide nothing and rerun its round`() {
        val (total, _) = polls(oppositeMagnitudes, costs(0, 0, 1, -1))
        val optimization = AbsRealCompletion(oppositeMagnitudes).optimize(leaf, costs(0, 0, 1, -1))

        val stopped = optimization.advance(PollStop(total / 2))
        val resumed = optimization.advance(ExecutionControl.Never)

        assertIs<RealOptimizationResult.Undecided>(assertIs<RealOptimizationStep.Completed>(stopped).result)
        val optimum = assertIs<RealOptimizationResult.Optimum>(assertIs<RealOptimizationStep.Completed>(resumed).result)
        assertEquals(BigFraction.ZERO, optimum.value)
    }

    @Test
    fun `a refutation that finishes as the stop fires should decide nothing`() {
        val source = membership(0.0, 1.0)
        val (total, uninterrupted) = polls(source, costs(0, 1))
        val optimization = AbsRealCompletion(source).optimize(leaf, costs(0, 1))

        val stopped = optimization.advance(PollStop(total - 1))

        assertIs<RealOptimizationResult.Refuted>(uninterrupted)
        assertIs<RealOptimizationResult.Undecided>(assertIs<RealOptimizationStep.Completed>(stopped).result)
    }

    @Test
    fun `minimization should resume a paused leaf optimization across work slices`() {
        val objective = LinearObjective(realCoefficients = doubleArrayOf(0.0, 0.0, 1.0, -1.0))
        val params = BacktrackParams(randomSeed = 0L,
            lpPlan = LpPlan(bounding = false, probe = false, rootCutHarvest = false, componentSplit = false))
        var terminal: MinimizeResult? = null
        var paused = false
        var slices = 0

        ResumableMinimize(BacktrackSolver(oppositeMagnitudes.bake()), objective, params).use { search ->
            while (terminal == null && slices++ < 512) {
                terminal = search.runSlice(ExecutionControl.Never, 60_000L, 1L) {}
                paused = paused || search.leafOptimizationPending
            }
        }

        assertEquals(0.0, assertIs<MinimizeResult.Optimal>(terminal).objective)
        assertTrue(paused)
    }

    @Test
    fun `minimization stopped inside a leaf optimization should not refute the leaf`() {
        val problem = membership(0.0, 1.0).bake()
        val objective = LinearObjective(realCoefficients = doubleArrayOf(0.0, 1.0))
        val counted = PollStop()
        BacktrackSolver(problem).minimize(objective, BacktrackParams(cancellation = counted))

        val results = listOf(counted.polls / 4, counted.polls / 2, counted.polls * 3 / 4).map { firing ->
            BacktrackSolver(problem).minimize(objective, BacktrackParams(cancellation = PollStop(firing)))
        }

        assertTrue(results.none { it is MinimizeResult.Infeasible }, "$results")
    }
}
