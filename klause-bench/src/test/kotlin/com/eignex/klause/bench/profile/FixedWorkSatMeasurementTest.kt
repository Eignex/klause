package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.util.Cancellation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FixedWorkSatMeasurementTest {
    @Test
    fun `conflict limit stops at the identified prefix as comparable work`() {
        val record = measure(FixedWorkUnit.CONFLICTS, 40)

        assertEquals("limit", record.text("stop"))
        assertTrue(record.flag("comparable"))
        assertFalse(record.flag("interrupted"))
        assertEquals(40, record.path("trajectory", "prefix").number("conflicts"))
        assertTrue(record.path("limitPoint").flag("reached"))
    }

    @Test
    fun `repeated invocations with one seed share the prefix digest`() {
        val first = measure(FixedWorkUnit.CONFLICTS, 40, index = 0)
        val second = measure(FixedWorkUnit.CONFLICTS, 40, index = 1)

        assertEquals(first.path("trajectory", "prefix").text("digest"),
            second.path("trajectory", "prefix").text("digest"))
        assertEquals("cold", first.text("jvmState"))
        assertEquals("warm", second.text("jvmState"))
    }

    @Test
    fun `charged work limit stops once the handle reaches the limit`() {
        val record = measure(FixedWorkUnit.WORK, 30)

        assertEquals("limit", record.text("stop"))
        assertTrue(record.path("counters").number("work") >= 30)
    }

    @Test
    fun `a verdict before the limit is a completed search`() {
        val record = measure(FixedWorkUnit.DECISIONS, 1_000_000, source = pigeonhole(5, 4))

        assertEquals("verdict", record.text("stop"))
        assertEquals("Unsat", record.text("result"))
        assertTrue(record.flag("comparable"))
        assertFalse(record.path("limitPoint").flag("reached"))
    }

    @Test
    fun `a deadline during search reports interrupted partial work`() {
        var polls = 0
        val record = measure(FixedWorkUnit.CONFLICTS, 1_000_000, deadline = Cancellation { ++polls > DEADLINE_POLLS })

        assertEquals("deadline", record.text("stop"))
        assertTrue(record.flag("interrupted"))
        assertFalse(record.flag("comparable"))
        assertFalse(record.path("limitPoint").flag("reached"))
        assertTrue(record.path("trajectory").number("conflicts") > 0)
    }

    @Test
    fun `retained rows past the row limit are marked truncated`() {
        val record = measure(FixedWorkUnit.CONFLICTS, 40, traceRows = 10)

        val trajectory = record.path("trajectory")
        assertEquals(10, trajectory.number("rowsWritten"))
        assertTrue(trajectory.flag("rowsTruncated"))
        assertTrue(trajectory.number("events") > 10)
    }

    private fun measure(
        unit: FixedWorkUnit,
        limit: Long,
        index: Int = 0,
        traceRows: Int = 1024,
        deadline: Cancellation? = null,
        source: ByteArray = pigeonhole(7, 6),
    ): JsonObject {
        val out = createTempDirectory("fixed-work-sat").toFile()
        try {
            val request = FixedWorkRequest(out.resolve("php.cnf").path, sha256(source), 1, "clausal-balanced",
                unit, limit, 10_000, 0, traceRows)
            val trace = out.resolve(FixedWorkSatMeasurement.TRACE)
            val record = if (deadline == null) {
                FixedWorkSatMeasurement.invoke(source, request, index, true, trace)
            } else FixedWorkSatMeasurement.invoke(source, request, index, true, trace, deadline)
            if (traceRows > 0) {
                assertEquals(record.path("trajectory").number("rowsWritten"), trace.readLines().size.toLong())
            }
            return record
        } finally {
            out.deleteRecursively()
        }
    }

    private fun JsonObject.path(vararg keys: String): JsonObject =
        keys.fold(this) { node, key -> node.getValue(key).jsonObject }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.flag(key: String): Boolean = getValue(key).jsonPrimitive.boolean

    private fun JsonObject.number(key: String): Long = getValue(key).jsonPrimitive.long

    private companion object {
        const val DEADLINE_POLLS = 40

        fun pigeonhole(pigeons: Int, holes: Int): ByteArray = buildString {
            fun variable(pigeon: Int, hole: Int) = pigeon * holes + hole + 1
            val clauses = buildList {
                for (pigeon in 0 until pigeons) add((0 until holes).map { variable(pigeon, it) })
                for (hole in 0 until holes) for (a in 0 until pigeons) for (b in a + 1 until pigeons) {
                    add(listOf(-variable(a, hole), -variable(b, hole)))
                }
            }
            append("p cnf ${pigeons * holes} ${clauses.size}\n")
            clauses.forEach { append(it.joinToString(" ", postfix = " 0\n")) }
        }.toByteArray()
    }
}
