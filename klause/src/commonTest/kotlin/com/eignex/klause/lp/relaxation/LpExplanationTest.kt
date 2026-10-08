package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Lit
import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.LpBoundTrail
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpScopedRow
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.integerCertify
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.lp.engine.integerFarkasRay
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.simplex.exact.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #281/#705: LP objective-bound learning on the sparse revised-simplex path. The reason is built from
 * the exact basis-certificate; every literal it cites is false at the node.
 */
class LpExplanationTest {

    @Test
    fun `retained objective explanations decline certificates from popped bounds`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(3, 9)), arrayOf<Factor>())
        val session = PropagationSession(problem)
        val relaxation = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1L))).build(session)
        val trail = LpBoundTrail(assertNotNull(relaxation.model.authoritativeModel()))
        assertTrue(trail.push())
        assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2L)), 7L))
        val child = relaxation.withModel(assertNotNull(trail.state.toWorkingModel()))
        val certificate = assertNotNull(integerCertify(child.model, doubleArrayOf()))
        assertTrue(trail.pop(0))
        val parent = relaxation.withModel(assertNotNull(trail.state.toWorkingModel()))

        assertNull(LpExplanation.objectiveBoundReason(parent, certificate, session))
        val fresh = assertNotNull(integerCertify(parent.model, doubleArrayOf()))
        assertEquals(listOf(session.boundGeLit(0, 3L, false)), LpExplanation.objectiveBoundReason(parent, fresh, session)?.toList())
    }

    @Test
    fun `retained objective explanations cite live absolute endpoints in root coordinates`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(3, 9)), arrayOf<Factor>())
        val session = PropagationSession(problem)
        val relaxation = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(2L))).build(session)
        val trail = LpBoundTrail(assertNotNull(relaxation.model.authoritativeModel()))
        assertTrue(trail.push())
        session.implyIntAtLeast(0, 5L)
        session.implyIntAtMost(0, 7L)
        assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2L)), 7L))
        assertTrue(trail.assertBound(0, true, ExactLpSide(ExactLpNumber.of(4L)), 8L))
        val retained = relaxation.withModel(assertNotNull(trail.state.toWorkingModel()))
        val certificate = assertNotNull(integerCertify(retained.model, doubleArrayOf()))

        val reason = LpExplanation.objectiveBoundReason(retained, certificate, session)

        assertEquals(listOf(session.boundGeLit(0, 5L, positive = false)), reason?.toList())
        assertEquals(session.boundLeLit(0, 7L, positive = false), LpExplanation.premiseLit(retained, session, 0, false))
    }

    @Test
    fun `retained Boolean explanations follow actual pinned endpoints`() {
        for (value in listOf(false, true)) {
            val problem = Problem(1, 0, emptyArray(), arrayOf<Factor>())
            val session = PropagationSession(problem)
            val source = LpBuilder().apply { addVar(0L, 1L) }.build(Sense.MINIMIZE)
            val trail = LpBoundTrail(assertNotNull(source.authoritativeModel()))
            session.implyBool(0, value)
            assertTrue(trail.assertBound(0, !value, ExactLpSide(ExactLpNumber.of(if (value) 1L else 0L)), 7L))
            val retained = LpRelaxation(
                assertNotNull(trail.state.toWorkingModel()), intArrayOf(0), booleanArrayOf(true), 0L,
                intArrayOf(), intArrayOf(0),
            )

            val premise = LpExplanation.premiseLit(retained, session, 0, lowerSide = value)

            assertEquals(Lit.make(0, !value), premise)
        }
    }

    @Test
    fun `retained integer explanations round rational and strict sides in the source lattice`() {
        for (strict in listOf(false, true)) {
            val problem = Problem(0, 1, arrayOf(IntDomain(3, 9)), arrayOf<Factor>())
            val session = PropagationSession(problem)
            val relaxation = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1L))).build(session)
            val trail = LpBoundTrail(assertNotNull(relaxation.model.authoritativeModel()))
            val lo = if (strict) 6L else 5L
            val hi = if (strict) 6L else 7L
            session.implyIntAtLeast(0, lo)
            session.implyIntAtMost(0, hi)
            val lower = if (strict) BigFraction.ofLong(2L) else assertNotNull(BigFraction.ofDouble(1.5))
            val upper = if (strict) BigFraction.ofLong(4L) else assertNotNull(BigFraction.ofDouble(4.5))
            assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(lower), strict), 7L))
            assertTrue(trail.assertBound(0, true, ExactLpSide(ExactLpNumber.of(upper), strict), 8L))
            val retained = relaxation.withModel(assertNotNull(trail.state.toWorkingModel()))

            assertEquals(session.boundGeLit(0, lo, false), LpExplanation.premiseLit(retained, session, 0, true))
            assertEquals(session.boundLeLit(0, hi, false), LpExplanation.premiseLit(retained, session, 0, false))
        }
    }

    @Test
    fun `retained rational Farkas explanations reject a popped row and name its live bound`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 5)), arrayOf<Factor>())
        val session = PropagationSession(problem)
        val relaxation = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1L))).build(session)
        val trail = LpBoundTrail(assertNotNull(relaxation.model.authoritativeModel()))
        assertTrue(trail.push())
        session.implyIntAtLeast(0, 2L)
        assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2L)), 7L))
        val third = ExactLpNumber.of(BigFraction.ofLong(3L).reciprocal())
        assertTrue(trail.append(
            LpScopedRow(0L, listOf(0 to third), third, ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(0L))))),
            true,
        ))
        val retained = relaxation.withModel(assertNotNull(trail.state.toWorkingModel()))
        val ray = assertNotNull(integerFarkasRay(retained.model, doubleArrayOf(-1.0)))

        val clause = LpExplanation.infeasibilityClause(retained, ray, session)

        assertEquals(listOf(session.boundGeLit(0, 2L, false)), clause?.toList())
        assertTrue(trail.pop(0))
        val parent = relaxation.withModel(assertNotNull(trail.state.toWorkingModel()))
        assertNull(LpExplanation.infeasibilityClause(parent, ray, session))
    }

    @Test
    fun `objective-bound reason cites the load-bearing column premise and is all-false at the node`() {
        // minimize x, x in [3,8], constraint x >= 5: LP optimum 5, the binding bound is x >= 5 (its
        // GE row), so the reason cites the negated live lower-bound premise of x.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(3, 8)),
            factors = arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 5)),
        )
        val session = PropagationSession(problem)
        val relaxation = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1)))
            .build(session)
        val result = assertNotNull(RevisedSimplex(relaxation.model).solve())
        val cert = assertNotNull(integerCertify(relaxation.model, result.duals))
        val reason = LpExplanation.objectiveBoundReason(relaxation, cert, session)
        assertNotNull(reason, "an optimal LP over global rows must yield an objective-bound reason")
        // x is seated at its (binding) lower bound with a positive reduced cost, so the reason cites
        // the negated live lower-bound premise of x — the load-bearing support for objective >= 5.
        val lo = relaxation.model.loShift[0]
        assertTrue(
            session.boundGeLit(0, lo, positive = false) in reason.toList(),
            "reason ${reason.toList()} must cite the negated lower-bound premise of x (lo=$lo)",
        )
    }

    @Test
    fun `infeasible node lp yields a bound-atom nogood from the farkas ray`() {
        // x in [2,5] with x <= 1: the LP is infeasible and the load-bearing reason is x's lower bound,
        // so the Farkas ray names x and the clause is the single literal ¬(x >= 2).
        val b = LpBuilder()
        val x = b.addVar(2, 5, cost = 0)
        b.addRow(mapOf(x to 1L), Relation.LE, 1)
        val model = b.build(Sense.MINIMIZE)
        val simplex = RevisedSimplex(model)
        assertTrue(simplex.solve() == null, "the LP is infeasible, so solve() must return null")
        val ray = assertNotNull(integerFarkasRay(model, assertNotNull(simplex.infeasibleRay)))
        val relaxation = LpRelaxation(
            model = model,
            colVarId = intArrayOf(x),
            colIsBool = booleanArrayOf(false),
            objectiveConstant = 0L,
            intColOf = intArrayOf(x),
            boolColOf = IntArray(0),
        )
        // A clean session (no conflicting constraint) so the premise atom resolves; x stays in [2,5].
        val session = PropagationSession(Problem(0, 1, arrayOf(IntDomain(2, 5)), arrayOf<Factor>()))
        val clause = LpExplanation.infeasibilityClause(relaxation, ray, session)
        assertEquals(listOf(session.boundGeLit(x, 2, positive = false)), clause?.toList())
    }

    @Test
    fun `a farkas nogood through a real row cites the bound that row carries`() {
        // x in [2,5], y in [0,5]; the real row 0.5x - 0.5y <= 0 and the integer row y <= 1. Their sum is
        // x <= 1, so the proof rests on x >= 2 alone, and y cancels out of it.
        val b = LpBuilder()
        val x = b.addVar(2, 5, cost = 0)
        val y = b.addVar(0, 5, cost = 0)
        b.addRealRow(intArrayOf(x, y), doubleArrayOf(0.5, -0.5), Relation.LE, 0.0)
        b.addRow(mapOf(y to 1L), Relation.LE, 1)
        val model = b.build(Sense.MINIMIZE)
        val simplex = RevisedSimplex(model)
        assertTrue(simplex.solve() == null, "the LP is infeasible, so solve() must return null")
        val ray = assertNotNull(integerFarkasRay(model, assertNotNull(simplex.infeasibleRay)))
        val relaxation = LpRelaxation(
            model = model,
            colVarId = intArrayOf(x, y),
            colIsBool = booleanArrayOf(false, false),
            objectiveConstant = 0L,
            intColOf = intArrayOf(x, y),
            boolColOf = IntArray(0),
        )
        val session = PropagationSession(Problem(0, 2, arrayOf(IntDomain(2, 5), IntDomain(0, 5)), arrayOf<Factor>()))

        val clause = LpExplanation.infeasibilityClause(relaxation, ray, session)

        assertEquals(listOf(session.boundGeLit(x, 2, positive = false)), clause?.toList())
    }
}
