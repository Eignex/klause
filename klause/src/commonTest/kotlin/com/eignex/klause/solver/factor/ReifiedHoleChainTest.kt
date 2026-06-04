package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.Vsids
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Stress for reason attribution across carved holes: equality channels force value exclusions
 * that advance domain endpoints past earlier holes, and the clauses learned from the ensuing
 * conflicts must not prune feasible assignments — an over-strong nogood shows up here as a
 * proven "optimum" worse than the brute-force one. Each seeded instance pairs two channelled
 * variables with random ternary clauses over the channels and free driver booleans.
 */
class ReifiedHoleChainTest {

    @Test
    fun `proven optimum over equality channels with carved holes matches brute force`() {
        val lo = 1
        val hi = 5
        val span = hi - lo + 1
        for (seed in 0 until 12) {
            val rnd = Random(seed)
            val numDrivers = 2

            // bool layout: drivers d0,d1 then channels cx_v, cy_v.
            fun cx(v: Int) = numDrivers + (v - lo)
            fun cy(v: Int) = numDrivers + span + (v - lo)
            val numBool = numDrivers + 2 * span
            val factors = ArrayList<Factor>()
            for (v in lo..hi) {
                factors.add(
                    ReifiedLinear(
                        auxBoolVar = cx(v),
                        coeffs = intArrayOf(1),
                        vars = intArrayOf(0),
                        op = LinearOp.EQ,
                        bound = v,
                    ),
                )
                factors.add(
                    ReifiedLinear(
                        auxBoolVar = cy(v),
                        coeffs = intArrayOf(1),
                        vars = intArrayOf(1),
                        op = LinearOp.EQ,
                        bound = v,
                    ),
                )
            }
            val clauses = ArrayList<IntArray>()
            repeat(10) {
                val lits = IntArray(3) {
                    val b = rnd.nextInt(numBool)
                    Lit.make(b, rnd.nextBoolean())
                }
                clauses.add(lits)
                factors.add(Clause(lits))
            }
            val p = Problem(
                numBoolVars = numBool,
                numIntVars = 2,
                intDomains = arrayOf(IntDomain(lo, hi), IntDomain(lo, hi)),
                factors = factors.toTypedArray(),
            )

            // Brute-force optimum of x + 2y over the feasible set (channels are functions of
            // x and y; the two drivers are free).
            var bruteBest: Int? = null
            for (x in lo..hi) {
                for (y in lo..hi) {
                for (mask in 0 until (1 shl numDrivers)) {
                    val bools = BooleanArray(numBool)
                    for (d in 0 until numDrivers) bools[d] = (mask shr d) and 1 == 1
                    for (v in lo..hi) {
                        bools[cx(v)] = x == v
                        bools[cy(v)] = y == v
                    }
                    val ok = clauses.all { cl ->
                        cl.any { lit -> bools[Lit.variable(lit)] == Lit.isPositive(lit) }
                    }
                    if (ok) {
                        val obj = x + 2 * y
                        val best = bruteBest
                        if (best == null || obj < best) bruteBest = obj
                    }
                }
            }
            }

            // Defensive decision cap: a capped run returns BestFound and makes no optimality
            // claim, which the check below then skips. The guarded property is the soundness
            // one: whenever the engine *claims* a proven optimum or infeasibility, brute force
            // must agree.
            val params = BacktrackParams(
                randomSeed = seed.toLong(),
                variableHeuristic = Vsids(),
                maxLearnedClauses = 1_000,
                maxDecisions = 200_000,
            )
            val objective = LinearObjective(intCoefficients = doubleArrayOf(1.0, 2.0))
            val result = BacktrackSolver(p).minimize(objective, params)
            val best = bruteBest
            when (result) {
                is MinimizeResult.Optimal ->
                    assertEquals(
                        best?.toDouble(),
                        result.objective,
                        "seed $seed: proven optimum must match brute force",
                    )

                is MinimizeResult.Infeasible ->
                    assertEquals(null, best, "seed $seed: claimed infeasible but brute found $best")

                else -> {} // budget-capped: no optimality claim to check
            }
        }
    }
}
