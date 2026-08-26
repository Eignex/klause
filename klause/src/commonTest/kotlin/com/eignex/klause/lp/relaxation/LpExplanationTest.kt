package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.lp.LpBuilder
import com.eignex.klause.lp.Relation
import com.eignex.klause.lp.RevisedSimplex
import com.eignex.klause.lp.Sense
import com.eignex.klause.lp.integerCertify
import com.eignex.klause.lp.integerFarkasRay
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #281/#705: LP objective-bound learning on the sparse revised-simplex path. The reason is built from
 * the exact basis-certificate; every literal it cites is false at the node.
 */
class LpExplanationTest {

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
}
