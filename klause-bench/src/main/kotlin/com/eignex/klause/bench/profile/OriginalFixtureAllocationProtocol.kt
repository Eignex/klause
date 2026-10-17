package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.sha256
import kotlinx.serialization.Serializable

internal object OriginalFixtureAllocationProtocol {
    const val PAIRS = 3
    const val ROUNDS = 21
    const val LATE_FROM = 11
    val fixtures = listOf(
        OriginalAllocationFixture("matching",
            "alldifferent matching deductions should be implied by their reasons under carved holes",
            "a7cda6e9ab2b04b9e41c59fb78fbb5e69c92726f5ce9e2ad93dee56fc49b3218", 300),
        OriginalAllocationFixture("except-zero",
            "alldifferent except zero deductions should be implied by their reasons under carved holes",
            "60c013c47448a89ef6de1df51510870893ae32a8cc69c2c8373fe5aa13e170d6", 150),
    )

    fun sourceBody(source: String, fixture: OriginalAllocationFixture): String {
        val header = "    fun `${fixture.method}`() {"
        val start = source.indexOf(header)
        require(start >= 0 && source.lastIndexOf(header) == start) { "Original fixture method is missing or repeated" }
        val end = source.indexOf("\n    }", start)
        require(end > start) { "Original fixture body is incomplete" }
        val body = source.substring(start, end + "\n    }".length)
        require(sha256(body.toByteArray()) == fixture.sourceSha256) { "Original seeded fixture source differs" }
        return body
    }

    fun arms(pair: Int): List<String> {
        require(pair in 0 until PAIRS)
        return if (pair % 2 == 0) listOf("control", "candidate") else listOf("candidate", "control")
    }

    fun measurements(stdout: String, fixture: String): List<OriginalAllocationMeasurement> {
        require(fixtures.any { it.key == fixture })
        val lines = stdout.lineSequence().filter { it.isNotBlank() }.toList()
        val counts = mapOf("CLASS" to 2, "FIXTURE_CLASS" to 1, "FIXTURE" to 1, "JAR" to 1,
            "VM" to 1, "PID" to 1, "CPU" to 1, "MEASURE" to ROUNDS, "COMPLETED" to 1)
        require(lines.groupingBy { it.substringBefore('\t') }.eachCount() == counts) {
            "Original fixture output is incomplete, repeated or unexpected"
        }
        require(lines.last() == "COMPLETED\t$fixture")
        val rows = lines.filter { it.startsWith("MEASURE\t") }.map { line ->
            val fields = line.split('\t')
            require(fields.size == 4)
            OriginalAllocationMeasurement(fields[1].toInt(), fields[2].toLong(), fields[3].toLong()).also {
                require(it.elapsedNanos >= 0 && it.threadAllocatedBytes >= 0)
            }
        }
        require(rows.map { it.round } == (0 until ROUNDS).toList()) { "Original measurement rounds differ" }
        return rows
    }
}

@Serializable
internal data class OriginalAllocationFixture(
    val key: String,
    val method: String,
    val sourceSha256: String,
    val sourceRepeatLiteral: Int,
)

@Serializable
internal data class OriginalAllocationMeasurement(
    val round: Int,
    val elapsedNanos: Long,
    val threadAllocatedBytes: Long,
    val inPredeclaredLateWindow: Boolean = round >= OriginalFixtureAllocationProtocol.LATE_FROM,
)
