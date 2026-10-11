package com.eignex.klause.localsearch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class LocalSearchPhaseTimerTest {
    @Test
    fun `suspended sequence time is excluded from its active phase`() {
        var clock = 0L
        val timer = LocalSearchPhaseTimer { clock }
        val source = sequence {
            timer.inPhase("seeding") {
                clock += 2L
                yield(1)
                clock += 3L
                yield(2)
            }
        }.withActiveLocalSearchTime(timer).iterator()

        assertEquals(1, source.next())
        clock += 1000L
        assertEquals(2L, timer.snapshot().getValue("seeding"))
        assertEquals(2, source.next())
        clock += 1000L
        assertFalse(source.hasNext())

        assertEquals(5L, timer.snapshot().getValue("seeding"))
        assertEquals(5L, timer.snapshot().values.sum())
    }

    @Test
    fun `nested phases account elapsed time without counting it twice`() {
        var clock = 0L
        val timer = LocalSearchPhaseTimer { clock }
        timer.resume()

        timer.inPhase("seeding") {
            clock += 2L
            timer.inPhase("selection") { clock += 3L }
            clock += 5L
        }
        timer.pause()

        assertEquals(7L, timer.snapshot().getValue("seeding"))
        assertEquals(3L, timer.snapshot().getValue("selection"))
        assertEquals(10L, timer.snapshot().values.sum())
    }

    @Test
    fun `an interrupted sequence stops accruing time`() {
        var clock = 0L
        val timer = LocalSearchPhaseTimer { clock }
        val source = sequence<Int> {
            timer.inPhase("preparation") {
                clock += 7L
                error("cancelled")
            }
        }.withActiveLocalSearchTime(timer).iterator()

        assertFailsWith<IllegalStateException> { source.hasNext() }
        clock += 1000L

        assertEquals(7L, timer.snapshot().getValue("preparation"))
        assertEquals(7L, timer.snapshot().values.sum())
    }

    @Test
    fun `disabled timing retains the original sequence`() {
        val source = sequenceOf(1, 2)

        val timed = source.withActiveLocalSearchTime(null)

        assertSame(source, timed)
        assertEquals(listOf(1, 2), timed.toList())
    }
}
