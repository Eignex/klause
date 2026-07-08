package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.table.Mdd
import com.eignex.klause.lp.LpStatus
import com.eignex.klause.lp.solveLp
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.IntArrayList
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #655 (Tranche A): the layered flow hull of [Mdd]. The flow polytope is integral, so the LP optimum
 * of a linear objective over the sequence (or the cost-MDD's cost var) equals the true optimum over
 * the diagram's accepting paths — checked against brute force.
 */
class CpToLpRelaxationMddFlowHullTest {

    private val eps = 1e-9

    @Test
    fun `cost-MDD flow hull gives the exact minimum path cost`() {
        // 2 layers, single state each; value 1 costs 5, value 2 costs 1. Minimum-cost accepted path
        // takes value 2 twice -> cost 2. seq = vars 0,1; cost = var 2.
        val transitions = longArrayOf(
            0, 1, 0, 5, 0, 2, 0, 1, // layer 0 records (src,value,dst,weight)
            0, 1, 0, 5, 0, 2, 0, 1, // layer 1
        )
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(1, 2), IntDomain(1, 2), IntDomain(0, 100)),
            factors = arrayOf<Factor>(
                Mdd(
                    seq = intArrayOf(0, 1),
                    numStatesPerLayer = intArrayOf(1, 1, 1),
                    layerStarts = intArrayOf(0, 8, 16),
                    transitions = transitions,
                    initial = 0,
                    accepting = intArrayOf(0),
                    recordStride = 4,
                    cost = 2,
                ),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(0, 0, 1)) // minimize the cost var
        val r = CpToLpRelaxation(p, obj, mddHull = true).build(PropagationSession(p))
        val sol = solveLp(r.model)
        assertEquals(LpStatus.OPTIMAL, sol.status)
        assertEquals(2.0, sol.objectiveValue, eps, "minimum accepted-path cost is 2")
    }

    @Test
    fun `cost-MDD flow hull is exact with symbols and weights beyond Int range`() {
        // Symbols b+1/b+2 and weights past 2^31: value b+1 costs 5e9, b+2 costs 1e9. The cost channel
        // carries weights as Long and the arc presence carries the full Long symbol, so the minimum
        // accepted-path cost (b+2 twice -> 2e9) is exact.
        val b = 4_000_000_000L
        val transitions = longArrayOf(
            0, b + 1, 0, 5_000_000_000L, 0, b + 2, 0, 1_000_000_000L, // layer 0 (src,value,dst,weight)
            0, b + 1, 0, 5_000_000_000L, 0, b + 2, 0, 1_000_000_000L, // layer 1
        )
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(b + 1, b + 2), IntDomain(b + 1, b + 2), IntDomain(0, 20_000_000_000L)),
            factors = arrayOf<Factor>(
                Mdd(
                    seq = intArrayOf(0, 1),
                    numStatesPerLayer = intArrayOf(1, 1, 1),
                    layerStarts = intArrayOf(0, 8, 16),
                    transitions = transitions,
                    initial = 0,
                    accepting = intArrayOf(0),
                    recordStride = 4,
                    cost = 2,
                ),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(0, 0, 1)) // minimize the cost var
        val r = CpToLpRelaxation(p, obj, mddHull = true).build(PropagationSession(p))
        val sol = solveLp(r.model)
        assertEquals(LpStatus.OPTIMAL, sol.status)
        assertEquals(2_000_000_000.0, sol.objectiveValue, 1.0, "minimum accepted-path cost is 2e9")
    }

    @Test
    fun `randomized plain MDD flow hull matches the brute-force optimum`() {
        val rng = Random(20260616)
        var checked = 0
        repeat(300) { _ ->
            val n = rng.nextInt(2, 5)
            val alphabet = rng.nextInt(2, 4)
            val spl = IntArray(n + 1) { if (it == 0) 1 else rng.nextInt(1, 4) }
            // Deterministic layered MDD: at most one record per (layer, src, value).
            val trans = IntArrayList()
            val starts = IntArray(n + 1)
            val lookup = HashMap<Int, Int>() // key = ((layer*8 + src)*8 + value) -> dst
            for (layer in 0 until n) {
                starts[layer] = trans.size
                for (srcState in 0 until spl[layer]) {
                    for (value in 1..alphabet) {
                        if (rng.nextInt(10) < 6) {
                            val dst = rng.nextInt(0, spl[layer + 1])
                            trans.add(srcState)
                            trans.add(value)
                            trans.add(dst)
                            lookup[(layer * 8 + srcState) * 8 + value] = dst
                        }
                    }
                }
            }
            starts[n] = trans.size
            val acc = HashSet<Int>()
            for (st in 0 until spl[n]) if (rng.nextInt(2) == 0) acc.add(st)
            if (acc.isEmpty()) acc.add(rng.nextInt(0, spl[n]))
            val c = LongArray(n) { rng.nextInt(-3, 4).toLong() }
            val p = Problem(
                numBoolVars = 0,
                numIntVars = n,
                intDomains = Array(n) { IntDomain(1, alphabet.toLong()) },
                factors = arrayOf<Factor>(
                    Mdd(
                        IntArray(n) { it },
                        spl,
                        starts,
                        trans.toIntArray().let { a -> LongArray(a.size) { a[it].toLong() } },
                        0,
                        acc.toIntArray(),
                        3,
                        -1,
                    ),
                ),
            )
            val obj = LinearObjective(intCoefficients = c)

            // Brute force: minimum objective over accepted strings (deterministic path follow).
            var brute: Long? = null
            val x = IntArray(n)
            fun accepts(): Boolean {
                var state = 0
                for (layer in 0 until n) {
                    val dst = lookup[(layer * 8 + state) * 8 + x[layer]] ?: return false
                    state = dst
                }
                return state in acc
            }
            fun rec(i: Int) {
                if (i == n) {
                    if (!accepts()) return
                    var o = 0L
                    for (k in 0 until n) o += c[k] * x[k]
                    if (brute == null || o < brute!!) brute = o
                    return
                }
                for (v in 1..alphabet) {
                    x[i] = v
                    rec(i + 1)
                }
            }
            rec(0)

            val r = CpToLpRelaxation(p, obj, mddHull = true).build(PropagationSession(p))
            val sol = solveLp(r.model)
            val opt = brute ?: return@repeat // no accepting string: the hull soundly adds no rows
            checked++
            assertEquals(LpStatus.OPTIMAL, sol.status, "accepted string exists but LP not optimal")
            assertEquals(
                opt.toDouble(),
                sol.objectiveValue,
                eps,
                "MDD flow hull optimum ${sol.objectiveValue} != brute $opt",
            )
        }
        assertTrue(checked > 100, "only $checked feasible instances checked")
    }
}
