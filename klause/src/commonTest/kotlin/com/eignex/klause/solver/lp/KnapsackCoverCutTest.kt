package com.eignex.klause.solver.lp

import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.PseudoBoolean
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #286: knapsack cover cuts for `Σ w_i·x_i ≤ b` PseudoBoolean rows. */
class KnapsackCoverCutTest {

    private fun posLits(n: Int) = IntArray(n) { Lit.make(it, true) }

    private fun coverVarsOf(r: LpRelaxation, cut: Cut): Set<Int> = cut.cols.map { r.colVarId[it] }.toSet()

    @Test
    fun `separates a violated cover and the cut is valid`() {
        // 3 items, weights [3,3,2], capacity 4. Maximising x0+x1 drives the LP to x0=x1=2/3. The cover
        // {0,1} weighs 6 > 4, and x2 (weight 2 > b - w_x0 = 1) up-lifts with coefficient 1, so the
        // sequential-lifted cut is x0 + x1 + x2 <= 1, violated at 4/3 (#552).
        val p = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(PseudoBoolean(intArrayOf(3, 3, 2), posLits(3), PbOp.LE, 4)),
        )
        val r = CpToLpRelaxation(p, LinearObjective(boolWeights = longArrayOf(-1, -1, 0))).build(PropagationSession(p))
        val sol = requireNotNull(RevisedSimplex(r.model).solve())
        val cuts = KnapsackCoverSeparator().separate(CutContext(p, r, sol.primal, PropagationSession(p)))

        assertTrue(cuts.isNotEmpty(), "a violated cover should be separated")
        val cut = cuts.first()
        assertEquals(Relation.LE, cut.rel)
        assertEquals(setOf(0, 1, 2), coverVarsOf(r, cut), "x2 is up-lifted into the cut")
        assertEquals(1L, cut.rhs, "rhs stays |C| - 1 = 1")
    }

    @Test
    fun `extended cover folds in a heavy non-cover item and separates a point the bare cover misses`() {
        // 3 items, weights [3,3,3], capacity 4: at most one item fits. Maximising x0+x1+x2 drives the LP
        // to 4/9 each. The bare cover {0,1} (3+3>4) has Σx* = 8/9 < 1 — NOT violated — but extending it
        // with item 2 (weight 3 >= the cover max 3) gives x0+x1+x2 <= 1, violated at 4/3 (#552).
        val p = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(PseudoBoolean(intArrayOf(3, 3, 3), posLits(3), PbOp.LE, 4)),
        )
        val r = CpToLpRelaxation(p, LinearObjective(boolWeights = longArrayOf(-1, -1, -1))).build(PropagationSession(p))
        val sol = requireNotNull(RevisedSimplex(r.model).solve())
        val cuts = KnapsackCoverSeparator().separate(CutContext(p, r, sol.primal, PropagationSession(p)))
        assertTrue(cuts.isNotEmpty(), "the extended cover should separate the all-4/9 point")
        val cut = cuts.first()
        assertEquals(setOf(0, 1, 2), coverVarsOf(r, cut), "all three items are in the extended cover")
        assertEquals(1L, cut.rhs, "rhs stays |C| - 1 = 1")
    }

    @Test
    fun `cover cuts exclude no knapsack-feasible point`() {
        val rng = Random(20260610)
        var separated = 0
        repeat(1500) {
            val n = rng.nextInt(2, 6)
            val weights = IntArray(n) { rng.nextInt(1, 6) }
            val bnd = rng.nextInt(1, weights.sum())
            val p = Problem(
                numBoolVars = n,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = arrayOf<Factor>(PseudoBoolean(weights, posLits(n), PbOp.LE, bnd)),
            )
            val obj = LinearObjective(boolWeights = LongArray(n) { -rng.nextInt(0, 4).toLong() })
            val r = CpToLpRelaxation(p, obj).build(PropagationSession(p))
            val sol = RevisedSimplex(r.model).solve() ?: return@repeat
            val cuts = KnapsackCoverSeparator().separate(CutContext(p, r, sol.primal, PropagationSession(p)))
            if (cuts.isEmpty()) return@repeat
            separated++
            for (cut in cuts) {
                // Lifting yields non-unit coefficients, so check the weighted left-hand side.
                val coeffByVar = HashMap<Int, Long>()
                for (t in cut.cols.indices) coeffByVar[r.colVarId[cut.cols[t]]] = cut.coeffs[t]
                // Every 0/1 assignment that satisfies the knapsack row must satisfy the cut.
                for (mask in 0 until (1 shl n)) {
                    var w = 0
                    var lhs = 0L
                    for (v in 0 until n) {
                        if (mask and (1 shl v) != 0) {
                            w += weights[v]
                            lhs += coeffByVar[v] ?: 0L
                        }
                    }
                    if (w <= bnd) {
                        assertTrue(
                            lhs <= cut.rhs,
                            "lifted cover cut excludes feasible mask $mask (lhs=$lhs rhs=${cut.rhs})",
                        )
                    }
                }
            }
        }
        assertTrue(separated > 50, "only $separated instances produced a cover cut")
    }

    @Test
    fun `GUB-lifted cover cuts exclude no feasible point of the knapsack-plus-cliques system`() {
        // Random knapsacks paired with an at-most-one clique over the first few vars. With cliques the
        // lift is GUB-constrained, so the cut is valid against the clique+knapsack feasible set (the
        // clique rows are in the relaxation) — checked over exactly those points (#552).
        val rng = Random(20260614)
        var gubSeen = 0
        repeat(1500) {
            val n = rng.nextInt(3, 6)
            val weights = IntArray(n) { rng.nextInt(1, 6) }
            val bnd = rng.nextInt(2, weights.sum())
            val cliqueSize = rng.nextInt(2, n + 1)
            val cliqueVars = (0 until cliqueSize).toSet()
            val factors = arrayOf<Factor>(
                PseudoBoolean(weights, posLits(n), PbOp.LE, bnd),
                Cardinality(IntArray(cliqueSize) { i -> Lit.make(i, true) }, min = 0, max = 1),
            )
            val p = Problem(numBoolVars = n, numIntVars = 0, intDomains = emptyArray(), factors = factors)
            val obj = LinearObjective(boolWeights = LongArray(n) { -rng.nextInt(0, 4).toLong() })
            val r = CpToLpRelaxation(p, obj).build(PropagationSession(p))
            val sol = RevisedSimplex(r.model).solve() ?: return@repeat
            val cuts = KnapsackCoverSeparator().separate(CutContext(p, r, sol.primal, PropagationSession(p)))
            if (cuts.isEmpty()) return@repeat
            gubSeen++
            for (cut in cuts) {
                val coeffByVar = HashMap<Int, Long>()
                for (t in cut.cols.indices) coeffByVar[r.colVarId[cut.cols[t]]] = cut.coeffs[t]
                for (mask in 0 until (1 shl n)) {
                    var w = 0
                    var cliqueCount = 0
                    var lhs = 0L
                    for (v in 0 until n) {
                        if (mask and (1 shl v) != 0) {
                            w += weights[v]
                            if (v in cliqueVars) cliqueCount++
                            lhs += coeffByVar[v] ?: 0L
                        }
                    }
                    // Feasible for the system = within the knapsack bound AND respecting the AMO clique.
                    if (w <= bnd && cliqueCount <= 1) {
                        assertTrue(
                            lhs <= cut.rhs,
                            "GUB cover cut excludes feasible mask $mask (lhs=$lhs rhs=${cut.rhs})",
                        )
                    }
                }
            }
        }
        assertTrue(gubSeen > 50, "only $gubSeen clique instances produced a cover cut")
    }
}
