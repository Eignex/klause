package com.eignex.klause.solver.lp

import com.eignex.klause.ast.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.PseudoBoolean
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #19: the CP-to-LP relaxation builder over real Problems and live propagation sessions. */
class CpToLpRelaxationTest {

    private val eps = 1e-9

    /** Solve the relaxation of [problem] under [objective] and return (solution, relaxation). */
    private fun solve(problem: Problem, objective: LinearObjective?): Pair<LpSolution, LpRelaxation> {
        val relaxation = CpToLpRelaxation(problem, objective).build(PropagationSession(problem))
        return DualSimplex(relaxation.model).solve() to relaxation
    }

    /** LP column standing for integer variable [v], or -1. */
    private fun intCol(r: LpRelaxation, v: Int): Int {
        for (c in r.colVarId.indices) if (!r.colIsBool[c] && r.colVarId[c] == v) return c
        return -1
    }

    @Test
    fun `linear constraints bound the objective`() {
        // min x0  s.t.  x0 + x1 >= 5,  x1 <= 3,  x0,x1 in [0,10].  -> x0 >= 2.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 10), IntDomain(0, 10)),
            factors = arrayOf<Factor>(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 5),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.LE, 3),
            ),
        )
        val (sol, r) = solve(p, LinearObjective(intCoefficients = longArrayOf(1L, 0L)))

        assertEquals(LpStatus.OPTIMAL, sol.status)
        assertEquals(2.0, sol.objectiveValue + r.objectiveConstant, eps)
        assertEquals(2.0, sol.primal(intCol(r, 0)), eps)
    }

    @Test
    fun `objective constant is carried separately`() {
        val p = Problem(0, 1, arrayOf(IntDomain(0, 10)), arrayOf<Factor>())
        val (sol, r) = solve(p, LinearObjective(intCoefficients = longArrayOf(1L), constant = 100L))

        assertEquals(LpStatus.OPTIMAL, sol.status)
        assertEquals(100L, r.objectiveConstant)
        // LP objective (cost·x) is 0 at x0 = 0; the true bound is that plus the constant.
        assertEquals(0.0, sol.objectiveValue, eps)
        assertEquals(100.0, sol.objectiveValue + r.objectiveConstant, eps)
    }

    @Test
    fun `free reification does not over-constrain`() {
        // aux(b0) <-> (x0 >= 8), b0 free, min x0.  The relaxation must still allow x0 = 0 (b0 = 0),
        // i.e. the big-M indicator must not force x0 >= 8 when the aux is unfixed.
        val p = Problem(
            numBoolVars = 1,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 10)),
            factors = arrayOf<Factor>(
                ReifiedLinear(
                    auxBoolVar = 0,
                    coeffs = intArrayOf(1),
                    vars = intArrayOf(0),
                    op = LinearOp.GE,
                    bound = 8,
                ),
            ),
        )
        val (sol, r) = solve(p, LinearObjective(intCoefficients = longArrayOf(1L)))

        assertEquals(LpStatus.OPTIMAL, sol.status)
        assertEquals(0.0, sol.primal(intCol(r, 0)), eps)
    }

    @Test
    fun `pinned reification enforces its linear constraint`() {
        // Unit clause pins b0 = true; aux(b0) <-> (x0 >= 8) then forces x0 >= 8. min x0 -> 8.
        val p = Problem(
            numBoolVars = 1,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 10)),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                ReifiedLinear(
                    auxBoolVar = 0,
                    coeffs = intArrayOf(1),
                    vars = intArrayOf(0),
                    op = LinearOp.GE,
                    bound = 8,
                ),
            ),
        )
        val (sol, r) = solve(p, LinearObjective(intCoefficients = longArrayOf(1L)))

        assertEquals(LpStatus.OPTIMAL, sol.status)
        assertEquals(8.0, sol.primal(intCol(r, 0)), eps)
    }

    @Test
    fun `cardinality and clause rows constrain bool fan-in`() {
        // ExactlyTwo over 3 bools, each with objective weight 1 -> the LP objective is >= 2.
        val p = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = arrayOf(),
            factors = arrayOf<Factor>(
                Cardinality(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)), min = 2, max = 2),
            ),
        )
        val (sol, _) = solve(p, LinearObjective(boolWeights = longArrayOf(1L, 1L, 1L)))

        assertEquals(LpStatus.OPTIMAL, sol.status)
        assertEquals(2.0, sol.objectiveValue, eps)
    }

    @Test
    fun `pseudo boolean upper bound row`() {
        // 2·b0 + 3·b1 <= 4, maximize b0 + b1 (encoded as min -(b0+b1)).  Cheapest unit is b0, so the
        // LP picks b0 = 1, b1 = 2/3 -> obj 5/3.
        val p = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = arrayOf(),
            factors = arrayOf<Factor>(
                PseudoBoolean(intArrayOf(2, 3), intArrayOf(Lit.make(0, true), Lit.make(1, true)), PbOp.LE, 4),
            ),
        )
        val (sol, _) = solve(p, LinearObjective(boolWeights = longArrayOf(-1L, -1L)))

        assertEquals(LpStatus.OPTIMAL, sol.status)
        // min -(b0+b1) = -5/3  ->  max b0+b1 = 5/3.
        assertEquals(-5.0 / 3.0, sol.objectiveValue, eps)
    }

    @Test
    fun `column metadata maps back to cp variables`() {
        val p = Problem(
            numBoolVars = 1,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 10)),
            factors = arrayOf<Factor>(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 7),
                Clause(intArrayOf(Lit.make(0, true))),
            ),
        )
        val (_, r) = solve(p, null)

        val ci = intCol(r, 0)
        assertTrue(ci >= 0, "int var 0 has a column")
        assertEquals(0, r.colVarId[ci])
        // The bool column exists and is tagged as bool with var id 0.
        var boolCol = -1
        for (c in r.colVarId.indices) if (r.colIsBool[c] && r.colVarId[c] == 0) boolCol = c
        assertTrue(boolCol >= 0, "bool var 0 has a column")
    }

    @Test
    fun `unrecognized factors are skipped soundly`() {
        // No LP-emittable factor and no objective: a trivially feasible, unconstrained relaxation.
        val p = Problem(0, 1, arrayOf(IntDomain(0, 10)), arrayOf<Factor>())
        val (sol, _) = solve(p, null)
        assertEquals(LpStatus.OPTIMAL, sol.status)
    }
}
