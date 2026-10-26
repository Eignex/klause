package com.eignex.klause.bench.profile

import com.eignex.klause.factor.global.Inverse
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.propagation.bake
import com.eignex.klause.util.Cancellation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class ChannelViewsTest {
    @Test
    fun `searched channel factories use the admitted definition network`() {
        val program = parseFlatZinc("var bool: b; var 0..1: y; " +
            "constraint bool2int(b, y) :: defines_var(y); solve satisfy;")
        val maintained = assertNotNull(DefinitionalSweep.fromSource(program.problem, program.definitionHints))

        val searched = ChannelView.SearchedValues.sweep(maintained, Cancellation.Never)

        assertNull(searched)
        assertEquals(1, maintained.size)
    }

    @Test
    fun `inverse role transposition preserves feasibility at every source assignment`() {
        val source = Problem(0, 4, Array(4) { IntDomain(1, 2) },
            arrayOf(Inverse(intArrayOf(0, 1), intArrayOf(2, 3), 1, 1)))
        val view = assertNotNull(inverseAssignmentView(source, Cancellation.Never))
        val primal = LocalSearchState(source.bake(), Random(0))
        val inverse = LocalSearchState(view.bake(), Random(0))

        for (bits in 0 until 16) {
            for (variable in 0 until 4) {
                val value = 1L + ((bits shr variable) and 1)
                primal.assignment.setInt(variable, value)
                inverse.assignment.setInt(variable, value)
            }
            primal.recompute()
            inverse.recompute()

            assertEquals(primal.cost == 0L, inverse.cost == 0L, "source assignment $bits")
        }
    }

    @Test
    fun `inverse role transposition retains source holes semantics and implied roles`() {
        val source = Problem(0, 6, Array(6) { IntDomain(4, 6).excludeValue(5) },
            arrayOf(Inverse(intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), 4, 4)),
            impliedFactorMask = booleanArrayOf(true))

        val view = assertNotNull(inverseAssignmentView(source, Cancellation.Never))

        assertSame(source.declaredIntDomains, view.declaredIntDomains)
        assertSame(source.semantics, view.semantics)
        assertSame(source.impliedFactorMask, view.impliedFactorMask)
    }

    @Test
    fun `inverse role transposition swaps unequal index offsets`() {
        val source = Problem(0, 4, arrayOf(IntDomain(4, 5), IntDomain(4, 5),
            IntDomain(-2, -1), IntDomain(-2, -1)),
            arrayOf(Inverse(intArrayOf(0, 1), intArrayOf(2, 3), -2, 4)))
        val view = assertNotNull(inverseAssignmentView(source, Cancellation.Never))
        val channel = view.factors.single() as Inverse

        assertEquals(4, channel.fOffset)
        assertEquals(-2, channel.gOffset)
        assertEquals(listOf(2, 3), channel.f.toList())
        assertEquals(listOf(0, 1), channel.g.toList())
        assertFalse(channel.f.contentEquals(channel.g))
    }

    @Test
    fun `aliased and competing inverse channels decline without a partial view`() {
        val cases = listOf(
            arrayOf<Factor>(Inverse(intArrayOf(0, 1), intArrayOf(0, 1))),
            arrayOf<Factor>(Inverse(intArrayOf(0, 1), intArrayOf(2, 3)),
                Inverse(intArrayOf(2, 3), intArrayOf(4, 5))),
        )
        for (factors in cases) {
            val source = Problem(0, 6, Array(6) { IntDomain(0, 1) }, factors)

            assertNull(inverseAssignmentView(source, Cancellation.Never))
        }
    }

    @Test
    fun `cancelled inverse role transposition publishes no view`() {
        val source = Problem(0, 4, Array(4) { IntDomain(0, 1) },
            arrayOf(Inverse(intArrayOf(0, 1), intArrayOf(2, 3))))

        assertNull(inverseAssignmentView(source, Cancellation { true }))
    }
}
