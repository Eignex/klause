package com.eignex.klause.bench.metric

import com.eignex.klause.solver.SearchEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngineTimesTest {

    @Test
    fun `records first incumbent and tracks the best, ignoring non-improvements`() {
        val t = EngineTimes()
        t.listener(SearchEvent.Restart(1, 10)) // non-incumbent events are ignored
        assertEquals(-1L, t.firstMs)
        t.listener(SearchEvent.Incumbent(5.0))
        val first = t.firstMs
        assertTrue(first >= 0)
        t.listener(SearchEvent.Incumbent(3.0)) // improvement moves bestMs
        t.listener(SearchEvent.Incumbent(7.0)) // worse: best unchanged
        assertEquals(first, t.firstMs)
        assertTrue(t.bestMs >= first)
    }

    @Test
    fun `concurrent incumbents from portfolio workers keep a consistent first stamp`() {
        val t = EngineTimes()
        val threads = (0 until 4).map { i ->
            Thread { repeat(100) { j -> t.listener(SearchEvent.Incumbent(1000.0 - i * 100 - j)) } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertTrue(t.firstMs >= 0)
        assertTrue(t.bestMs >= t.firstMs)
    }
}
