package com.eignex.klause.lp.relaxation

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.CutExpression
import com.eignex.klause.lp.engine.CutPremise
import com.eignex.klause.lp.engine.CutSource
import com.eignex.klause.lp.engine.CutSourceKind
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.propagation.PropagationSession
import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CutSourceMapTest {
    @Test
    fun `copied column bindings should evaluate their own source geometry`() {
        val x = CutSource(CutSourceKind.INTEGER, 0)
        val y = CutSource(CutSourceKind.INTEGER, 1)
        val original = CutColumnSource(x)
        val expression = original.expression()
        val variants = listOf(
            original.copy(source = y) to 7L,
            original.copy(scale = BigFraction.ofLong(2)) to 10L,
            original.copy(offset = BigFraction.ofLong(3)) to 8L,
        )

        for ((column, expected) in variants) {
            val value = column.expression().value { if (it == x) BigFraction.ofLong(5) else BigFraction.ofLong(7) }

            assertEquals(BigFraction.ofLong(expected), value)
            assertEquals(BigFraction.ofLong(5), expression.value { BigFraction.ofLong(5) })
        }
    }

    @Test
    fun `CP bounds should retain raw integer meaning for incompatible column mappings`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 10) }, emptyArray())
        val session = PropagationSession(problem)
        session.pinIntAtLeast(0, 3)
        val builder = LpBuilder()
        builder.addVar(0, 10)
        val model = builder.build(Sense.MINIMIZE)
        val x = CutSource(CutSourceKind.INTEGER, 0)
        val unit = CutColumnSource(x)
        val variants = listOf(
            unit to intArrayOf(0),
            unit to intArrayOf(-1),
            unit to intArrayOf(100),
            unit to intArrayOf(),
            unit to null,
            unit.copy(source = CutSource(CutSourceKind.INTEGER, 1)) to intArrayOf(0),
            unit.copy(source = CutSource(CutSourceKind.BOOLEAN, 0)) to intArrayOf(0),
            unit.copy(scale = BigFraction.ofLong(2)) to intArrayOf(0),
            unit.copy(offset = BigFraction.ONE) to intArrayOf(0),
        )
        val expression = CutExpression(mapOf(x to BigFraction.ONE))

        for ((column, mapping) in variants) {
            val source = CutSourceMap(problem, 0, listOf(column))

            val active = source.withCpBounds(model, session, mapping)

            assertTrue(active.isActive(CutPremise.Bound(expression, false, BigFraction.ofLong(3))))
            assertFalse(active.isActive(CutPremise.Bound(expression, false, BigFraction.ofLong(4))))
            assertFalse(active.isGlobal(CutPremise.Bound(expression, false, BigFraction.ofLong(3))))
        }
    }

    @Test
    fun `refreshed CP bounds should not retain a stronger preceding view`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)), emptyArray())
        val builder = LpBuilder()
        builder.addVar(0, 10)
        val model = builder.build(Sense.MINIMIZE)
        val x = CutSource(CutSourceKind.INTEGER, 0)
        val source = CutSourceMap(problem, 0, listOf(CutColumnSource(x)))
        val tightSession = PropagationSession(problem)
        tightSession.pinIntAtLeast(0, 7)
        val tight = source.withCpBounds(model, tightSession, intArrayOf(0))
        val looseSession = PropagationSession(problem)
        looseSession.pinIntAtLeast(0, 2)
        val expression = CutExpression(mapOf(x to BigFraction.ONE))
        val strong = CutPremise.Bound(expression, false, BigFraction.ofLong(7))

        val loose = tight.withCpBounds(model, looseSession, intArrayOf(0))

        assertFalse(loose.isActive(strong))
        assertTrue(loose.isActive(CutPremise.Bound(expression, false, BigFraction.ofLong(2))))
        assertTrue(tight.isActive(strong))
        assertFalse(source.isActive(strong))
        assertEquals(tight.epoch + 1, loose.epoch)
    }
}
