package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Problem
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class NativeCapGrowthTest(private val cap: Int) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "cap={0}")
        fun parameters(): List<Array<Any>> = listOf(arrayOf<Any>(4_000), arrayOf<Any>(20_000))
    }

    @Test
    fun `growing an initialized native window should rank admissions under tiny grants`() {
        val session = PropagationSession(Problem(3, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(1, 2)), Clause(intArrayOf(3, 4)),
        )), nativeSat = true)
        val clause = Clause(intArrayOf(1, 3, 4))
        val classifications = IntArray(cap + 1)
        val configuration = NativeCapConfiguration(false, 2, 6)
        repeat(32) { session.addLearnedClause(clause, cap + 9) }
        session.maintainCapWindow(31, 65_536L, 1_048_576L, { false },
            canDrop = { false }, nativePolicy = configuration) { index ->
            classifications[index]++
            false
        }
        assertEquals(32, session.activeLearnedClauseCount)
        assertFalse(session.nativeCapWindowPending)
        repeat(cap - 31) { index -> session.addLearnedClause(clause, cap + 8 - index) }

        var grants = 0
        var maximumWork = 0L
        while ((session.activeLearnedClauseCount > cap || session.nativeCapWindowPending) && grants < cap * 4) {
            val before = session.work
            session.maintainCapWindow(cap, 256L, 64L, { false }, nativePolicy = configuration) { index ->
                classifications[index]++
                false
            }
            maximumWork = maxOf(maximumWork, session.work - before)
            grants++
        }

        assertEquals(cap, session.activeLearnedClauseCount)
        assertFalse(session.nativeCapWindowPending)
        assertTrue(maximumWork <= 256L)
        assertTrue(classifications.all { it == 1 })
        assertTrue((0..cap).all { session.isLearnedClause(it) == (it != 31) })
    }
}
