package com.eignex.klause.lp.bounding

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.CutExpression
import com.eignex.klause.lp.engine.CutPremise
import com.eignex.klause.lp.engine.CutProofFact
import com.eignex.klause.lp.engine.CutProvenance
import com.eignex.klause.lp.engine.CutSource
import com.eignex.klause.lp.engine.CutSourceKind
import com.eignex.klause.lp.engine.LpEngineFactory
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.PersistentLpSolver
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.exactBounds
import com.eignex.klause.lp.engine.exactShift
import com.eignex.klause.lp.relaxation.LpExplanation
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.CpSearchComponent
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CpLpAdapterTest {
    @Test
    fun `shared bound changes and pop reuse factors with source equivalent proof views`() {
        val problem = Problem(
            0, 2, Array(2) { IntDomain(-3, 7) },
            arrayOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
        )
        )
        var constructions = 0
        var rebinds = 0
        var closes = 0
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver {
                constructions++
                val delegate = ProductionLpEngineFactory.newPersistentSolver(
                    model,
                    cancellation,
                    refactorUpdateLimit,
                    iterationLimit,
                    workLimit,
                    trackDegeneracy,
                    pricing,
                )
                return object : PersistentLpSolver by delegate {
                    override fun rebind(next: LpModel, token: Cancellation): Boolean {
                        rebinds++
                        return delegate.rebind(next, token)
                    }
                    override fun close() {
                        closes++
                        delegate.close()
                    }
                }
            }
        }
        val objective = LinearObjective(intCoefficients = longArrayOf(2, 1), constant = 5)
        LpEngine(
            problem,
            objective,
            LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "trail"),
            LpSolveContext(factory),
        ).use { engine ->
            val cp = CpSearchComponent(PropagationSession(problem))
            engine.cpAdapter.attach(cp.session, feasibility = false)
            val shared = SearchSession(listOf(cp, engine.propagator))
            shared.initialize()
            val relaxer = assertNotNull(engine.lpRelaxer)
            val root = engine.nodeRelaxation(relaxer, cp.session)
            val initial = assertNotNull(engine.solveNode(root.model, null, Cancellation.Never)?.second)
            val saved = initial.primal.copyOf()
            val authority = assertNotNull(engine.propagator.state).model

            shared.push(SearchDecision.IntAtLeast(0, 0))
            val child = engine.nodeRelaxation(relaxer, cp.session)
            val result = assertNotNull(engine.solveNode(child.model, null, Cancellation.Never)?.second)
            assertEquals(0, engine.propagator.lastMetrics.initialRefactorizations)
            for (x in 0L..7L) {
                for (y in -3L..7L) {
                val column = child.intColOf[0]
                assertEquals(BigFraction.ZERO, child.model.exactShift(column))
                assertEquals(BigFraction.ofLong(7), child.model.exactBounds(column).upper?.number?.value)
                val shifted = longArrayOf(x - child.model.loShift[0], y - child.model.loShift[1])
                var activity = 0L
                for (j in shifted.indices) child.model.forEachInColumn(j) { _, a -> activity += a * shifted[j] }
                assertEquals(x + y >= 1L, activity <= child.model.rhs[0])
                val objectiveValue = child.model.objConstant + child.objectiveConstant +
                    shifted.indices.sumOf { child.model.cost[it] * shifted[it] }
                assertEquals(2 * x + y + 5, objectiveValue)
            }
            }
            assertEquals(1.0, result.objective)
            shared.popTo(0)
            val restored = engine.nodeRelaxation(relaxer, cp.session)
            assertTrue(authority.sameAuthority(assertNotNull(engine.propagator.state).model))
            assertNotNull(engine.solveNode(restored.model, null, Cancellation.Never)?.second)
            assertEquals(0, engine.propagator.lastMetrics.initialRefactorizations)
            assertEquals(0, engine.propagator.lastMetrics.warmStartRefactorizations)
            assertContentEquals(saved, initial.primal)
            assertEquals(1, constructions)
            assertEquals(0, rebinds)
            engine.releasePersistentSolvers()
            assertEquals(constructions, closes)
            assertNotNull(engine.solveNode(restored.model, null, Cancellation.Never)?.second)
            assertEquals(2, constructions)
            assertEquals(0, rebinds)
        }
        assertEquals(constructions, closes)
    }

    @Test
    fun `standalone sibling bounds cannot retain a stronger prior assertion`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(-5, 5)), emptyArray())
        LpEngine(
            problem,
            LinearObjective(intCoefficients = longArrayOf(1)),
            LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "siblings"),
        ).use { engine ->
            val session = PropagationSession(problem)
            val relaxer = assertNotNull(engine.lpRelaxer)
            session.pinIntAtLeast(0, 3)
            assertEquals(3L, engine.nodeRelaxation(relaxer, session).model.loShift[0])
            session.popToLevel(0)
            session.pinIntAtMost(0, -2)
            val sibling = engine.nodeRelaxation(relaxer, session)
            assertEquals(-5L, sibling.model.loShift[0])
            assertEquals(3L, sibling.model.upper[0])
            assertEquals(
                -5.0,
                assertNotNull(engine.solveNode(sibling.model, null, Cancellation.Never)?.second).objective,
            )
        }
    }

    @Test
    fun `pooled local cuts retain guards absent from their columns through siblings`() {
        val problem = Problem(1, 1, arrayOf(IntDomain(0, 2)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        LpEngine(
            problem,
            objective,
            LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "guards"),
        ).use { engine ->
            val cp = CpSearchComponent(PropagationSession(problem))
            engine.cpAdapter.attach(cp.session, feasibility = false)
            val shared = SearchSession(listOf(cp, engine.propagator))
            shared.initialize()
            shared.push(SearchDecision.Bool(0))
            shared.push(SearchDecision.IntAtLeast(0, 1))
            val relaxer = assertNotNull(engine.lpRelaxer)
            val relaxation = engine.nodeRelaxation(relaxer, cp.session)
            val expression = CutExpression(
                mapOf(
                CutSource(CutSourceKind.INTEGER, 0) to BigFraction.ONE,
            )
            )
            val facts = listOf(
                CutProofFact(CutPremise.Literal(0), false),
                CutProofFact(
                    CutPremise.Bound(expression, false, BigFraction.ONE),
                    false,
                ),
            )
            val cut = Cut(
                intArrayOf(relaxation.intColOf[0]),
                longArrayOf(1),
                Relation.GE,
                1,
                provenance = CutProvenance(problem, 0, facts),
            )
            engine.recordSearchCuts(listOf(cut), doubleArrayOf(1.0), relaxation, cp.session)
            assertEquals(1, engine.cutPool.cuts().size)
            assertTrue(engine.cutPool.exportGlobalCuts().isEmpty())
            val cutRelaxation = relaxer.build(cp.session, engine.cutPool.cuts())
            val reason = IntArrayList()
            assertTrue(
                LpExplanation.addRowPremiseLits(
                reason,
                IntHashSet(),
                cutRelaxation,
                intArrayOf(cutRelaxation.model.m - 1),
                cp.session,
            )
            )
            val boundLiteral = cp.session.boundGeLit(0, 1, positive = false)
            assertEquals(setOf(1, boundLiteral), reason.toIntArray().toSet())
            for (guard in listOf(false, true)) {
                for (x in 0L..2L) {
                val antecedent = reason.toIntArray().none { literal ->
                    when (literal) {
                        1 -> !guard
                        boundLiteral -> x < 1L
                        else -> error("unexpected source literal")
                    }
                }
                assertTrue(!antecedent || x >= 1L)
            }
            }

            shared.popTo(0)
            shared.push(SearchDecision.Bool(1))
            shared.push(SearchDecision.IntAtLeast(0, 1))
            engine.nodeRelaxation(relaxer, cp.session)
            assertTrue(engine.cutPool.cuts().isEmpty())
            assertEquals(1, engine.cutPool.size)
            shared.popTo(0)
            shared.push(SearchDecision.Bool(0))
            shared.push(SearchDecision.IntAtLeast(0, 1))
            engine.nodeRelaxation(relaxer, cp.session)
            assertEquals(1, engine.cutPool.cuts().size)
            assertEquals(facts, engine.cutPool.cuts().single().provenance?.facts?.filter { !it.global })
        }
    }

    @Test
    fun `interior domain removal updates auxiliary presence without changed source endpoints`() {
        val problem = Problem(
            0, 3, arrayOf(IntDomain(0, 4), IntDomain(0, 5), IntDomain(0, 4)),
            arrayOf(
            Table(intArrayOf(0, 1), longArrayOf(0, 5, 2, 2, 4, 0)),
            AllDifferent(intArrayOf(0, 2), 0, 5),
        )
        )
        LpEngine(
            problem,
            LinearObjective(intCoefficients = longArrayOf(1, 0, 0)),
            LpParams(lpPlan = LpPlan(bounding = true, table = true)),
            SolveStatsSink(backend = "presence"),
        ).use { engine ->
            val cp = CpSearchComponent(PropagationSession(problem))
            engine.cpAdapter.attach(cp.session, feasibility = false)
            val shared = SearchSession(listOf(cp, engine.propagator))
            shared.initialize()
            val relaxer = assertNotNull(engine.lpRelaxer)
            val root = engine.nodeRelaxation(relaxer, cp.session)
            val auxiliary = root.colReq.indices.first { column ->
                root.colReq[column]?.toList() == listOf(0L, 2L, 1L, 2L)
            }
            assertEquals(1L, root.model.upper[auxiliary])
            shared.push(SearchDecision.IntEqual(2, 2))

            val child = engine.nodeRelaxation(relaxer, cp.session)

            assertEquals(0L, cp.session.intDomain(0).min)
            assertEquals(4L, cp.session.intDomain(0).max)
            assertTrue(!cp.session.intDomain(0).contains(2L))
            assertEquals(0L, child.model.upper[auxiliary])
            shared.popTo(0)
            assertEquals(1L, engine.nodeRelaxation(relaxer, cp.session).model.upper[auxiliary])
        }
    }

    @Test
    fun `a reseeded native root replaces previous bound authority`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 5)), emptyArray())
        LpEngine(
            problem,
            LinearObjective(intCoefficients = longArrayOf(1)),
            LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "reseed"),
        ).use { engine ->
            val cp = CpSearchComponent(PropagationSession(problem))
            cp.session.seed(Assumptions(ints = mapOf(0 to 4L)))
            cp.rebase()
            engine.cpAdapter.attach(cp.session, feasibility = false)
            val shared = SearchSession(listOf(cp, engine.propagator))
            shared.initialize()
            val relaxer = assertNotNull(engine.lpRelaxer)
            assertEquals(4L, engine.nodeRelaxation(relaxer, cp.session).model.loShift[0])
            cp.session.reseedFrom(Assumptions(ints = mapOf(0 to 1L)))
            cp.rebase()
            shared.resetRootFacts()
            shared.initialize()
            assertEquals(1L, engine.nodeRelaxation(relaxer, cp.session).model.loShift[0])
            assertEquals(0, assertNotNull(engine.propagator.state).depth)
        }
    }
}
