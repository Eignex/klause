package com.eignex.klause.solver.lp

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Circuit
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #22 genuine subtour elimination for Circuit: the arc-indicator relaxation plus the max-flow
 * [CircuitSeparator]. The degree rows make the LP optimum a permutation; when that permutation is a
 * union of subtours, the separator must find a directed cutset inequality it violates, and that cut
 * must exclude no real Hamiltonian tour.
 */
class CircuitCutTest {

    private val eps = 1e-7

    // succ cost vector c1 > c0 > c3 > c2, so the min-cost assignment is succ = [1,0,3,2] — the two
    // 2-cycles {0↔1} and {2↔3}, i.e. a subtour, with cost 3·1 + 4·0 + 1·3 + 2·2 = 10.
    private fun circuitProblem(): Pair<Problem, LinearObjective> {
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 3) },
            factors = arrayOf<Factor>(Circuit(intArrayOf(0, 1, 2, 3))),
        )
        return p to LinearObjective(intCoefficients = longArrayOf(3L, 4L, 1L, 2L))
    }

    private fun relaxer(p: Problem, obj: LinearObjective) =
        CpToLpRelaxation(p, obj, generateCuts = false, circuitArcs = true)

    private fun intCol(r: LpRelaxation, v: Int): Int {
        for (c in r.colVarId.indices) if (!r.colIsBool[c] && r.colVarId[c] == v) return c
        return -1
    }

    @Test
    fun `arc relaxation optimum is a subtour and gets cut`() {
        val (p, obj) = circuitProblem()
        val session = PropagationSession(p)
        val r = relaxer(p, obj).build(session)
        val sol = DualSimplex(r.model).solve()

        assertEquals(LpStatus.OPTIMAL, sol.status)
        assertEquals(10.0, sol.objectiveValue, eps) // the subtour assignment cost
        assertEquals(1.0, sol.primal(intCol(r, 0)), eps)
        assertEquals(0.0, sol.primal(intCol(r, 1)), eps)
        assertEquals(3.0, sol.primal(intCol(r, 2)), eps)
        assertEquals(2.0, sol.primal(intCol(r, 3)), eps)

        val cuts = CircuitSeparator().separate(CutContext(p, r, sol, session))
        assertTrue(cuts.isNotEmpty(), "a subtour LP point must yield a cut")
        assertTrue(cuts.all { it.rel == Relation.GE && it.rhs == 1L })
        // The first cut is violated at the current point: its arcs carry no flow out of the subtour.
        val lhs = cuts.first().cols.sumOf { sol.primal(it) }
        assertTrue(lhs < 1.0 - eps, "cut LHS $lhs should be < 1 at the subtour point")
    }

    @Test
    fun `subtour cut excludes no Hamiltonian tour`() {
        val (p, obj) = circuitProblem()
        val session = PropagationSession(p)
        val r = relaxer(p, obj).build(session)
        val sol = DualSimplex(r.model).solve()
        val model = r.circuitArcs.single()
        val cut = CircuitSeparator().separate(CutContext(p, r, sol, session)).first { it.rel == Relation.GE }

        // Invert arcCol so a cut column maps back to its (i, j) arc.
        val arcOf = HashMap<Int, Pair<Int, Int>>()
        for (i in 0 until model.n) {
            for (j in 0 until model.n) {
                if (model.arcCol[i][j] >= 0) arcOf[model.arcCol[i][j]] = i to j
            }
        }
        val cutArcs = cut.cols.map { arcOf.getValue(it) }

        // Every single 4-cycle (Hamiltonian tour) must use at least one cut arc.
        for (succ in singleCyclePermutations(4)) {
            val crossings = cutArcs.count { (i, j) -> succ[i] == j }
            assertTrue(crossings >= cut.rhs, "tour ${succ.toList()} violates the subtour cut")
        }
    }

    @Test
    fun `re-solving with the cut raises the bound above the subtour`() {
        val (p, obj) = circuitProblem()
        val session = PropagationSession(p)
        val relaxer = relaxer(p, obj)
        val r = relaxer.build(session)
        val sol = DualSimplex(r.model).solve()
        val cuts = CircuitSeparator().separate(CutContext(p, r, sol, session))

        val r2 = relaxer.build(session, cuts)
        val sol2 = DualSimplex(r2.model).solve()
        assertEquals(LpStatus.OPTIMAL, sol2.status)
        assertTrue(sol2.objectiveValue > 10.0 + eps, "subtour-eliminated bound ${sol2.objectiveValue} should exceed 10")
    }

    /** All permutations of `[0,n)` that form a single n-cycle, as successor arrays. */
    private fun singleCyclePermutations(n: Int): List<IntArray> {
        val out = ArrayList<IntArray>()
        val perm = IntArray(n)
        val used = BooleanArray(n)
        fun rec(pos: Int) {
            if (pos == n) {
                if (isSingleCycle(perm)) out.add(perm.copyOf())
                return
            }
            for (v in 0 until n) {
                if (used[v] || v == pos) continue // no self-loops in a circuit
                used[v] = true
                perm[pos] = v
                rec(pos + 1)
                used[v] = false
            }
        }
        rec(0)
        return out
    }

    private fun isSingleCycle(succ: IntArray): Boolean {
        var node = 0
        var steps = 0
        do {
            node = succ[node]
            steps++
        } while (node != 0 && steps <= succ.size)
        return steps == succ.size
    }
}
