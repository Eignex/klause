package com.eignex.klause.lp.cut

import com.eignex.klause.factor.circuit.Circuit
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
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

    private fun relaxer(p: Problem, obj: LinearObjective) = CpToLpRelaxation(p, obj, circuitArcs = true)

    private fun intCol(r: LpRelaxation, v: Int): Int {
        for (c in r.colVarId.indices) if (!r.colIsBool[c] && r.colVarId[c] == v) return c
        return -1
    }

    @Test
    fun `arc relaxation optimum is a subtour and gets cut`() {
        val (p, obj) = circuitProblem()
        val session = PropagationSession(p)
        val r = relaxer(p, obj).build(session)
        val sol = requireNotNull(RevisedSimplex(r.model).solve())

        assertEquals(10.0, sol.objective, eps) // the subtour assignment cost
        assertEquals(1.0, sol.primalAt(intCol(r, 0)), eps)
        assertEquals(0.0, sol.primalAt(intCol(r, 1)), eps)
        assertEquals(3.0, sol.primalAt(intCol(r, 2)), eps)
        assertEquals(2.0, sol.primalAt(intCol(r, 3)), eps)

        val cuts = CircuitSeparator().separate(CutContext(p, r, sol.primal, session))
        assertTrue(cuts.isNotEmpty(), "a subtour LP point must yield a cut")
        assertTrue(cuts.all { it.rel == Relation.GE && it.rhs == 1L })
        // The first cut is violated at the current point: its arcs carry no flow out of the subtour.
        val lhs = cuts.first().cols.sumOf { sol.primalAt(it) }
        assertTrue(lhs < 1.0 - eps, "cut LHS $lhs should be < 1 at the subtour point")
    }

    @Test
    fun `subtour cut excludes no Hamiltonian tour`() {
        val (p, obj) = circuitProblem()
        val session = PropagationSession(p)
        val r = relaxer(p, obj).build(session)
        val sol = requireNotNull(RevisedSimplex(r.model).solve())
        val model = r.circuitArcs.single()
        val cut = CircuitSeparator().separate(CutContext(p, r, sol.primal, session)).first { it.rel == Relation.GE }

        // Map each arc column back to its (i, j) from the sparse arc lists.
        val arcOf = HashMap<Int, Pair<Int, Int>>()
        for (k in model.cols.indices) arcOf[model.cols[k]] = model.tails[k] to model.heads[k]
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
        val sol = requireNotNull(RevisedSimplex(r.model).solve())
        val cuts = CircuitSeparator().separate(CutContext(p, r, sol.primal, session))

        val r2 = relaxer.build(session, cuts)
        val sol2 = requireNotNull(RevisedSimplex(r2.model).solve())
        assertTrue(sol2.objective > 10.0 + eps, "subtour-eliminated bound ${sol2.objective} should exceed 10")
    }

    @Test
    fun `subtour cut at n=6 excludes no Hamiltonian tour`() {
        // Costs pair the largest c with the smallest successor value (rearrangement inequality), so
        // c = [5,4,6,2,1,3] forces the min-cost assignment succ = [1,2,0,4,5,3] — two 3-cycles
        // {0→1→2→0},{3→4→5→3}, a subtour that exercises the sparse max-flow separator beyond n=4.
        val n = 6
        val p = Problem(
            numBoolVars = 0,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(0, (n - 1).toLong()) },
            factors = arrayOf<Factor>(Circuit(IntArray(n) { it })),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(5L, 4L, 6L, 2L, 1L, 3L))
        val session = PropagationSession(p)
        val r = relaxer(p, obj).build(session)
        val model = r.circuitArcs.single()
        val sol = requireNotNull(RevisedSimplex(r.model).solve())
        val cut = CircuitSeparator().separate(CutContext(p, r, sol.primal, session)).first { it.rel == Relation.GE }
        val arcOf = HashMap<Int, Pair<Int, Int>>()
        for (k in model.cols.indices) arcOf[model.cols[k]] = model.tails[k] to model.heads[k]
        val cutArcs = cut.cols.map { arcOf.getValue(it) }
        for (succ in singleCyclePermutations(n)) {
            val crossings = cutArcs.count { (i, j) -> succ[i] == j }
            assertTrue(crossings >= cut.rhs, "tour ${succ.toList()} violates the subtour cut")
        }
    }

    @Test
    fun `arc relaxation is gated on candidate-arc count not node count`() {
        // n=30 (30·29 = 870 arcs ≤ MAX_CIRCUIT_ARCS) builds; n=40 (1560 arcs) exceeds the cap and is
        // skipped, so the gate works both ways (#431).
        fun fullCircuit(n: Int): LpRelaxation {
            val p = Problem(
                numBoolVars = 0,
                numIntVars = n,
                intDomains = Array(n) { IntDomain(0, (n - 1).toLong()) },
                factors = arrayOf<Factor>(Circuit(IntArray(n) { it })),
            )
            return CpToLpRelaxation(p, null, circuitArcs = true).build(PropagationSession(p))
        }
        val built = fullCircuit(30).circuitArcs.single()
        assertEquals(30, built.n)
        assertEquals(30 * 29, built.cols.size) // every i→j with j ≠ i is a candidate arc
        assertTrue(fullCircuit(40).circuitArcs.isEmpty(), "n=40 (1560 arcs) exceeds the arc cap, so skipped")
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

private fun FloatLpResult.primalAt(c: Int): Double = primal[c]
