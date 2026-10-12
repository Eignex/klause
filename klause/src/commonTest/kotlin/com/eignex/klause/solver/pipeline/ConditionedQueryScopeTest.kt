package com.eignex.klause.solver.pipeline

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConditionedQueryScopeTest {
    @Test
    fun `a condition captures caller buffers before nested queries`() {
        val pins = mutableMapOf(0 to true)
        val bounds = mutableMapOf(0 to BigFraction.ONE)
        val scope = ConditionedQueryScope(Cancellation.Never)

        scope.withCondition(ExactNeighborhoodCondition(pins, bounds, emptyMap()), Cancellation.Never) { outer ->
            pins[0] = false
            bounds.clear()
            scope.withCondition(ExactNeighborhoodCondition(emptyMap(), emptyMap(), mapOf(0 to BigFraction.ONE)),
                Cancellation.Never) { nested ->
                assertEquals(2, nested.size)
                assertEquals(true, nested.first().bools[0])
                assertEquals(BigFraction.ONE, nested.first().lower[0])
            }
            assertEquals(1, outer.size)
        }
    }

    @Test
    fun `a failed query retracts its conditions and invocation stop`() {
        val scope = ConditionedQueryScope(Cancellation.Never)
        val condition = ExactNeighborhoodCondition(mapOf(0 to true), emptyMap(), emptyMap())
        assertFailsWith<IllegalArgumentException> {
            scope.withCondition(condition, Cancellation { true }) {
                assertTrue(scope.stop())
                throw IllegalArgumentException("query failed")
            }
        }

        scope.withCondition(ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap()), Cancellation.Never) {
            assertEquals(1, it.size)
            assertTrue(it.single().bools.isEmpty())
            assertFalse(scope.stop())
        }
    }

    @Test
    fun `closing a query owner stops native work and rejects another query`() {
        val scope = ConditionedQueryScope(Cancellation.Never)

        scope.close()

        assertTrue(scope.stop())
        assertFailsWith<IllegalStateException> {
            scope.withCondition(ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap()), Cancellation.Never) {}
        }
    }
}
