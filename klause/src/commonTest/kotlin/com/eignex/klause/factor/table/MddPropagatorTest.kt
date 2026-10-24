package com.eignex.klause.factor.table

import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.factor.table.internals.MddIncrementalState
import com.eignex.klause.factor.table.internals.MddTransitionIndex
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.AtomKind
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.factorAt
import com.eignex.klause.propagation.holeReasonFor
import com.eignex.klause.propagation.mark
import com.eignex.klause.propagation.reasonOf
import com.eignex.klause.propagation.undoTo
import com.eignex.klause.util.Cancellation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MddPropagatorTest {

    private val diagonal = Mdd(
        seq = intArrayOf(0, 1),
        numStatesPerLayer = intArrayOf(1, 4, 1),
        layerStarts = intArrayOf(0, 12, 24),
        transitions = longArrayOf(
            0, 0, 0, 0, 1, 1, 0, 2, 2, 0, 3, 3,
            0, 0, 0, 1, 1, 0, 2, 2, 0, 3, 3, 0,
        ),
        initial = 0,
        accepting = intArrayOf(0),
        recordStride = 3,
    )

    @Test
    fun `interior removals should propagate in both directions without moving bounds`() {
        for (source in 0..1) {
            val problem = Problem(0, 2, Array(2) { IntDomain(0, 3) }, arrayOf<Factor>(diagonal))
            val state = PropagationState(problem, Assumptions.None)
            assertNull(state.runToFixpoint(allFactors = true))
            state.undoLogging = true
            state.currentLevel = 1
            check(state.excludeIntValue(source, 1))

            assertNull(state.runToFixpoint(allFactors = false))

            assertFalse(1L in state.intDomains[1 - source])
            assertEquals(0L, state.intDomains[1 - source].min)
            assertEquals(3L, state.intDomains[1 - source].max)
        }
    }

    @Test
    fun `cancellation before a hole event fires should leave the fixpoint incomplete`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 3) }, arrayOf<Factor>(diagonal))
        val state = PropagationState(problem, Assumptions.None)
        assertNull(state.runToFixpoint(allFactors = true))
        state.undoLogging = true
        state.currentLevel = 1
        state.cancelFloor = 0
        check(state.excludeIntValue(0, 1))

        assertNull(state.runToFixpoint(allFactors = false, cancellation = Cancellation { true }))

        assertTrue(state.runCancelled)
        assertTrue(1L in state.intDomains[1])
    }

    @Test
    fun `delayed mdd reasons should retain historical holes across sibling branches`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 3) }, arrayOf<Factor>(diagonal))
        val state = PropagationState(problem, Assumptions.None)
        assertNull(state.runToFixpoint(allFactors = true))
        state.undoLogging = true
        val mark = state.mark()
        state.levelToDecisionVar.add(0)
        state.currentLevel = 1
        check(state.excludeIntValue(0, 1))
        assertNull(state.runToFixpoint(allFactors = false))
        val reason = assertNotNull(state.holeReasonFor(1, 1))
        check(state.excludeIntValue(0, 2) && state.tightenIntMax(0, 2))

        val cited = assertNotNull(state.reasonOf(reason)).map { lit ->
            val atom = Lit.variable(lit) - problem.numBoolVars
            Triple(state.atoms.intVar[atom], state.atoms.kind[atom], state.atoms.threshold[atom])
        }

        assertEquals(listOf(Triple(0, AtomKind.EQ, 1L)), cited)
        state.undoTo(mark)
        state.levelToDecisionVar.add(0)
        state.currentLevel = 1
        check(state.excludeIntValue(0, 2))
        assertNull(state.runToFixpoint(allFactors = false))
        assertTrue(1L in state.intDomains[1])
        val sibling = assertNotNull(state.reasonOf(state.holeReasonFor(1, 2))).map { lit ->
            val atom = Lit.variable(lit) - problem.numBoolVars
            Triple(state.atoms.intVar[atom], state.atoms.kind[atom], state.atoms.threshold[atom])
        }
        assertEquals(listOf(Triple(0, AtomKind.EQ, 2L)), sibling)
    }

    @Test
    fun `root snapshots should neither publish nor reuse reachability through a hole`() {
        val index = MddTransitionIndex.build(
            diagonal.transitions, diagonal.layerStarts, diagonal.numStatesPerLayer, diagonal.recordStride,
        )
        for ((step, hole) in listOf(true, false, true, false).withIndex()) {
            val domains = Array(2) {
                if (hole) IntDomain(0, 3) else IntDomain(-2, 5).excludeValue(-1).excludeValue(4)
            }
            val problem = Problem(0, 2, domains, arrayOf<Factor>(diagonal))
            val state = PropagationState(problem, Assumptions.None)
            if (hole) check(state.excludeIntValue(0, 1))
            val inc = MddIncrementalState(
                state, diagonal.seq, diagonal.numStatesPerLayer, diagonal.layerStarts, diagonal.transitions,
                diagonal.initial, diagonal.accepting, diagonal.recordStride, diagonal.cost, index,
            )

            assertTrue(inc.propagate(state, 0))

            assertEquals(!hole, 1L in state.intDomains[1])
            if (step == 0) assertNull(index.rootSnapshot) else assertNotNull(index.rootSnapshot)
            if (step == 3) {
                state.undoLogging = true
                val mark = state.mark()
                state.currentLevel = 1
                check(state.excludeIntValue(0, 1))
                assertTrue(inc.propagate(state, 0))
                assertFalse(1L in state.intDomains[1])
                state.undoTo(mark)
                state.currentLevel = 1
                check(state.excludeIntValue(0, 2))

                assertTrue(inc.propagate(state, 0))

                assertTrue(1L in state.intDomains[1])
                assertFalse(2L in state.intDomains[1])
            }
        }
    }

    @Test
    fun `cost bounds should exclude weighted paths cut by an interior hole`() {
        val factor = Mdd(
            seq = intArrayOf(0),
            numStatesPerLayer = intArrayOf(1, 1),
            layerStarts = intArrayOf(0, 12),
            transitions = longArrayOf(0, 0, 0, 4, 0, 1, 0, 1, 0, 2, 0, 7),
            initial = 0,
            accepting = intArrayOf(0),
            recordStride = 4,
            cost = 1,
        )
        val problem = Problem(0, 2, arrayOf(IntDomain(0, 2), IntDomain(0, 9)), arrayOf<Factor>(factor))
        val state = PropagationState(problem, Assumptions.None)
        assertNull(state.runToFixpoint(allFactors = true))
        state.undoLogging = true
        state.currentLevel = 1
        check(state.excludeIntValue(0, 1))

        assertNull(state.runToFixpoint(allFactors = false))

        assertEquals(4L, state.intDomains[1].min)
        val cited = assertNotNull(state.reasonOf(state.intMinAntecedents[1])).map { lit ->
            val atom = Lit.variable(lit) - problem.numBoolVars
            Triple(state.atoms.intVar[atom], state.atoms.kind[atom], state.atoms.threshold[atom])
        }
        assertEquals(listOf(Triple(0, AtomKind.EQ, 1L)), cited)
    }

    @Test
    fun `mdd deductions should be implied by their reasons under carved holes`() {
        val rng = Random(0x3DD0)
        repeat(60) { iter ->
            val problem = Problem(0, 4, Array(4) { IntDomain(0, 2) }, arrayOf<Factor>(randomMdd(rng, 4, 3, cost = -1)))
            PropagationReasonOracle.assertReasonsImply(problem, "mdd#$iter") { state -> state.narrow(rng, 2, 2) }
        }
    }

    @Test
    fun `cost mdd deductions should be implied by their reasons`() {
        val rng = Random(0x3DD1)
        repeat(60) { iter ->
            val domains = Array(4) { if (it == 3) IntDomain(0, 9) else IntDomain(0, 2) }
            val problem = Problem(0, 4, domains, arrayOf<Factor>(randomMdd(rng, 3, 2, cost = 3)))
            PropagationReasonOracle.assertReasonsImply(problem, "cost-mdd#$iter") { state -> state.narrow(rng, 3, 9) }
        }
    }

    private fun randomMdd(rng: Random, n: Int, width: Int, cost: Int): Mdd {
        val numStatesPerLayer = IntArray(n + 1) { if (it == 0 || it == n) 1 else width }
        val records = ArrayList<Long>()
        val layerStarts = IntArray(n + 1)
        for (layer in 0 until n) {
            layerStarts[layer] = records.size
            for (src in 0 until numStatesPerLayer[layer]) {
                for (symbol in 0L..2L) {
                    if (rng.nextInt(3) == 0) continue
                    records += listOf(src.toLong(), symbol, rng.nextInt(numStatesPerLayer[layer + 1]).toLong())
                    if (cost >= 0) records += rng.nextInt(4).toLong()
                }
            }
        }
        layerStarts[n] = records.size
        return Mdd(
            seq = IntArray(n) { it },
            numStatesPerLayer = numStatesPerLayer,
            layerStarts = layerStarts,
            transitions = records.toLongArray(),
            initial = 0,
            accepting = intArrayOf(0),
            recordStride = if (cost >= 0) 4 else 3,
            cost = cost,
        )
    }

    private fun PropagationState.narrow(rng: Random, lastVar: Int, lastTop: Int): Boolean = (0 until 4).all {
        val v = rng.nextInt(lastVar + 1)
        val top = if (v == lastVar) lastTop else 2
        when (rng.nextInt(3)) {
            0 -> excludeIntValue(v, rng.nextInt(top + 1).toLong())
            1 -> tightenIntMin(v, rng.nextInt(top).toLong())
            else -> tightenIntMax(v, 1L + rng.nextInt(top))
        }
    }

    @Test
    fun `an mdd prune should cite only the bounds its cut of the diagram crosses`() {
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
