package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class FiniteCompanionAdmissionTest {
    @Test
    fun `late first starts should decline before constructing a context`() {
        val clock = TestTimeSource()
        var captured = false
        val admission = FiniteCompanionAdmission(Cancellation.until(clock.markNow() + 30.seconds), 4_000, 1_000,
            capture = { captured = true; null }, clock = clock)

        assertTrue(admission.initiallyFunded)
        clock += 23.seconds
        assertNull(admission.contextForStart(Cancellation.Never, 1_000))

        assertFalse(captured)
        assertEquals(0L, admission.stages()["finiteCompanionPreparationAttempts"])
        assertEquals(1L, admission.stages()["finiteCompanionStartDeclines"])
    }

    @Test
    fun `unfunded pair lifetimes should avoid affine construction`() {
        for (remaining in listOf(1, 7, 8)) {
            val clock = TestTimeSource()
            var captured = false
            val admission = FiniteCompanionAdmission(Cancellation.until(clock.markNow() + remaining.seconds),
                4_000, 1_000, capture = { captured = true; null }, clock = clock)

            assertFalse(admission.initiallyFunded)
            assertNull(admission.contextForStart(Cancellation.Never, 1_000))

            assertFalse(captured)
            assertEquals(0L, admission.stages()["finiteCompanionPreparationAttempts"])
            assertEquals(1L, admission.stages()["finiteCompanionAdmissionDeclined"])
        }
    }

    @Test
    fun `admitted workers should share one paid capture and recheck their remaining lifetime`() {
        val model = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
        val context = assertNotNull(model.linearAuthorityContext(LinearObjective(intCoefficients = longArrayOf(1)),
            ObjectiveSense.MINIMIZE, Cancellation.Never))
        val clock = TestTimeSource()
        var calls = 0
        val admission = FiniteCompanionAdmission(Cancellation.until(clock.markNow() + 10.seconds), 4_000, 1_000,
            capture = { calls++; clock += 100.milliseconds; context }, clock = clock)

        assertSame(context, admission.contextForStart(Cancellation.Never, 1_000))
        clock += 4.seconds
        assertSame(context, admission.contextForStart(Cancellation.Never, 1_000))
        clock += 2.seconds
        assertNull(admission.contextForStart(Cancellation.Never, 1_000))

        assertEquals(1, calls)
        assertEquals(100_000_000L, admission.stages()["finiteCompanionPreparationNs"])
        assertEquals(1L, admission.stages()["finiteCompanionPreparationReady"])
        assertEquals(1L, admission.stages()["finiteCompanionStartDeclines"])
    }

    @Test
    fun `capture should preserve the pair lifetime and obey its query grant`() {
        for (grant in listOf(50L, 1_000L)) {
            val clock = TestTimeSource()
            var calls = 0
            val admission = FiniteCompanionAdmission(Cancellation.until(clock.markNow() + 8_100.milliseconds),
                4_000, 1_000, capture = {
                    calls++
                    clock += minOf(grant, 100L).milliseconds
                    assertTrue(it.isCancelled())
                    null
                }, clock = clock)

            assertTrue(admission.initiallyFunded)
            assertNull(admission.contextForStart(Cancellation.Never, grant))
            assertNull(admission.contextForStart(Cancellation.Never, grant))

            assertEquals(1, calls)
            assertEquals(TerminationReason.BudgetExhausted, admission.declineReason)
            assertEquals(1L, admission.stages()["finiteCompanionPreparationUnavailable"])
        }
    }

    @Test
    fun `unavailable complete affine capture should not repeat in sibling workers`() {
        val clock = TestTimeSource()
        var calls = 0
        val admission = FiniteCompanionAdmission(Cancellation.until(clock.markNow() + 30.seconds), 4_000, 1_000,
            capture = { calls++; null }, clock = clock)

        assertNull(admission.contextForStart(Cancellation.Never, 1_000))
        assertNull(admission.contextForStart(Cancellation.Never, 1_000))

        assertEquals(1, calls)
        assertEquals(TerminationReason.Unsupported, admission.declineReason)
        assertEquals(1L, admission.stages()["finiteCompanionPreparationAttempts"])
    }
}
