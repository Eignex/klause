package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.ReifiedLinear
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Complete enumeration must terminate and report the brute-force feasible set exactly — every
 * solution once, no revisits. Equality channels plus random clauses drive conflicts whose
 * backjumps unwind the frames of already-yielded leaves; without a blocking nogood per yielded
 * solution the search re-finds the same leaves indefinitely.
 */
class EnumerateCompletenessTest {

    @Test
    fun `enumeration over equality channels matches the brute-force set exactly once`() {
        val lo = 1
        val hi = 5
        val span = hi - lo + 1
        for (seed in 0 until 12) {
            val rnd = Random(seed)
            val numDrivers = 2
            fun cx(v: Int) = numDrivers + (v - lo)
            fun cy(v: Int) = numDrivers + span + (v - lo)
            val numBool = numDrivers + 2 * span
            val factors = ArrayList<Factor>()
            for (v in lo..hi) {
                factors.add(ReifiedLinear(auxBoolVar = cx(v), coeffs = intArrayOf(1), vars = intArrayOf(0), op = LinearOp.EQ, bound = v))
                factors.add(ReifiedLinear(auxBoolVar = cy(v), coeffs = intArrayOf(1), vars = intArrayOf(1), op = LinearOp.EQ, bound = v))
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

            val brute = HashSet<List<Int>>()
            for (x in lo..hi) for (y in lo..hi) {
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
                    if (ok) brute.add(bools.map { if (it) 1 else 0 } + listOf(x, y))
                }
            }

            val params = BacktrackParams(randomSeed = seed.toLong(), variableHeuristic = Vsids(), maxLearnedClauses = 1_000)
            val raw = BacktrackSolver(p).enumerate(params).take(brute.size + 10)
                .map { s -> s.bools.map { if (it) 1 else 0 } + s.ints.toList() }.toList()
            assertEquals(raw.size, raw.toHashSet().size, "seed $seed: a solution was yielded more than once")
            assertEquals(brute, raw.toHashSet(), "seed $seed: enumeration must equal the brute-force feasible set")
        }
    }
}
