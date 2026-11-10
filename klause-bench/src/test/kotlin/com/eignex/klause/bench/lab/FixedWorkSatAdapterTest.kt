package com.eignex.klause.bench.lab

import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.profile.FixedWorkRequest
import com.eignex.klause.bench.profile.FixedWorkSatMeasurement
import com.eignex.klause.bench.profile.FixedWorkUnit
import com.eignex.klause.util.Cancellation
import com.eignex.lab.bench.BenchRequest
import com.eignex.lab.bench.BenchStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FixedWorkSatAdapterTest {
    @Test
    fun `planning pins the declared source and bounds each seed by its emergency deadline`() {
        val cases = FixedWorkSatSuite.plan(BenchRequest(PARAMETERS + ("sources" to "$SHA:$PATH,$OTHER_SHA:$OTHER")))

        assertEquals(listOf("1", "3", "1", "3"), cases.map { it.parameters.getValue("seed") })
        assertTrue(cases.all { it.inputs.single().sha256 == it.parameters["sha256"] &&
            it.inputs.single().path == it.parameters["source-path"] })
        assertTrue(cases.all { it.resources.cpus == 1 && it.resources.timeoutSeconds == 30L + 60L + 60L })
        assertEquals(4, cases.map { it.attributes.getValue("comparisonKey") }.distinct().size)
    }

    @ParameterizedTest
    @ValueSource(strings = ["limit=0", "deadline-seconds=901", "unit=nodes", "warmups=1", "measurement=hot",
        "sources=ABC:x.cnf", "sources=$SHA:../x.cnf", "sources=$SHA:x.cnf,$OTHER_SHA:x.cnf", "seeds=1,1",
        "seed=1", "trace-rows=2000000", "heap-mib=8192", "colour=red"])
    fun `planning rejects controls outside the bounded contract`(override: String) {
        val (key, value) = override.split('=', limit = 2)

        assertFailsWith<IllegalArgumentException> { FixedWorkSatSuite.plan(BenchRequest(PARAMETERS + (key to value))) }
    }

    @Test
    fun `a killed child without a measured record fails and retains completed warmups`() {
        val warmup = invoke(measured = false)

        val result = outcome(listOf(warmup), exit = null, killed = true)

        assertEquals(BenchStatus.FAILED, result.status)
        assertTrue(result.message.orEmpty().contains("emergency process ceiling"))
        assertTrue(result.details.flag("interrupted"))
    }

    @Test
    fun `an interrupted measured invocation reports only completed work metrics`() {
        val record = invoke(measured = true, deadline = Cancellation { true })

        val result = outcome(listOf(record), exit = 0, killed = false)

        assertEquals(BenchStatus.SUCCESS, result.status)
        assertEquals(listOf("completedWork", "decisions", "failures"), result.metrics.map { it.name })
        assertTrue(result.message.orEmpty().contains("not comparable"))
        assertTrue(result.details.flag("interrupted"))
    }

    @Test
    fun `a comparable measured invocation reports its measured and preparation times`() {
        val record = invoke(measured = true)

        val result = outcome(listOf(record), exit = 0, killed = false)

        assertTrue(result.details.flag("comparable"))
        assertTrue(result.metrics.map { it.name }.containsAll(listOf("measuredElapsedMs", "preparationElapsedMs")))
    }

    private fun invoke(measured: Boolean, deadline: Cancellation = Cancellation.Never): JsonObject {
        val request = FixedWorkRequest("unused", sha256(SOURCE), 1, "clausal-balanced", FixedWorkUnit.CONFLICTS,
            10, 10_000, 0, 0)
        return FixedWorkSatMeasurement.invoke(SOURCE, request, 0, measured, null, deadline)
    }

    private fun outcome(records: List<JsonObject>, exit: Int?, killed: Boolean) = FixedWorkSatSuite.outcome(
        "case", FixedWorkSatSuite.Options.parse(PARAMETERS, planned = false), JsonObject(emptyMap()), null, records, exit, killed,
    )

    private fun JsonObject?.flag(key: String): Boolean = requireNotNull(this).getValue(key).jsonPrimitive.boolean

    private companion object {
        const val PATH = "offline/tiny.cnf"
        const val OTHER = "offline/other.cnf"
        const val SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val OTHER_SHA = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val PARAMETERS = mapOf("sources" to "$SHA:$PATH", "seeds" to "1,3", "unit" to "conflicts",
            "limit" to "1000", "deadline-seconds" to "30")
        val SOURCE = "p cnf 2 2\n1 2 0\n-1 2 0\n".toByteArray()
    }
}
