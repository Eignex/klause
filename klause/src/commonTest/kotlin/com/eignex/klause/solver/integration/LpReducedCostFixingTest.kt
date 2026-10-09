package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.bounding.applySparseReducedCostFixing
import com.eignex.klause.lp.bounding.reducedCostFixingReasons
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.VarStatus
import com.eignex.klause.lp.engine.integerCertify
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.LpBoundTrail
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.mark
import com.eignex.klause.propagation.setIntMinAsDecision
import com.eignex.klause.propagation.undoTo
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #21: LP reduced-cost fixing wired into BacktrackSolver branch-and-bound. */
class LpReducedCostFixingTest {

    @Test
    fun `retained affine fixing reasons express the cutoff in absolute source units`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(3, 9)), emptyArray())
        val session = PropagationSession(problem)
        session.implyIntAtLeast(0, 5L)
        session.implyIntAtMost(0, 6L)
        val source = LpBuilder().apply { addVar(3L, 9L, cost = 2L) }.build(Sense.MINIMIZE)
        val trail = LpBoundTrail(assertNotNull(source.authoritativeModel()))
        assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2L)), 7L))
        assertTrue(trail.assertBound(0, true, ExactLpSide(ExactLpNumber.of(3L)), 8L))
        val model = assertNotNull(trail.state.toWorkingModel())
        val certificate = assertNotNull(integerCertify(model, doubleArrayOf()))
        val relaxation = LpRelaxation(model, intArrayOf(0), booleanArrayOf(false), 10L, intArrayOf(0), intArrayOf())

        val support = assertNotNull(reducedCostFixingReasons(relaxation, certificate, session, 0, 23L))

        assertEquals(listOf(session.boundLeLit(0, 6L, false)), support.reasonFor(0).toList())
        assertNull(reducedCostFixingReasons(relaxation, certificate, session, 0, 21L))
    }

    @Test
    fun `retained reduced cost fixing declines stale certificates after bound rollback`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(3, 9)), emptyArray())
        val session = PropagationSession(problem)
        val objective = LinearObjective(intCoefficients = longArrayOf(1L))
        val source = LpBuilder().apply { addVar(3L, 9L, cost = 1L) }.build(Sense.MINIMIZE)
        val trail = LpBoundTrail(assertNotNull(source.authoritativeModel()))
        assertTrue(trail.push())
        assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2L)), 7L))
        val certificate = assertNotNull(integerCertify(assertNotNull(trail.state.toWorkingModel()), doubleArrayOf()))
        assertTrue(trail.pop(0))
        val model = assertNotNull(trail.state.toWorkingModel())
        val relaxation = LpRelaxation(model, intArrayOf(0), booleanArrayOf(false), 0L, intArrayOf(0), intArrayOf())
        val sink = SolveStatsSink(backend = "retained-rollback")
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink).use { engine ->

            engine.applySparseReducedCostFixing(relaxation, certificate, session, 8.0, sink)

            assertEquals(IntDomain(3, 9), session.intDomain(0))
            assertNull(reducedCostFixingReasons(relaxation, certificate, session, 0, 7L))
            val fresh = assertNotNull(integerCertify(model, doubleArrayOf()))
            engine.applySparseReducedCostFixing(relaxation, fresh, session, 8.0, sink)
            assertEquals(IntDomain(3, 7), session.intDomain(0))
        }
    }

    @Test
    fun `retained fixing starts at the live endpoint in the original source coordinates`() {
        for (coefficient in listOf(-1L, 1L)) {
            val problem = Problem(0, 1, arrayOf(IntDomain(3, 9)), emptyArray())
            val objective = LinearObjective(intCoefficients = longArrayOf(coefficient))
            val session = PropagationSession(problem)
            session.implyIntAtLeast(0, 5L)
            session.implyIntAtMost(0, 7L)
            val source = LpBuilder().apply { addVar(3L, 9L, cost = coefficient) }.build(Sense.MINIMIZE)
            val trail = LpBoundTrail(assertNotNull(source.authoritativeModel()))
            assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2L)), 7L))
            assertTrue(trail.assertBound(0, true, ExactLpSide(ExactLpNumber.of(4L)), 8L))
            val model = assertNotNull(trail.state.toWorkingModel())
            val certificate = assertNotNull(integerCertify(model, doubleArrayOf()))
            val relaxation = LpRelaxation(model, intArrayOf(0), booleanArrayOf(false), 0L, intArrayOf(0), intArrayOf())
            val sink = SolveStatsSink(backend = "retained-fixing")
            LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink).use { engine ->

                engine.applySparseReducedCostFixing(
                    relaxation, certificate, session, if (coefficient > 0L) 7.0 else -5.0, sink,
                )

                val expected = IntDomain(if (coefficient > 0L) 5L else 6L, if (coefficient > 0L) 6L else 7L)
                assertEquals(expected, session.intDomain(0))
            }
        }
    }

    // min x0 + 10·x1 s.t. x0 + x1 >= 5, both in [0,10]. Optimum 5 at (5,0). With the constraint
    // active the objective is 5 + 9·x1, so x1's reduced cost is 9: under a tight incumbent it is
    // exactly the variable reduced-cost fixing should pin down.
    private fun weighted(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(0, 10), IntDomain(0, 10)),
        factors = arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 5)),
    )

    private val obj = LinearObjective(intCoefficients = longArrayOf(1L, 10L))

    @Test
    fun `reduced-cost fixing preserves the optimum`() {
        val problem = weighted()
        val off = BacktrackSolver(problem.bake()).minimize(obj, BacktrackParams(randomSeed = 1L))
        val on = BacktrackSolver(
            problem.bake(),
        ).minimize(obj, BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true)))

        assertEquals(off.objectiveValue, on.objectiveValue, "fixing must not change the optimum")
        assertEquals(5.0, on.objectiveValue)
    }

    @Test
    fun `reduced-cost fixing fires under a tight external bound`() {
        // A generous external incumbent of 6 makes the gap known from the first node, so the LP's
        // reduced cost on x1 (= 9) immediately bounds x1 in. The optimum is still 5.
        val problem = weighted()
        val result = BacktrackSolver(problem.bake()).minimize(
            obj,
            BacktrackParams(randomSeed = 1L, objectiveBoundSupplier = { 6.0 }, lpPlan = LpPlan(bounding = true)),
        )
        assertTrue(result.objectiveValue == 5.0, "optimum should still be reached, got ${result.objectiveValue}")
        assertTrue(result.stats.lp.fixed.sum > 0.0, "expected reduced-cost fixings, got ${result.stats.lp.fixed.sum}")
    }

    @Test
    fun `source objective constant is included before strict cutoff fixing`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1L), constant = -1L)
        val session = PropagationSession(problem)
        val builder = LpBuilder()
        val x = builder.addVar(0L, 3L, cost = 1L)
        val model = builder.build(Sense.MINIMIZE)
        val result = assertNotNull(RevisedSimplex(model).solve())
        val cert = assertNotNull(integerCertify(model, result.duals))
        val relaxation = LpRelaxation(
            model,
            intArrayOf(0),
            booleanArrayOf(false),
            -1L,
            intArrayOf(x),
            IntArray(0),
        )
        val engine = LpEngine(
            problem,
            objective,
            LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "constant"),
        )

        engine.use {
            it.applySparseReducedCostFixing(relaxation, cert, session, 2.0, SolveStatsSink(backend = "fix"))
        }

        assertEquals(IntDomain(0, 2), session.intDomain(0))
    }

    @Test
    fun `fixing starts from the certificate endpoint after a live tightening`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1L))
        val session = PropagationSession(problem)
        val builder = LpBuilder()
        val x = builder.addVar(0L, 10L, cost = 1L)
        val model = builder.build(Sense.MINIMIZE)
        val result = assertNotNull(RevisedSimplex(model).solve())
        val cert = assertNotNull(integerCertify(model, result.duals))
        val relaxation = LpRelaxation(
            model,
            intArrayOf(0),
            booleanArrayOf(false),
            0L,
            intArrayOf(x),
            IntArray(0),
        )
        assertIs<com.eignex.klause.propagation.PropagationResult.Implied>(session.implyIntAtLeast(0, 2L))
        val engine = LpEngine(
            problem,
            objective,
            LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "endpoint"),
        )

        engine.use {
            it.applySparseReducedCostFixing(relaxation, cert, session, 4.0, SolveStatsSink(backend = "fix"))
        }

        assertEquals(IntDomain(2, 3), session.intDomain(0))
    }

    @Test
    fun `learned fixing reason includes basic-column support and cutoff`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 20), IntDomain(0, 10), IntDomain(0, 120)),
            factors = emptyArray(),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(0L, 0L, 1L))
        val session = PropagationSession(problem)
        assertIs<com.eignex.klause.propagation.PropagationResult.Implied>(session.pinIntAtMost(0, 10L))
        assertIs<com.eignex.klause.propagation.PropagationResult.Implied>(session.pinIntAtMost(2, 1L))
        val builder = LpBuilder()
        val basic = builder.addVar(0L, 10L)
        val target = builder.addVar(0L, 10L)
        val objectiveCol = builder.addVar(0L, 120L, cost = 1L)
        builder.addRow(
            intArrayOf(objectiveCol, basic, target),
            longArrayOf(1L, -2L, -10L),
            Relation.EQ,
            0L,
        )
        builder.addRow(intArrayOf(basic), longArrayOf(3L), Relation.GE, 1L)
        val model = builder.build(Sense.MINIMIZE)
        val result = assertNotNull(RevisedSimplex(model).solve())
        val cert = assertNotNull(integerCertify(model, result.duals, scaleBits = 0))
        assertEquals(VarStatus.BASIC, result.basis.status[basic])
        assertTrue(cert.reducedCostSign(basic) < 0)
        val relaxation = LpRelaxation(
            model,
            intArrayOf(0, 1, 2),
            booleanArrayOf(false, false, false),
            0L,
            intArrayOf(basic, target, objectiveCol),
            IntArray(0),
        )
        val engine = LpEngine(
            problem,
            objective,
            LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "reason"),
        )

        engine.use {
            it.applySparseReducedCostFixing(
                relaxation,
                cert,
                session,
                2.0,
                SolveStatsSink(backend = "fix"),
                objectiveVar = 2,
                learn = true,
            )
        }

        val reason = assertNotNull(reducedCostFixingReasons(relaxation, cert, session, 2, 1L)).reasonFor(target)
        assertTrue(
            session.boundLeLit(0, 10L, positive = false) in reason,
            "missing basic support from ${reason.toList()}",
        )
        assertTrue(
            session.boundLeLit(2, 1L, positive = false) in reason,
            "missing cutoff from ${reason.toList()}",
        )
    }

    @Test
    fun `affine objective cutoff is not mislabeled as a variable cutoff`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 40)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(2L), constant = 10L)
        val session = PropagationSession(problem)
        val builder = LpBuilder()
        val x = builder.addVar(0L, 40L, cost = 2L)
        val model = builder.build(Sense.MINIMIZE)
        val result = assertNotNull(RevisedSimplex(model).solve())
        val cert = assertNotNull(integerCertify(model, result.duals))
        val relaxation = LpRelaxation(
            model,
            intArrayOf(0),
            booleanArrayOf(false),
            10L,
            intArrayOf(x),
            IntArray(0),
        )
        val engine = LpEngine(
            problem,
            objective,
            LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "affine"),
        )

        assertNull(reducedCostFixingReasons(relaxation, cert, session, 0, 49L))
        engine.use {
            it.applySparseReducedCostFixing(
                relaxation,
                cert,
                session,
                50.0,
                SolveStatsSink(backend = "fix"),
                objectiveVar = 0,
                learn = true,
            )
        }

        assertEquals(IntDomain(0, 19), session.intDomain(0))
    }

    @Test
    fun `local fixing retracts with its search level`() {
        val problem = Problem(0, 2, arrayOf(IntDomain(0, 1), IntDomain(0, 3)), emptyArray())
        val state = PropagationState(problem, Assumptions.None).also { it.undoLogging = true }
        val root = state.mark()
        assertTrue(state.setIntMinAsDecision(0, 1L))
        assertTrue(state.tightenIntMax(1, 1L))
        assertEquals(IntDomain(0, 1), state.intDomains[1])

        state.undoTo(root)

        assertEquals(IntDomain(0, 3), state.intDomains[1])
    }

    @Test
    fun `replacement objective uses a fresh fixing lifetime`() {
        fun fixedDomain(coefficient: Long, cutoff: Double): IntDomain {
            val problem = Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyArray())
            val objective = LinearObjective(intCoefficients = longArrayOf(coefficient))
            val session = PropagationSession(problem)
            val builder = LpBuilder()
            val x = builder.addVar(0L, 3L, cost = coefficient)
            val model = builder.build(Sense.MINIMIZE)
            val result = assertNotNull(RevisedSimplex(model).solve())
            val cert = assertNotNull(integerCertify(model, result.duals))
            val relaxation = LpRelaxation(
                model,
                intArrayOf(0),
                booleanArrayOf(false),
                0L,
                intArrayOf(x),
                IntArray(0),
            )
            val engine = LpEngine(
                problem,
                objective,
                LpParams(lpPlan = LpPlan(bounding = true)),
                SolveStatsSink(backend = "replacement"),
            )
            engine.use {
                it.applySparseReducedCostFixing(
                    relaxation,
                    cert,
                    session,
                    cutoff,
                    SolveStatsSink(backend = "fix"),
                )
            }
            return session.intDomain(0)
        }

        assertEquals(IntDomain(0, 0), fixedDomain(1L, 1.0))
        assertEquals(IntDomain(3, 3), fixedDomain(-1L, -2.0))
    }
}
