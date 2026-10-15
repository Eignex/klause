package com.eignex.klause.propagation

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.MixedVars
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import kotlin.test.Test
import kotlin.test.assertEquals

class IntEventMachineryTest {
    private class WakeCounter(val v: Int, val kind: Int) :
        Factor,
        Propagator {
        var fires: Int = 0
        override val variables: VarList = MixedVars(spanInts = intArrayOf(v), boolVars = IntArray(0))
        override val initialIntEventWatches: IntArray = intArrayOf(IntEvent.pack(v, kind))

        override fun propagate(state: PropagationState, factorId: Int): Boolean {
            fires++
            return true
        }

        override fun remap(mapping: VarRemap): Factor = WakeCounter(mapping.int(v), kind)

        override fun structuralKey(): StructuralKey = error("test double has no structural key")

        override fun conflictReason(state: PropagationState, factorId: Int): IntArray? = null
    }

    @Test
    fun `a subscriber should wake only on its subscribed kind`() {
        val counter = WakeCounter(v = 0, kind = IntEvent.LB_RAISED)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 5)),
            factors = listOf(counter),
        )
        val session = PropagationSession(problem)
        session.seed(Assumptions.None)
        val baseline = counter.fires

        session.pinIntAtMost(0, 3) // upper bound lowered — NOT an LB_RAISED, must not wake
        assertEquals(baseline, counter.fires)

        session.pinIntAtLeast(0, 2) // lower bound raised — the subscribed kind, must wake exactly once
        assertEquals(baseline + 1, counter.fires)
    }
}
