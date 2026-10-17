package com.eignex.klause.bench.profile

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.lang.ref.WeakReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BranchAttemptObservationTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `source dependency roles should retain the attempted variable and polarity`(arithmetic: Boolean) {
        val observation = BranchAttemptObservation()
        observation.attempted(Any(), Any(), Any(), 3, 2, false, -1, arithmetic)

        val report = observation.snapshot()

        assertEquals("COMPLETE", report["status"])
        val row = (report.getValue("trace") as List<*>).single() as Map<*, *>
        assertEquals(2, row["variable"])
        assertEquals(false, row["positive"])
        assertEquals(if (arithmetic) "ARITHMETIC_DEPENDENCY" else "NON_ARITHMETIC", row["role"])
    }

    @Test
    fun `aggregate roles should continue after the bounded trace fills`() {
        val observation = BranchAttemptObservation(traceCap = 1)
        val run = Any()
        val session = Any()
        val component = Any()
        observation.attempted(run, session, component, 2, 0, true, -1, true)
        observation.attempted(run, session, component, 2, 1, false, -1, false)

        val report = observation.snapshot()

        assertEquals("COMPLETE", report["status"])
        assertEquals(2L, report["events"])
        assertEquals(1L, report["traceDropped"])
        assertEquals(1, (report["trace"] as List<*>).size)
        assertEquals(1L, (report["counts"] as Map<*, *>)["NON_ARITHMETIC"])
    }

    @Test
    fun `distinct live owners should retain separate identity and attempt counts`() {
        val observation = BranchAttemptObservation()
        val session = Any()
        val component = Any()
        val first = Any()
        val second = Any()
        observation.attempted(first, session, component, 1, 0, true, -1, true)
        observation.attempted(second, session, component, 1, 0, true, -1, true)
        observation.attempted(first, session, component, 1, 0, false, -1, true)

        val owners = observation.snapshot()["owners"] as List<*>

        assertEquals(listOf(2L, 1L), owners.map { (it as Map<*, *>)["attempts"] })
    }

    @Test
    fun `an owner should refuse a changed context without conflating its identity`() {
        val observation = BranchAttemptObservation()
        val run = Any()
        val component = Any()
        observation.attempted(run, Any(), component, 1, 0, true, -1, true)
        observation.attempted(run, Any(), component, 1, 0, true, -1, true)

        assertEquals("INCONCLUSIVE", observation.snapshot()["status"])
    }

    @Test
    fun `expired weak owner identities should not replenish the owner allowance`() {
        val observation = BranchAttemptObservation(ownerCap = 1)
        observation.attempted(Any(), Any(), Any(), 1, 0, true, -1, true)
        val owners = observation.javaClass.getDeclaredField("owners").apply { isAccessible = true }
            .get(observation) as List<*>
        val owner = requireNotNull(owners.single())
        for (name in listOf("run", "session", "component")) {
            val reference = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
            (reference as WeakReference<*>).clear()
        }
        observation.attempted(Any(), Any(), Any(), 1, 0, true, -1, true)

        val report = observation.snapshot()

        assertEquals("INCONCLUSIVE", report["status"])
        val retained = (report["owners"] as List<*>).single() as Map<*, *>
        assertEquals(false, retained["runAlive"])
        assertEquals(false, retained["sessionAlive"])
        assertEquals(false, retained["componentAlive"])
        assertEquals(listOf("owner-cap"), report["failures"])
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2])
    fun `an assigned or invalid selected source slot should withhold role qualification`(slot: Int) {
        val observation = BranchAttemptObservation()
        observation.attempted(Any(), Any(), Any(), 1, 0, true, slot, true)

        assertEquals("INCONCLUSIVE", observation.snapshot()["status"])
    }

    @Test
    fun `named Boolean decisions should not acquire a source dependency role`() {
        val observation = BranchAttemptObservation()
        observation.attempted(Any(), Any(), Any(), 1, 2, true, null, null)

        val counts = observation.snapshot()["counts"] as Map<*, *>

        assertEquals(1L, counts["NAMED_THEORY_BOOLEAN"])
        assertEquals(0L, counts["ARITHMETIC_DEPENDENCY"])
        assertEquals(0L, counts["NON_ARITHMETIC"])
    }

    @Test
    fun `non Boolean decisions should retain their separate closed role`() {
        val observation = BranchAttemptObservation()
        observation.attempted(Any(), Any(), Any(), 1, null, null, null, null)

        assertEquals(1L, (observation.snapshot()["counts"] as Map<*, *>)["OTHER_DECISION"])
    }

    @Test
    fun `event exhaustion should preserve the accepted prefix and withhold completeness`() {
        val observation = BranchAttemptObservation(eventCap = 1)
        val run = Any()
        val session = Any()
        val component = Any()
        repeat(2) { observation.attempted(run, session, component, 1, 0, true, -1, true) }

        val report = observation.snapshot()

        assertEquals("INCONCLUSIVE", report["status"])
        assertEquals(1L, report["events"])
        assertEquals(listOf("event-cap"), report["failures"])
    }

    @Test
    fun `an observation with no attempted branches should remain inconclusive`() {
        assertEquals("INCONCLUSIVE", BranchAttemptObservation().snapshot()["status"])
    }

    @Test
    fun `failure storage should remain bounded and disable further evidence`() {
        val observation = BranchAttemptObservation()
        repeat(40) { observation.fail("failure-$it-${"x".repeat(100)}") }
        observation.attempted(Any(), Any(), Any(), 1, 0, true, -1, true)

        val report = observation.snapshot()

        assertTrue(observation.disabled)
        assertEquals(0L, report["events"])
        val failures = report["failures"] as List<*>
        assertEquals(32, failures.size)
        assertFalse(failures.any { it.toString().length > 64 })
    }
}
