package com.eignex.klause.factor.table

import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.AtomKind
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.factorAt
import com.eignex.klause.propagation.propagate
import com.eignex.klause.propagation.reasonOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MddPropagatorTest {

    @Test
    fun `repeated propagate from identical state preserves filtering`() {
        // 2-symbol MDD that accepts {1,2}* of length 2; states: layer0 = {0}, layer1 = {0},
        // layer2 = {0}. Initial 0, accepting {0}. Two transitions per layer.
        val factor = Mdd(
            seq = intArrayOf(0, 1),
            numStatesPerLayer = intArrayOf(1, 1, 1),
            layerStarts = intArrayOf(0, 6, 12),
            transitions = longArrayOf(
                0, 1, 0, // layer 0: 0 --1--> 0
                0, 2, 0, // layer 0: 0 --2--> 0
                0, 1, 0, // layer 1: 0 --1--> 0
                0, 2, 0, // layer 1: 0 --2--> 0
            ),
            initial = 0,
            accepting = intArrayOf(0),
            recordStride = 3,
        )
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(1, 2), IntDomain(1, 2)),
            factors = arrayOf<Factor>(factor),
        )
        val r1 = problem.propagate(Assumptions.None)
        assertTrue(r1 is PropagationResult.Implied, "first fire should reach fixpoint; got $r1")
        // Pinning seq[0] = 1 narrows a domain; re-propagating must still reach fixpoint.
        val r2 = problem.propagate(Assumptions(ints = mapOf(0 to 1)))
        assertTrue(r2 is PropagationResult.Implied, "second fire with pin should still propagate; got $r2")
    }

    @Test
    fun `mdd deductions are implied by their reasons under carved holes`() {
        val rng = Random(0x3DD0)
        repeat(300) { iter ->
            val n = 4
            val numStatesPerLayer = IntArray(n + 1) { if (it == 0 || it == n) 1 else 3 }
            val records = ArrayList<Long>()
            val layerStarts = IntArray(n + 1)
            for (layer in 0 until n) {
                layerStarts[layer] = records.size
                for (src in 0 until numStatesPerLayer[layer]) {
                    for (symbol in 0L..2L) {
                        if (rng.nextInt(3) == 0) continue
                        records += listOf(src.toLong(), symbol, rng.nextInt(numStatesPerLayer[layer + 1]).toLong())
                    }
                }
            }
            layerStarts[n] = records.size
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = n,
                intDomains = Array(n) { IntDomain(0, 2) },
                factors = arrayOf<Factor>(
                    Mdd(
                        seq = IntArray(n) { it },
                        numStatesPerLayer = numStatesPerLayer,
                        layerStarts = layerStarts,
                        transitions = records.toLongArray(),
                        initial = 0,
                        accepting = intArrayOf(0),
                        recordStride = 3,
                    ),
                ),
            )
            PropagationReasonOracle.assertReasonsImply(problem, "mdd#$iter") { state ->
                (0 until 4).all {
                    val v = rng.nextInt(n)
                    when (rng.nextInt(3)) {
                        0 -> state.excludeIntValue(v, rng.nextInt(3).toLong())
                        1 -> state.tightenIntMin(v, rng.nextInt(2).toLong())
                        else -> state.tightenIntMax(v, 1L + rng.nextInt(2))
                    }
                }
            }
        }
    }

    @Test
    fun `cost mdd deductions are implied by their reasons`() {
        val rng = Random(0x3DD1)
        repeat(200) { iter ->
            val n = 3
            val numStatesPerLayer = IntArray(n + 1) { if (it == 0 || it == n) 1 else 2 }
            val records = ArrayList<Long>()
            val layerStarts = IntArray(n + 1)
            for (layer in 0 until n) {
                layerStarts[layer] = records.size
                for (src in 0 until numStatesPerLayer[layer]) {
                    for (symbol in 0L..2L) {
                        if (rng.nextInt(3) == 0) continue
                        val dst = rng.nextInt(numStatesPerLayer[layer + 1]).toLong()
                        records += listOf(src.toLong(), symbol, dst, rng.nextInt(4).toLong())
                    }
                }
            }
            layerStarts[n] = records.size
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = n + 1,
                intDomains = Array(n + 1) { if (it == n) IntDomain(0, 9) else IntDomain(0, 2) },
                factors = arrayOf<Factor>(
                    Mdd(
                        seq = IntArray(n) { it },
                        numStatesPerLayer = numStatesPerLayer,
                        layerStarts = layerStarts,
                        transitions = records.toLongArray(),
                        initial = 0,
                        accepting = intArrayOf(0),
                        recordStride = 4,
                        cost = n,
                    ),
                ),
            )
            PropagationReasonOracle.assertReasonsImply(problem, "cost-mdd#$iter") { state ->
                (0 until 4).all {
                    val v = rng.nextInt(n + 1)
                    val top = if (v == n) 9 else 2
                    when (rng.nextInt(3)) {
                        0 -> state.excludeIntValue(v, rng.nextInt(top + 1).toLong())
                        1 -> state.tightenIntMin(v, rng.nextInt(top).toLong())
                        else -> state.tightenIntMax(v, 1L + rng.nextInt(top))
                    }
                }
            }
        }
    }

    @Test
    fun `an mdd prune cites only the bounds its cut of the diagram crosses`() {
        // Words with x0 = x1, then any x2: x0 <= 1 removes 2 from x1, and x2 >= 1 plays no part in that.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(0, 2) },
            factors = arrayOf<Factor>(
                Mdd(
                    seq = intArrayOf(0, 1, 2),
                    numStatesPerLayer = intArrayOf(1, 3, 1, 1),
                    layerStarts = intArrayOf(0, 9, 18, 27),
                    transitions = longArrayOf(
                        0, 0, 0, 0, 1, 1, 0, 2, 2,
                        0, 0, 0, 1, 1, 0, 2, 2, 0,
                        0, 0, 0, 0, 1, 0, 0, 2, 0,
                    ),
                    initial = 0,
                    accepting = intArrayOf(0),
                    recordStride = 3,
                ),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMax(0, 1L) && state.tightenIntMin(2, 1L))
        state.currentFactor = 0

        check(state.factorAt(0).propagate(state, 0))

        val cited = state.reasonOf(state.intMaxAntecedents[1])!!.map { lit ->
            val atom = Lit.variable(lit) - problem.numBoolVars
            Triple(state.atoms.intVar[atom], state.atoms.kind[atom], state.atoms.threshold[atom])
        }
        assertEquals(listOf(Triple(0, AtomKind.LE, 1L)), cited)
    }
}
