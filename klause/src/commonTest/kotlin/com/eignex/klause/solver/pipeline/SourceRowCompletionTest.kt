package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceRowCompletionTest {
    private fun complete(
        problem: Problem,
        center: LongArray,
        seed: Pair<Int, Int>,
        frozen: (Int) -> Boolean = { false },
    ): SourceRowCompletion.Packet {
        val model = problem.bake()
        val objective = LinearObjective(intCoefficients = LongArray(model.numIntVars) { if (it == 0) 1L else 0L })
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            Cancellation.Never))
        val funding = SourceLpFunding(1_000_000, 4_000_000).apply { grant(1_000_000) }
        val packets = SourceRowCompletion(model, source, funding, frozen) { funding.paused }
            .complete(Sample(booleanArrayOf(), center), listOf(seed)) { it == seed.first }
        return assertNotNull(packets).single()
    }

    private fun inputs(packet: SourceRowCompletion.Packet) = packet.members.map {
        assertEquals(SourceCoordinate.Kind.Integer, it.coordinate.kind)
        it.coordinate.id
    }

    @Test
    fun `a blocked seed should complete through every equality its move breaks`() {
        val chain = Problem(0, 5, Array(5) { IntDomain(0, 1) }, listOf(
            Linear(longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0L),
            Linear(longArrayOf(1, -1), intArrayOf(1, 2), LinearOp.EQ, 0L)))

        val packet = complete(chain, longArrayOf(1, 1, 1, 0, 0), 0 to -1)

        assertEquals(SourceRowCompletion.Outcome.Complete, packet.outcome)
        assertEquals(listOf(0, 1, 2), inputs(packet))
        assertNull(packet.members.first().row)
        assertTrue(packet.members.drop(1).all { it.row != null })
    }

    @Test
    fun `row slack should leave a seed without added inputs`() {
        val knapsack = Problem(0, 3, Array(3) { IntDomain(0, 1) },
            listOf(Linear(longArrayOf(2, 3, 4), intArrayOf(0, 1, 2), LinearOp.GE, 5L)))

        val packet = complete(knapsack, longArrayOf(1, 1, 1), 0 to -1)

        assertEquals(SourceRowCompletion.Outcome.Complete, packet.outcome)
        assertEquals(listOf(0), inputs(packet))
    }

    @Test
    fun `integer compensators should move just far enough to restore the row`() {
        val scaled = Problem(0, 2, Array(2) { IntDomain(0, 5) },
            listOf(Linear(longArrayOf(2, -1), intArrayOf(0, 1), LinearOp.EQ, 0L)))

        val packet = complete(scaled, longArrayOf(2, 4), 0 to -1)

        assertEquals(listOf(0, 1), inputs(packet))
        assertEquals(BigFraction.ofLong(2), packet.members.last().value)
    }

    @Test
    fun `a pinned compensator should decline the packet as unrepairable`() {
        val exchange = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 1L)))

        val packet = complete(exchange, longArrayOf(1, 0), 0 to -1, frozen = { it == 1 })

        assertEquals(SourceRowCompletion.Outcome.Unrepairable, packet.outcome)
        assertEquals(listOf(0), inputs(packet))
        assertNotNull(packet.blockedRow)
    }

    @Test
    fun `a seed without a domain step in its direction should be immovable`() {
        val exchange = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 1L)))

        val packet = complete(exchange, longArrayOf(1, 0), 0 to 1)

        assertEquals(SourceRowCompletion.Outcome.Immovable, packet.outcome)
        assertTrue(packet.members.isEmpty())
    }
}
