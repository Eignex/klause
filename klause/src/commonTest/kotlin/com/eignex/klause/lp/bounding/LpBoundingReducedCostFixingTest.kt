package com.eignex.klause.lp.bounding

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.IntegerCertificate
import com.eignex.klause.lp.engine.LpBoundTrail
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpDeductionCertificate
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.VarStatus
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.lp.engine.integerCertify
import com.eignex.klause.lp.engine.nodeDualCertificate
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpBoundingReducedCostFixingTest {

    @Test
    fun `integral continuous objective should use a strict integer fixing cutoff`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 10)),
            emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(10.0),
        )
        val session = PropagationSession(problem)
        val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
        val source = LpBuilder().apply {
            val x = addVar(0L, 10L)
            val r = addRealVar(0.0, 10.0, cost = 1.0)
            addRealRow(intArrayOf(x, r), doubleArrayOf(-1.0, 1.0), Relation.EQ, 0.0)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())
        val certificate = assertNotNull(nodeDualCertificate(model, doubleArrayOf(1.0)))
        val relaxation = LpRelaxation(
            model,
            intArrayOf(0, -1),
            booleanArrayOf(false, false),
            0L,
            intArrayOf(0),
            intArrayOf(),
            colRealId = intArrayOf(-1, 0),
        )

        fix(problem, objective, relaxation, certificate, session, 2.0)

        assertEquals(IntDomain(0, 1), session.intDomain(0))
    }

    @Test
    fun `mixed reduced cost fixing should keep continuous objective units`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)), emptyArray())
        val session = PropagationSession(problem)
        val objective = LinearObjective(intCoefficients = longArrayOf(1L))
        val source = LpBuilder().apply {
            addVar(0L, 10L, cost = 1L)
            addRealVar(0.25, 1.25, cost = 0.5)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())
        val certificate = assertNotNull(nodeDualCertificate(model, doubleArrayOf()))
        val relaxation = LpRelaxation(
            model,
            intArrayOf(0, -1),
            booleanArrayOf(false, false),
            0L,
            intArrayOf(0),
            intArrayOf(),
            colRealId = intArrayOf(-1, 0),
        )

        fix(problem, objective, relaxation, certificate, session, 3.0)

        assertEquals(IntDomain(0, 2), session.intDomain(0))
    }

    @Test
    fun `retained fixing reasons should express the cutoff in source units`() {
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
    fun `retained fixing should decline stale certificates after bound rollback`() {
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
    fun `retained fixing should start at the live endpoint in source coordinates`() {
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

            fix(problem, objective, relaxation, certificate, session, if (coefficient > 0L) 7.0 else -5.0)

            val expected = IntDomain(if (coefficient > 0L) 5L else 6L, if (coefficient > 0L) 6L else 7L)
            assertEquals(expected, session.intDomain(0))
        }
    }

    // Optimum 5 at (5, 0); x1 has reduced cost 9, so a tight incumbent fixes it.
    private fun weighted(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(0, 10), IntDomain(0, 10)),
        factors = arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 5)),
    )

    private val obj = LinearObjective(intCoefficients = longArrayOf(1L, 10L))

    @Test
    fun `reduced cost fixing should preserve the optimum`() {
        val problem = weighted()
        val off = BacktrackSolver(problem.bake()).minimize(obj, BacktrackParams(randomSeed = 1L))
        val on = BacktrackSolver(
            problem.bake(),
        ).minimize(obj, BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true)))

        assertEquals(off.objectiveValue, on.objectiveValue)
        assertEquals(5.0, on.objectiveValue)
    }

    @Test
    fun `reduced cost fixing should fire under a tight external bound`() {
        val problem = weighted()
        val result = BacktrackSolver(problem.bake()).minimize(
            obj,
            BacktrackParams(randomSeed = 1L, objectiveBoundSupplier = { 6.0 }, lpPlan = LpPlan(bounding = true)),
        )
        assertEquals(5.0, result.objectiveValue)
        assertTrue(result.stats.lp.fixed.sum > 0.0)
    }

    @Test
    fun `fixing should include the objective constant in the strict cutoff`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1L), constant = -1L)
        val session = PropagationSession(problem)
        val (relaxation, cert) = singleColumn(hi = 3L, cost = 1L, objectiveConstant = -1L)

        fix(problem, objective, relaxation, cert, session, 2.0)

        assertEquals(IntDomain(0, 2), session.intDomain(0))
    }

    @Test
    fun `fixing should start from the certificate endpoint after a live tightening`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1L))
        val session = PropagationSession(problem)
        val (relaxation, cert) = singleColumn(hi = 10L, cost = 1L)
        assertIs<PropagationResult.Implied>(session.implyIntAtLeast(0, 2L))

        fix(problem, objective, relaxation, cert, session, 4.0)

        assertEquals(IntDomain(2, 3), session.intDomain(0))
    }

    @Test
    fun `learned fixing reason should include basic column support and cutoff`() {
        val problem = Problem(0, 3, arrayOf(IntDomain(0, 20), IntDomain(0, 10), IntDomain(0, 120)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(0L, 0L, 1L))
        val session = PropagationSession(problem)
        assertIs<PropagationResult.Implied>(session.pinIntAtMost(0, 10L))
        assertIs<PropagationResult.Implied>(session.pinIntAtMost(2, 1L))
        val builder = LpBuilder()
        val basic = builder.addVar(0L, 10L)
        val target = builder.addVar(0L, 10L)
        val objectiveCol = builder.addVar(0L, 120L, cost = 1L)
        builder.addRow(intArrayOf(objectiveCol, basic, target), longArrayOf(1L, -2L, -10L), Relation.EQ, 0L)
        builder.addRow(intArrayOf(basic), longArrayOf(3L), Relation.GE, 1L)
        val model = builder.build(Sense.MINIMIZE)
        val result = assertNotNull(RevisedSimplex(model).solve())
        val cert = assertNotNull(integerCertify(model, result.duals, scaleBits = 0))
        assertEquals(VarStatus.BASIC, result.basis.status[basic])
        val relaxation = LpRelaxation(
            model,
            intArrayOf(0, 1, 2),
            booleanArrayOf(false, false, false),
            0L,
            intArrayOf(basic, target, objectiveCol),
            IntArray(0),
        )

        fix(problem, objective, relaxation, cert, session, 2.0, objectiveVar = 2, learn = true)

        val reason = assertNotNull(reducedCostFixingReasons(relaxation, cert, session, 2, 1L)).reasonFor(target)
        assertTrue(session.boundLeLit(0, 10L, positive = false) in reason)
        assertTrue(session.boundLeLit(2, 1L, positive = false) in reason)
    }

    @Test
    fun `affine objective cutoff should not be read as a variable cutoff`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 40)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(2L), constant = 10L)
        val session = PropagationSession(problem)
        val (relaxation, cert) = singleColumn(hi = 40L, cost = 2L, objectiveConstant = 10L)

        assertNull(reducedCostFixingReasons(relaxation, cert, session, 0, 49L))
        fix(problem, objective, relaxation, cert, session, 50.0, objectiveVar = 0, learn = true)

        assertEquals(IntDomain(0, 19), session.intDomain(0))
    }

    private fun singleColumn(
        hi: Long,
        cost: Long,
        objectiveConstant: Long = 0L,
    ): Pair<LpRelaxation, IntegerCertificate> {
        val builder = LpBuilder()
        val x = builder.addVar(0L, hi, cost = cost)
        val model = builder.build(Sense.MINIMIZE)
        val cert = assertNotNull(integerCertify(model, assertNotNull(RevisedSimplex(model).solve()).duals))
        val relaxation = LpRelaxation(
            model,
            intArrayOf(0),
            booleanArrayOf(false),
            objectiveConstant,
            intArrayOf(x),
            IntArray(0),
        )
        return relaxation to cert
    }

    @Suppress("LongParameterList")
    private fun fix(
        problem: Problem,
        objective: LinearObjective,
        relaxation: LpRelaxation,
        cert: LpDeductionCertificate,
        session: PropagationSession,
        cutoff: Double,
        objectiveVar: Int = -1,
        learn: Boolean = false,
    ) {
        val sink = SolveStatsSink(backend = "fixing")
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink).use {
            it.applySparseReducedCostFixing(
                relaxation,
                cert,
                session,
                cutoff,
                sink,
                objectiveVar = objectiveVar,
                learn = learn,
            )
        }
    }
}
