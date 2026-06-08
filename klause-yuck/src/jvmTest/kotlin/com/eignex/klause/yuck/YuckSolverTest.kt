package com.eignex.klause.yuck

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.AllEqual
import com.eignex.klause.solver.factor.Among
import com.eignex.klause.solver.factor.ArrayMinMax
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Circuit
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Count
import com.eignex.klause.solver.factor.Element
import com.eignex.klause.solver.factor.Inverse
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.Monotone
import com.eignex.klause.solver.factor.Table
import com.eignex.klause.solver.factor.Xor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end tests against a real Yuck subprocess (provisioned by the `installYuck` Gradle
 * task). Each Yuck call pays a JVM spawn, so semantic factor coverage is batched into one
 * many-group problem rather than one run per factor.
 */
class YuckSolverTest {

    private val params = YuckParams(timeoutMillis = 10_000)

    @Test
    fun `solves a satisfiable clause problem`() {
        val p = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            ),
        )
        val r = YuckSolver(p).solve(params)
        assertTrue(r is SolveResult.Sat, "expected SAT, got $r")
        assertTrue(r.assignment.bools[0] || r.assignment.bools[1])
    }

    @Test
    fun `reports trivial unsat`() {
        val p = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
        )
        val r = YuckSolver(p).solve(params)
        assertTrue(r is SolveResult.Unsat, "expected UNSAT, got $r")
    }

    @Test
    fun `solves a permutation`() {
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            factors = arrayOf<Factor>(AllDifferent(vars = intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3)),
        )
        val r = YuckSolver(p).solve(params)
        assertTrue(r is SolveResult.Sat, "expected SAT, got $r")
        assertEquals(setOf(0, 1, 2), r.assignment.ints.toSet())
    }

    @Test
    fun `minimizes a linear objective`() {
        // minimize x with x in [2,9]; optimum is 2 (Yuck reports BestFound — LS proves nothing).
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 9)),
            factors = arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 2)),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L))
        val r = YuckSolver(p).minimize(obj, YuckParams(timeoutMillis = 5_000))
        assertTrue(r is MinimizeResult.WithSample, "expected a solution, got $r")
        assertEquals(2.0, r.objective)
        assertEquals(2, r.sample.ints[0])
    }

    @Test
    fun `improvements stream ends with the terminal result`() {
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 9), IntDomain(0, 9)),
            factors = arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 6)),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L, 1L))
        val rs = YuckSolver(p).improvements(obj, YuckParams(timeoutMillis = 5_000)).toList()
        assertTrue(rs.isNotEmpty())
        val objectives = rs.mapNotNull { it.objectiveValue }
        assertEquals(objectives.sorted().reversed(), objectives, "incumbents must improve monotonically")
        assertEquals(6.0, objectives.last())
    }

    @Test
    fun `factor semantics hold across one batched run`() {
        // Independent factor groups in a single Yuck run (each subprocess pays a JVM spawn).
        // Group vars:           ids        semantics
        //   AllEqual            0..2       all equal, pinned to 1 via i0 = 1
        //   Monotone strict     3..5       0 < 1 < 2 over domain 0..2 ⇒ exactly (0,1,2)
        //   Element (const)     6,7        i7 = [5,7,9][i6], i6 pinned to 2 ⇒ i7 = 9
        //   Among               8..10      i10 = #{i8, i9} ∈ {1,2}
        //   Table               11,12      (i11,i12) ∈ {(0,1),(2,2)}
        //   Circuit             13..15     one 3-cycle, no self-loops
        //   Inverse             16..19     f = (i16,i17), g = (i18,i19) channel each other
        //   Count(Ge)           20..22     i22 = #{i20, i21 ≥ 2}
        //   ArrayMinMax(max)    23..25     i25 = max(i23, i24)
        // Bool groups:
        //   Cardinality         b0..b2     exactly one true
        //   Xor                 b3,b4      odd parity
        val doms = ArrayList<IntDomain>()
        repeat(6) { doms.add(IntDomain(0, 2)) } // 0..5
        doms.add(IntDomain(0, 2)) // 6
        doms.add(IntDomain(0, 20)) // 7
        repeat(2) { doms.add(IntDomain(0, 3)) } // 8, 9
        doms.add(IntDomain(0, 2)) // 10
        repeat(2) { doms.add(IntDomain(0, 2)) } // 11, 12
        repeat(3) { doms.add(IntDomain(0, 2)) } // 13..15
        repeat(4) { doms.add(IntDomain(0, 1)) } // 16..19
        repeat(2) { doms.add(IntDomain(0, 3)) } // 20, 21
        doms.add(IntDomain(0, 2)) // 22
        repeat(3) { doms.add(IntDomain(0, 5)) } // 23..25
        val p = Problem(
            numBoolVars = 5,
            numIntVars = doms.size,
            intDomains = doms.toTypedArray(),
            factors = arrayOf<Factor>(
                AllEqual(intArrayOf(0, 1, 2)),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                Monotone(intArrayOf(3, 4, 5), Monotone.Direction.Increasing, strict = true),
                Element(idx = 6, result = 7, arr = intArrayOf(5, 7, 9), arrIsVars = false, indexOffset = 0),
                Linear(intArrayOf(1), intArrayOf(6), LinearOp.EQ, 2),
                Among(n = 10, xs = intArrayOf(8, 9), values = intArrayOf(1, 2)),
                Table(intArrayOf(11, 12), intArrayOf(0, 1, 2, 2)),
                Circuit(intArrayOf(13, 14, 15)),
                Inverse(intArrayOf(16, 17), intArrayOf(18, 19)),
                Count(xs = intArrayOf(20, 21), v = 2, op = Count.Op.Ge, n = 22),
                ArrayMinMax(result = 25, xs = intArrayOf(23, 24), max = true),
                Cardinality(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)), 1, 1),
                Xor(intArrayOf(Lit.make(3, true), Lit.make(4, true)), 1),
            ),
        )
        val r = YuckSolver(p).solve(params)
        assertTrue(r is SolveResult.Sat, "expected SAT, got $r")
        val a = r.assignment
        assertEquals(listOf(1, 1, 1), a.ints.slice(0..2))
        assertEquals(listOf(0, 1, 2), a.ints.slice(3..5))
        assertEquals(2, a.ints[6])
        assertEquals(9, a.ints[7])
        assertEquals(listOf(a.ints[8], a.ints[9]).count { it in 1..2 }, a.ints[10])
        assertTrue(
            (a.ints[11] == 0 && a.ints[12] == 1) || (a.ints[11] == 2 && a.ints[12] == 2),
            "table violated: ${a.ints[11]}, ${a.ints[12]}",
        )
        run {
            val succ = a.ints.slice(13..15)
            var node = 0
            val seen = HashSet<Int>()
            repeat(3) {
                assertTrue(seen.add(node), "subtour in $succ")
                node = succ[node]
            }
            assertEquals(0, node, "not a circuit: $succ")
        }
        run {
            val f = a.ints.slice(16..17)
            val g = a.ints.slice(18..19)
            for (i in f.indices) assertEquals(i, g[f[i]], "inverse violated: f=$f g=$g")
        }
        assertEquals(listOf(a.ints[20], a.ints[21]).count { it >= 2 }, a.ints[22])
        assertEquals(maxOf(a.ints[23], a.ints[24]), a.ints[25])
        assertEquals(1, a.bools.slice(0..2).count { it })
        assertTrue(a.bools[3] xor a.bools[4])
    }
}
