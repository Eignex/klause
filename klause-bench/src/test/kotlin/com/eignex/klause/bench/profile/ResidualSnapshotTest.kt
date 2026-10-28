package com.eignex.klause.bench.profile

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.formats.json.ProblemJson
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ResidualSnapshotTest {
    private fun capture(): ResidualCapture {
        val factors = listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false))),
            Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true))),
            Clause(intArrayOf(Lit.make(4, true), Lit.make(0, false), Lit.make(2, false))),
        )
        val problem = Problem(6, 0, emptyArray(), factors.toTypedArray())
        return ResidualSnapshots.capture(
            problem, LinearObjective(longArrayOf(1, -2, 3, 4, 5, -6), constant = 10),
            listOf(null, null, null, null, true, null), LearnedResidualInspection(emptyList(), 0, null),
            JsonObject(emptyMap()), JsonObject(emptyMap()), null, Cancellation.Never, ResidualSnapshots.MAX_BYTES,
        )
    }

    @Test
    fun `a snapshot should round trip exact signed objective and component membership`() {
        val captured = assertNotNull(capture().bytes)

        val snapshot = ResidualSnapshots.load(captured.decodeToString())
        val roundTrip = ResidualSnapshots.encode(snapshot).toByteArray()

        assertContentEquals(captured, roundTrip)
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), snapshot.sourceComponents)
        assertEquals("15", snapshot.fixedObjective)
        assertEquals("-6", snapshot.isolatedObjective)
        assertEquals(listOf(0, 1, 2, 3, 5), snapshot.residualToPrepared)
    }

    @Test
    fun `retained source residual should mean the original clauses under the captured condition`() {
        val snapshot = ResidualSnapshots.load(assertNotNull(capture().bytes).decodeToString())
        val residual = ResidualSnapshots.enforcingProblem(snapshot)
        val original = ProblemJson().decodeFromJsonElement(snapshot.preparedProblem)

        for (bits in 0 until 32) {
            val full = List(6) { v -> snapshot.values[v] ?: (bits and (1 shl snapshot.preparedToResidual[v]) != 0) }
            val local = snapshot.residualToPrepared.map { full[it] }
            val expected = original.factors.all { ResidualReplayControls.satisfied(it, full) }
            val actual = residual.factors.all { ResidualReplayControls.satisfied(it, local) }
            assertEquals(expected, actual)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["mapping", "inverse", "condition", "membership", "objective", "authority", "source"])
    fun `altered reconstruction fields should be rejected`(field: String) {
        val snapshot = ResidualSnapshots.load(assertNotNull(capture().bytes).decodeToString())
        val altered = when (field) {
            "mapping" -> snapshot.copy(residualToPrepared = snapshot.residualToPrepared.reversed())
            "inverse" -> snapshot.copy(preparedToResidual = List(6) { 0 })
            "condition" -> snapshot.copy(values = List(6) { null })
            "membership" -> snapshot.copy(sourceComponents = listOf(listOf(0, 1, 2, 3)))
            "objective" -> snapshot.copy(fixedObjective = "14")
            "source" -> snapshot.copy(sourceIdentity = JsonObject(mapOf("sha256" to JsonPrimitive("foreign"))))
            else -> snapshot.copy(originalSourceReplay = "available")
        }

        assertFailsWith<IllegalArgumentException> { ResidualSnapshots.verify(altered) }
    }

    @Test
    fun `a learned PB and a checked cutoff should remain in the enforcing scope`() {
        val problem = Problem(4, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true))),
        ))
        val session = PropagationSession(problem, nativeSat = false)
        session.addLearnedPb(LongArray(4) { 1 }, IntArray(4) { Lit.make(it, false) }, 1, 1, permanent = true)
        val capture = ResidualSnapshots.capture(
            problem, LinearObjective(LongArray(4) { 1 }), List(4) { null },
            LearnedResidualRestrictions.inspect(session), JsonObject(emptyMap()), JsonObject(emptyMap()),
            ResidualCutoff("4", List(4) { true }), Cancellation.Never, ResidualSnapshots.MAX_BYTES,
        )

        val snapshot = ResidualSnapshots.load(assertNotNull(capture.bytes).decodeToString())

        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), snapshot.sourceComponents)
        assertEquals(listOf(listOf(0, 1, 2, 3)), snapshot.enforcingComponents)
        assertEquals("unavailable", snapshot.learned.single().logicalScope)
        assertFailsWith<IllegalArgumentException> { ResidualSnapshots.verify(snapshot.copy(
            learned = snapshot.learned.map { it.copy(logicalScope = "source-valid") },
        )) }
    }

    @Test
    fun `interrupted capture should publish no partial payload`() {
        val capture = ResidualSnapshots.capture(
            Problem(0, 0, emptyArray(), emptyArray()), LinearObjective(), emptyList(),
            LearnedResidualInspection(emptyList(), 0, null), JsonObject(emptyMap()), JsonObject(emptyMap()),
            null, Cancellation { true }, ResidualSnapshots.MAX_BYTES,
        )

        assertEquals("interrupted", capture.decline)
        assertEquals(null, capture.bytes)
    }

    @Test
    fun `one containing budget should bound retained snapshot count and bytes`() {
        val budget = ResidualSnapshotBudget(1, ResidualSnapshots.MAX_BYTES)
        val retained = budget.retain { capture() }

        val next = budget.retain { error("must not renew the count allowance") }

        assertNotNull(retained.bytes)
        assertEquals("snapshot-count-limit", next.decline)
        assertEquals(retained.bytes.size, budget.bytes)
        val exhausted = ResidualSnapshotBudget(1, 0).retain { error("must not renew the byte allowance") }
        assertEquals("snapshot-byte-limit", exhausted.decline)
    }

    @Test
    fun `a zero replay allowance should leave a nonempty residual incomplete`() {
        val snapshot = ResidualSnapshots.load(assertNotNull(capture().bytes).decodeToString())

        val replay = ResidualReplayControls.replay(snapshot, enforcing = false, millis = 0, seed = 3)

        assertEquals("false", replay.getValue("compositionComplete").toString())
        assertEquals("null", replay.getValue("composedOptimum").toString())
        assertFalse(replay.getValue("nativeResidualWitnessChecked").toString().toBoolean())
    }
}
