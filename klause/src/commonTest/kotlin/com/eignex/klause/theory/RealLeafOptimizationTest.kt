package com.eignex.klause.theory

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.ResumableMinimize
import com.eignex.klause.backtrack.selector.IndomainMax
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealAbs
import com.eignex.klause.factor.arithmetic.RealFiniteDomain
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.localsearch.Completion
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.portfolio.LeafRealCompletion
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.validation.FiniteSourceAcceptance
import com.eignex.klause.solver.validation.RealOptimizationResult
import com.eignex.klause.solver.validation.RealOptimizationStep
import com.eignex.klause.solver.validation.capturedRealAcceptance
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
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

    // Each direction's objective over `(x, y)` and its exact optimum, which stands within tolerance as well.
    private val magnitudeOptima = listOf(
        doubleArrayOf(0.0, 1.0) to 0.0,
        doubleArrayOf(0.0, -1.0) to -3.0,
        doubleArrayOf(1.0, 0.0) to -2.0,
        doubleArrayOf(-1.0, 0.0) to -3.0,
    )

    @Test
    fun `candidate completion should reach the exact optimum of an absolute value in either mode`() {
        for ((costs, optimum) in magnitudeOptima) {
            for (tolerance in listOf(null, tenth)) {
                val source = magnitude(tolerance)
                val acceptance = tolerance?.let { policy ->
                    FiniteSourceAcceptance(checkNotNull(capturedRealAcceptance(source, ExecutionControl.Never)), true,
                        search = SourceToleranceSearch(source, policy))
                }
                val objective = LinearObjective(realCoefficients = costs)

                val completed = LeafRealCompletion(source, objective, acceptance).complete(leaf, ExecutionControl.Never)

                val point = assertIs<Completion.Witness>(completed).sample
                assertEquals(optimum, objective.evaluate(point), "${costs.toList()} $tolerance")
            }
        }
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

    // A stop that fires at poll [firing], counting every poll.
    private class PollStop(private val firing: Long = Long.MAX_VALUE) : ExecutionControl() {
        var polls = 0L
            private set

        override fun isCancelled(): Boolean = polls++ >= firing
    }

    // The polls an uninterrupted optimization of [source] makes, and its result.
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
