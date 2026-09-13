package com.eignex.klause.lp.bounding

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.Inprocessing
import com.eignex.klause.backtrack.lp.LpHints
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.cut.SourceCut
import com.eignex.klause.lp.cut.orNull
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.CutExpression
import com.eignex.klause.lp.engine.CutPremise
import com.eignex.klause.lp.engine.CutProofFact
import com.eignex.klause.lp.engine.CutProvenance
import com.eignex.klause.lp.engine.CutSource
import com.eignex.klause.lp.engine.CutSourceKind
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpEpochPassTest {
    @Test
    fun `equal row counts do not retain the old big M matrix`() {
        val problem = Problem(
            1,
            2,
            Array(2) { IntDomain(0, 6) },
            arrayOf(ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 5)),
        )
        val session = PropagationSession(problem)
        LpEngine(
            problem,
            LinearObjective(intCoefficients = longArrayOf(1, 1)),
            LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "epochs"),
        ).use { engine ->
            assertTrue(engine.rebuildEpoch(session, Cancellation.Never))
            val first = assertNotNull(engine.epochState)
            session.implyIntAtMost(0, 4)
            assertTrue(engine.rebuildEpoch(session, Cancellation.Never))
            val second = assertNotNull(engine.epochState)
            assertEquals(first.relaxation.model.m, second.relaxation.model.m)
            assertFalse(first.relaxation.model.csc.colVal.contentEquals(second.relaxation.model.csc.colVal))
            assertEquals(2, engine.epochRebuilds)
            val rebound = engine.nodeRelaxation(assertNotNull(engine.lpRelaxer), session)
            assertNotNull(engine.solveNode(rebound.model, null, Cancellation.Never))
            val model = rebound.model
            for (x in 0L..4L) {
                for (y in 0L..6L) {
                    val values = LongArray(model.n)
                    values[rebound.intColOf[0]] = x
                    values[rebound.intColOf[1]] = y
                    values[rebound.boolColOf[0]] = if (x + y <= 5L) 1L else 0L
                    val activity = LongArray(model.m)
                    for (column in 0 until model.n) {
                        model.forEachInColumn(column) { row, a ->
                            activity[row] += a * (values[column] - model.loShift[column])
                        }
                    }
                    for (row in activity.indices) assertTrue(activity[row] <= model.rhs[row])
                }
            }
        }
    }

    @Test
    fun `overflowing regeneration declines and preserves the prepared owner`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(-Long.MAX_VALUE, Long.MAX_VALUE)), emptyArray())
        val narrow = PropagationSession(problem)
        narrow.implyIntAtLeast(0, 0)
        narrow.implyIntAtMost(0, 4)
        LpEngine(
            problem,
            LinearObjective(intCoefficients = longArrayOf(1)),
            LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "epochs"),
        ).use { engine ->
            assertTrue(engine.rebuildEpoch(narrow, Cancellation.Never))
            val epoch = engine.epochState
            val owner = engine.propagator.state
            assertFalse(engine.rebuildEpoch(PropagationSession(problem), Cancellation.Never))
            assertEquals(1, engine.epochMetrics.modelDeclines)
            assertSame(epoch, engine.epochState)
            assertSame(owner, engine.propagator.state)
            assertNotNull(engine.propagator.solve())
        }
    }

    @Test
    fun `a replacement root discards its epoch before the next solve`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 5)), emptyArray())
        val session = PropagationSession(problem)
        session.implyIntAtLeast(0, 2)
        LpEngine(
            problem,
            LinearObjective(intCoefficients = longArrayOf(1)),
            LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "epochs"),
        ).use { engine ->
            assertTrue(engine.rebuildEpoch(session, Cancellation.Never))
            val rebound = engine.nodeRelaxation(assertNotNull(engine.lpRelaxer), PropagationSession(problem))
            assertNull(engine.epochState)
            assertEquals(0L, rebound.model.loShift[0])
            assertNotNull(engine.solveNode(rebound.model, null, Cancellation.Never))
        }
    }

    @Test
    fun `cancelled rebuild preserves hints and committed rebuild clears them`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 5)), emptyArray())
        val session = PropagationSession(problem)
        LpEngine(
            problem,
            LinearObjective(intCoefficients = longArrayOf(1)),
            LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "epochs"),
        ).use { engine ->
            val hints = LpHints(1, 0)
            engine.lpHints = hints
            val relaxation = engine.nodeRelaxation(assertNotNull(engine.lpRelaxer), session)
            hints.record(relaxation, doubleArrayOf(1.5), doubleArrayOf())
            val before = hints.branchScore(VarRef.IntVar(0))
            assertFalse(before.isNaN())
            assertFalse(engine.rebuildEpoch(session, Cancellation { true }))
            assertEquals(before, hints.branchScore(VarRef.IntVar(0)))
            assertTrue(engine.rebuildEpoch(session, Cancellation.Never))
            assertTrue(hints.branchScore(VarRef.IntVar(0)).isNaN())
        }
    }

    @Test
    fun `root epochs are scoped to the original objective engine and reseed generation`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 5)), emptyArray())
        val session = PropagationSession(problem)
        LpEngine(
            problem,
            LinearObjective(intCoefficients = longArrayOf(1)),
            LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "epochs"),
        ).use { engine ->
            assertTrue(engine.rebuildEpoch(session, Cancellation.Never))
            val old = engine.epochState
            engine.forObjective(LinearObjective(intCoefficients = longArrayOf(-1)), Cancellation.Never).use { other ->
                assertNull(other.epochState)
                assertSame(old, engine.epochState)
            }
            engine.retireEpochRoot()
            assertNull(engine.epochState)
            assertFalse(engine.rebuildEpoch(session, Cancellation.Never))
        }
    }

    @Test
    fun `raw positional cuts are not installed by a matrix epoch`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 5)), emptyArray())
        val session = PropagationSession(problem)
        LpEngine(
            problem,
            LinearObjective(intCoefficients = longArrayOf(1)),
            LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "epochs"),
        ).use { engine ->
            engine.cutPool.add(Cut(intArrayOf(0), longArrayOf(1), Relation.GE, 9, global = true))
            assertTrue(engine.rebuildEpoch(session, Cancellation.Never))
            assertEquals(0, assertNotNull(engine.epochState).relaxation.model.m)
            engine.nodeRelaxation(assertNotNull(engine.lpRelaxer), session)
            assertTrue(engine.cutPool.cuts().isEmpty())
        }
    }

    @Test
    fun `a conditional source cut becomes dormant when its epoch root is replaced`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 5)), emptyArray())
        val session = PropagationSession(problem)
        session.implyIntAtLeast(0, 2)
        LpEngine(
            problem,
            LinearObjective(intCoefficients = longArrayOf(1)),
            LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "epochs"),
        ).use { engine ->
            assertTrue(engine.rebuildEpoch(session, Cancellation.Never))
            val map = assertNotNull(assertNotNull(engine.epochState).relaxation.sourceMap)
            val expression = CutExpression(mapOf(CutSource(CutSourceKind.INTEGER, 0) to BigFraction.ONE))
            val threshold = BigFraction.ofLong(2)
            val fact = CutPremise.Bound(expression, false, threshold)
            val cut = SourceCut(
                expression,
                Relation.GE,
                threshold,
                CutProvenance(problem, map.epoch, listOf(CutProofFact(fact, false))),
            )
            assertNotNull(cut.toCut(map).orNull())
            assertTrue(engine.cutPool.add(cut, map))
            val donorCuts = engine.cutPool.cuts()
            val mapped = engine.cutPool.mappedCuts(map)
            assertEquals(donorCuts, engine.cutPool.cuts())
            assertNotNull(mapped.single().orNull())
            assertTrue(engine.rebuildEpoch(session, Cancellation.Never))
            assertTrue(assertNotNull(engine.epochState).relaxation.tidyDerivation!!.sourceModel.m > 0)
            assertEquals(donorCuts, engine.cutPool.cuts())
            val wider = engine.nodeRelaxation(assertNotNull(engine.lpRelaxer), PropagationSession(problem))
            assertNull(cut.toCut(assertNotNull(wider.sourceMap)).orNull())
            assertTrue(engine.cutPool.cuts().isEmpty())
            assertNotNull(engine.cutPool.mappedCuts(map).single().orNull())
            assertTrue(engine.cutPool.cuts().isEmpty())
        }
    }

    @Test
    fun `the pass excludes assumption seeded roots and preserves variable ids`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 5)), emptyArray())
        val session = PropagationSession(problem)
        LpEngine(
            problem,
            LinearObjective(intCoefficients = longArrayOf(1)),
            LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "epochs"),
        ).use { engine ->
            val pass = LpEpochPass(engine)
            assertTrue(pass.preservesVariables)
            pass.run(session, BacktrackParams(lpEpochs = true, assumptions = Assumptions.None.withInt(0, 2)))
            assertEquals(0, pass.runs)
            assertNull(engine.epochState)
        }
    }

    @Test
    fun `reset discards epoch state through the existing inprocessing lifecycle`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 4)), emptyArray())
        LpEngine(
            problem,
            LinearObjective(intCoefficients = longArrayOf(1)),
            LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "epochs"),
        ).use { engine ->
            val loop = assertNotNull(Inprocessing.from(BacktrackParams(lpEpochs = true), engine))
            assertTrue(engine.rebuildEpoch(PropagationSession(problem), Cancellation.Never))
            loop.reset()
            assertNull(engine.epochState)
            assertNull(engine.propagator.state)
        }
    }
}
