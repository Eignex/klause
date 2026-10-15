package com.eignex.klause.lp.cut

import com.eignex.klause.factor.circuit.Circuit
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun `repeated separation should keep cuts independent across changing support`() {
        val (p, obj) = circuitProblem()
        val session = PropagationSession(p)
        val r = relaxer(p, obj).build(session)
        val arcs = r.circuitArcs.single()
        val subtour = DoubleArray(r.model.n)
        val tour = DoubleArray(r.model.n)
        val dense = DoubleArray(r.model.n)
        val successors = intArrayOf(1, 0, 3, 2)
        for (k in arcs.cols.indices) {
            val col = arcs.cols[k]
            if (arcs.heads[k] == successors[arcs.tails[k]]) subtour[col] = 1.0
            if (arcs.heads[k] == (arcs.tails[k] + 1) % arcs.n) tour[col] = 1.0
            dense[col] = 1.0 / (arcs.n - 1)
        }
        val separator = CircuitSeparator()
        val retained = separator.separate(CutContext(p, r, subtour, session))
        val expected = retained.map { it.key() }
        assertTrue(expected.isNotEmpty())

        for (point in listOf(tour, dense, DoubleArray(r.model.n), tour)) {
            assertTrue(separator.separate(CutContext(p, r, point, session)).isEmpty())
            val cuts = separator.separate(CutContext(p, r, subtour, session))
            assertEquals(expected, cuts.map { it.key() })
        }

        assertEquals(expected, retained.map { it.key() })
    }

    @Test
    fun `subtour optimum of the arc relaxation should get cut`() {
        val (p, obj) = circuitProblem()
        val session = PropagationSession(p)
        val r = relaxer(p, obj).build(session)
        val sol = requireNotNull(RevisedSimplex(r.model).solve())

        assertEquals(10.0, sol.objective, eps)
        assertEquals(1.0, sol.primalAt(intCol(r, 0)), eps)
        assertEquals(0.0, sol.primalAt(intCol(r, 1)), eps)
        assertEquals(3.0, sol.primalAt(intCol(r, 2)), eps)
        assertEquals(2.0, sol.primalAt(intCol(r, 3)), eps)

        val cuts = CircuitSeparator().separate(CutContext(p, r, sol.primal, session))
        assertTrue(cuts.isNotEmpty())
        assertTrue(cuts.all { it.rel == Relation.GE && it.rhs == 1L })
        val lhs = cuts.first().cols.sumOf { sol.primalAt(it) }
        assertTrue(lhs < 1.0 - eps, "cut LHS $lhs should be < 1 at the subtour point")
    }

    @Test
    fun `subtour cut should exclude no Hamiltonian tour`() {
        val (p, obj) = circuitProblem()
        val session = PropagationSession(p)
        val r = relaxer(p, obj).build(session)
        val sol = requireNotNull(RevisedSimplex(r.model).solve())
        val model = r.circuitArcs.single()
        val cut = CircuitSeparator().separate(CutContext(p, r, sol.primal, session)).first { it.rel == Relation.GE }

        val arcOf = HashMap<Int, Pair<Int, Int>>()
        for (k in model.cols.indices) arcOf[model.cols[k]] = model.tails[k] to model.heads[k]
        val cutArcs = cut.cols.map { arcOf.getValue(it) }

        for (succ in singleCyclePermutations(4)) {
            val crossings = cutArcs.count { (i, j) -> succ[i] == j }
            assertTrue(crossings >= cut.rhs, "tour ${succ.toList()} violates the subtour cut")
        }
    }

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
                if (used[v] || v == pos) continue
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
