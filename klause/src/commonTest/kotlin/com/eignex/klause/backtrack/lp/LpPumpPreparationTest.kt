package com.eignex.klause.backtrack.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.engine.solveAndCertify
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.LpAssemblyCancelled
import com.eignex.klause.lp.relaxation.RootDomains
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LpPumpPreparationTest {
    private val problem = Problem(1, 3, Array(3) { IntDomain(0, 10) }, listOf(
        Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.GE, 6),
    ))
    private val selected = FactorProjectionProviders.Default.prepare(problem)
    private val objective = LinearObjective(boolWeights = longArrayOf(1), intCoefficients = longArrayOf(1, 1, 1))

    @Test
    fun `paid setup should count against private preparation admission`() {
        val root = CpToLpRelaxation(problem, objective, projections = selected, reuseSourceTemplates = false)
            .build(RootDomains(problem))
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), SolveStatsSink("paid-pump-setup"),
            projections = selected).use { engine ->
            assertNotNull(LpPumpPreparation.create(engine, root, Cancellation.Never))

            val prepared = LpPumpPreparation.create(engine, root, Cancellation.Never, setupWork = 100L)

            assertNull(prepared)
        }
    }

    @Test
    fun `private objective views should preserve earlier model costs and current constants`() {
        val root = CpToLpRelaxation(problem, objective, projections = selected, reuseSourceTemplates = false)
            .build(RootDomains(problem))
        val sink = SolveStatsSink("pump-private-objectives")
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink,
            projections = selected).use { engine ->
            val prepared = assertNotNull(LpPumpPreparation.create(engine, root, Cancellation.Never))
            val changed = LinearObjective(boolWeights = longArrayOf(-2),
                intCoefficients = longArrayOf(2, 2, 2), constant = 7)

            val rebound = assertNotNull(prepared.rebind(changed, Cancellation.Never))
            val restored = assertNotNull(prepared.rebind(objective, Cancellation.Never))

            assertEquals(BigFraction.ofLong(10), solveAndCertify(rebound.model).lowerBound)
            assertEquals(BigFraction.ofLong(6), solveAndCertify(restored.model).lowerBound)
            assertEquals(BigFraction.ofLong(6), solveAndCertify(root.model).lowerBound)
            assertEquals(7L, rebound.objectiveConstant)
            assertNull(rebound.sourceMap)
        }
    }

    @Test
    fun `a new objective coordinate should keep private rebinding unavailable`() {
        val view = Problem(0, 4, Array(4) { IntDomain(0, 10) }, problem.factors)
        val selection = FactorProjectionProviders.Default.prepare(view)
        val original = LinearObjective(intCoefficients = longArrayOf(1, 1, 1))
        val root = CpToLpRelaxation(view, original, projections = selection, reuseSourceTemplates = false)
            .build(RootDomains(view))
        LpEngine(view, original, LpParams(lpPlan = LpPlan(bounding = true)), SolveStatsSink("pump-new-coordinate"),
            projections = selection).use { engine ->
            val prepared = assertNotNull(LpPumpPreparation.create(engine, root, Cancellation.Never))

            val rebound = prepared.rebind(LinearObjective(intCoefficients = longArrayOf(1, 1, 1, -1)),
                Cancellation.Never)

            assertNull(rebound)
            assertEquals(BigFraction.ofLong(6), solveAndCertify(root.model).lowerBound)
        }
    }

    @Test
    fun `overflow should preserve the last completed private definition`() {
        val view = Problem(0, 3, Array(3) { IntDomain(2, 10) }, problem.factors)
        val selection = FactorProjectionProviders.Default.prepare(view)
        val original = LinearObjective(intCoefficients = longArrayOf(1, 1, 1))
        val root = CpToLpRelaxation(view, original, projections = selection, reuseSourceTemplates = false)
            .build(RootDomains(view))
        LpEngine(view, original, LpParams(lpPlan = LpPlan(bounding = true)), SolveStatsSink("pump-objective-overflow"),
            projections = selection).use { engine ->
            val prepared = assertNotNull(LpPumpPreparation.create(engine, root, Cancellation.Never))

            val rebound = prepared.rebind(LinearObjective(intCoefficients = longArrayOf(Long.MAX_VALUE, 1, 1)),
                Cancellation.Never)

            assertNull(rebound)
            assertEquals(BigFraction.ofLong(6),
                solveAndCertify(assertNotNull(prepared.rebind(original, Cancellation.Never)).model).lowerBound)
        }
    }

    @Test
    fun `a containing debit should withhold a private objective view before publication`() {
        val root = CpToLpRelaxation(problem, objective, projections = selected, reuseSourceTemplates = false)
            .build(RootDomains(problem))
        var armed = false
        var stopped = false
        var charged = 0L
        val sink = SolveStatsSink("pump-objective-debit")
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink,
            projections = selected, containingWork = {
                if (armed) {
                    charged += it
                    stopped = true
                }
            }).use { engine ->
            val prepared = assertNotNull(LpPumpPreparation.create(engine, root, Cancellation.Never))
            val before = engine.totalSolveWork()
            armed = true

            assertFailsWith<LpAssemblyCancelled> {
                prepared.rebind(objective, Cancellation { stopped })
            }

            assertEquals(1L, charged)
            assertEquals(charged, engine.totalSolveWork() - before)
            assertEquals(1L, sink.snapshot().lp.phases.getValue("PRIMAL_LP_PUMP_PREPARATION_ROOT")
                .outcomes["CANCELLED"])
            armed = false
            stopped = false
            assertEquals(BigFraction.ofLong(6),
                solveAndCertify(assertNotNull(prepared.rebind(objective, Cancellation.Never)).model).lowerBound)
        }
    }

    @Test
    fun `unsupported source rows should keep private preparation unavailable`() {
        val view = problem.withFactors(problem.factors + AllDifferent(intArrayOf(0, 1, 2)))
        val selection = FactorProjectionProviders.Default.prepare(view)
        val root = CpToLpRelaxation(view, objective, projections = selection, reuseSourceTemplates = false)
            .build(RootDomains(view))
        val sink = SolveStatsSink("pump-unsupported-row")
        LpEngine(view, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink,
            projections = selection).use { engine ->
            val prepared = LpPumpPreparation.create(engine, root, Cancellation.Never)

            assertNull(prepared)
            assertEquals(1L, sink.snapshot().lp.phases.getValue("PRIMAL_LP_PUMP_PREPARATION_ROOT")
                .outcomes["DECLINED"])
        }
    }

    @Test
    fun `cheaper ordinary construction should keep private preparation unadmitted`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
        val selection = FactorProjectionProviders.Default.prepare(source)
        val cost = LinearObjective(intCoefficients = longArrayOf(1))
        val root = CpToLpRelaxation(source, cost, projections = selection, reuseSourceTemplates = false)
            .build(RootDomains(source))
        val sink = SolveStatsSink("pump-cheap-construction")
        LpEngine(source, cost, LpParams(lpPlan = LpPlan(bounding = true)), sink,
            projections = selection).use { engine ->
            val prepared = LpPumpPreparation.create(engine, root, Cancellation.Never)

            assertNull(prepared)
            assertEquals(1L, sink.snapshot().lp.phases.getValue("PRIMAL_LP_PUMP_PREPARATION_ROOT")
                .outcomes["DECLINED"])
        }
    }
}
