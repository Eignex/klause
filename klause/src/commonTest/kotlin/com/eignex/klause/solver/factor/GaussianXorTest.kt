package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.selector.Vsids
import kotlin.test.Test
import kotlin.test.assertEquals

class GaussianXorTest {

    /**
     * Soundness gate for the reused Gauss-Jordan scratch in [GaussianXor.propagate]. Enumerating the
     * full solution set under the CDCL backtracker fires `propagate` repeatedly on one
     * `PropagationState` across push/pop — refilling and reducing the same `mask` / `reason` / `rhs`
     * buffers every fire. A faulty reset (stale row, reason bit, or right-hand side) would change the
     * forced pins / conflicts and drop or admit an assignment, so the enumerated set must equal the
     * brute-force set of assignments satisfying every XOR parity.
     */
    @Test
    fun `backtrack enumeration over a gaussian xor system equals brute force`() {
        val n = 5
        fun pos(v: Int) = Lit.make(v, true)
        // XOR system (all literals positive): x0^x1^x2=1, x1^x3=0, x2^x3^x4=1, x0^x4=0.
        val xors = listOf(
            intArrayOf(pos(0), pos(1), pos(2)) to 1,
            intArrayOf(pos(1), pos(3)) to 0,
            intArrayOf(pos(2), pos(3), pos(4)) to 1,
            intArrayOf(pos(0), pos(4)) to 0,
        )
        val brute = HashSet<List<Boolean>>()
        for (m in 0 until (1 shl n)) {
            val b = BooleanArray(n) { (m ushr it) and 1 == 1 }
            val ok = xors.all { (lits, parity) ->
                var p = 0
                for (lit in lits) if (b[Lit.variable(lit)]) p = p xor 1
                p == parity
            }
            if (ok) brute.add(b.toList())
        }

        val problem = Problem(
            numBoolVars = n,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(GaussianXor(xors.map { (lits, parity) -> Xor(lits, parity) })),
        )
        val params = BacktrackParams(randomSeed = 1L, variableSelector = Vsids(), maxLearnedClauses = 1_000)
        val found = BacktrackSolver(problem).enumerate(params).take(100_000)
            .map { it.bools.toList() }.toHashSet()
        assertEquals(brute, found, "GaussianXor enumeration must equal the brute-force XOR solution set")
    }
}
