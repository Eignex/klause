package com.eignex.klause.bench.lab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FixedWorkComparisonTest {
    private val declaration = FixedWorkComparison.Declaration("control", "candidate")

    @Test
    fun `a valid pair reports candidate over control ratios`() {
        val rows = compare(control(), candidate())

        assertEquals("matched", rows.first().text("class"))
        assertEquals(CANDIDATE_ELAPSED.toDouble() / CONTROL_ELAPSED, rows.first().number("elapsedRatio"))
        assertEquals(CANDIDATE_CPU.toDouble() / CONTROL_CPU, rows.first().number("cpuRatio"))
        assertEquals(CANDIDATE_ELAPSED.toDouble() / CONTROL_ELAPSED, rows.last().number("matchedMedianElapsedRatio"))
    }

    @Test
    fun `pairs that reach the limit compare limit point times along one prefix`() {
        val limit = mapOf(
            "record.details.measured.stop" to JsonPrimitive("limit"),
            "record.details.measured.limitReached" to JsonPrimitive(true),
            "record.details.measured.limitPoint" to Json.parseToJsonElement(
                """{"reached":true,"elapsedNs":100,"threadCpuNs":80}""",
            ),
            "record.details.measured.trajectory.prefix" to Json.parseToJsonElement("""{"digest":"p"}"""),
        )

        val rows = compare(
            control().with(limit),
            candidate().with(limit).with("record.details.measured.limitPoint.elapsedNs", JsonPrimitive(90)),
        )

        assertEquals("matched", rows.first().text("class"))
        assertEquals(0.9, rows.first().number("elapsedRatio"))
        assertEquals(1.0, rows.first().number("cpuRatio"))
    }

    @Test
    fun `completed arms with different digests report the first different checkpoint and wall ratio only`() {
        val rows = compare(
            control(),
            candidate().with(
                mapOf(
                    "record.details.measured.trajectory.digest" to JsonPrimitive("other"),
                    "record.details.measured.trajectory.checkpoints.1.digest" to JsonPrimitive("other"),
                ),
            ),
        )

        assertEquals("completed-diverged", rows.first().text("class"))
        assertEquals(2048L, rows.first().number("firstDifferentCheckpointEvents")?.toLong())
        assertEquals(1024L, rows.first().number("lastCommonCheckpointEvents")?.toLong())
        assertFalse("cpuRatio" in rows.first())
    }

    @ParameterizedTest
    @CsvSource(
        "host, node-b, arms ran on 2 machines",
        "record.labExecution.receiptSha256, bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb, " +
            "arms come from 2 worker attempts",
        "record.labExecution.frame.release, other, arms ran under 2 execution frames",
        "record.details.identity.revision, ffffffffffffffffffffffffffffffffffffffff, " +
            "revision differs: eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee vs ffffffffffffffffffffffffffffffffffffffff",
        "record.details.runtime.inputArguments.0, -Xmx1024m, runtime differs: 2 values",
        "$PARAMETERS.heap-mib, 1024, undeclared parameter difference heap-mib",
        "record.details.measured.sourceSha256, $OTHER_SOURCE, " +
            "candidate source hash disagrees within the arm: $SOURCE vs $OTHER_SOURCE",
    )
    fun `contradicting pairing evidence invalidates the row`(path: String, value: String, reason: String) {
        val row = compare(control(), candidate().with(path, JsonPrimitive(value))).first()

        assertEquals("invalid", row.text("class"))
        assertTrue(reason in row.reasons(), "${row.reasons()}")
        assertFalse("elapsedRatio" in row)
    }

    @ParameterizedTest
    @CsvSource(
        "$PARAMETERS.sha256 problem.benchCase.inputs.0.sha256 record.details.identity.declaredSha256 " +
            "record.details.identity.plainSha256 record.details.measured.sourceSha256, $OTHER_SOURCE, " +
            "source hash differs: $SOURCE vs $OTHER_SOURCE",
        "seed $PARAMETERS.seed problem.benchCase.attributes.seed record.details.measured.seed, 3, seed differs: 1 vs 3",
        "$PARAMETERS.limit record.details.limit record.details.measured.limit, 10000, " +
            "work limit differs: 20000 vs 10000",
        "$PARAMETERS.unit record.details.unit record.details.measured.unit, work, work unit differs: conflicts vs work",
        "$PARAMETERS.recipe record.details.recipe record.details.measured.recipe, other, " +
            "recipe differs: clausal-balanced vs other",
    )
    fun `arms with different source or controls are invalid`(fields: String, value: String, reason: String) {
        val row = compare(control(), candidate().with(fields.split(' ').associateWith { JsonPrimitive(value) })).first()

        assertEquals("invalid", row.text("class"))
        assertEquals(listOf(reason), row.reasons())
    }

    @Test
    fun `a declared revision difference keeps the pair valid`() {
        val revised = candidate().with("record.details.identity.revision", JsonPrimitive("f".repeat(40)))
            .with("record.details.identity.engine.klause-jvm-SNAPSHOT.jar", JsonPrimitive("f".repeat(64)))

        val row = FixedWorkComparison.compare(
            sequenceOf(control(), revised),
            declaration.copy(allowed = setOf("revision")),
        ).first()

        assertEquals("matched", row.text("class"))
    }

    @Test
    fun `duplicate control records invalidate the row instead of choosing one`() {
        val row = compare(control(), control().with("index", JsonPrimitive(2)), candidate()).first()

        assertEquals("invalid", row.text("class"))
        assertEquals(listOf("control arm control retained 2 times"), row.reasons())
        assertEquals(JsonNull, row["control"])
    }

    @Test
    fun `rejected rows keep their trajectory diagnostic`() {
        val row = compare(control(), candidate().with("host", JsonPrimitive("node-b"))).first()

        assertEquals("matched", row.text("trajectory"))
    }

    @ParameterizedTest
    @CsvSource(
        "status, FAILED, candidate case ended FAILED",
        "record.details.process.emergencyKilled, true, candidate reached the process ceiling",
        "record.details.measured.stop, deadline, candidate stopped at deadline",
        "record.details.measured.interrupted, true, candidate interrupted",
        "record.details.comparable, false, candidate not comparable",
        "record.details.measured.result, Paused, candidate verdict Paused",
    )
    fun `an arm without completed comparable work makes the row incomparable`(
        path: String,
        value: String,
        reason: String,
    ) {
        val parsed = value.toBooleanStrictOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(value)

        val row = compare(control(), candidate().with(path, parsed)).first()

        assertEquals("incomparable", row.text("class"))
        assertTrue(reason in row.reasons(), "${row.reasons()}")
        assertNull(row.text("trajectory"))
    }

    @ParameterizedTest
    @CsvSource(
        "record.details.measured.trajectory.digest, control trajectory digest unavailable",
        "record.labExecution, control has no accepted worker receipt",
        "record.details.identity.revision, control revision unavailable",
        "record.details.runtime, control runtime unavailable",
    )
    fun `missing evidence on both arms is unverifiable rather than matched`(path: String, reason: String) {
        val rows = compare(control().with(path, JsonNull), candidate().with(path, JsonNull))

        assertEquals("unverifiable", rows.first().text("class"))
        assertTrue(reason in rows.first().reasons(), "${rows.first().reasons()}")
        assertNull(rows.last().text("matchedMedianElapsedRatio"))
        assertEquals(1L, rows.last().number("unverifiable")?.toLong())
    }

    @Test
    fun `a row missing an arm is incomplete`() {
        val row = compare(control()).first()

        assertEquals("incomplete", row.text("class"))
        assertEquals(listOf("candidate arm candidate not retained"), row.reasons())
    }

    @Test
    fun `declarations refuse differences that would change the pair identity`() {
        assertFailsWith<IllegalArgumentException> {
            FixedWorkComparison.Declaration.parse(listOf("control", "candidate", "--allow", "limit"))
        }
    }

    private fun compare(vararg cases: JsonObject) = FixedWorkComparison.compare(cases.asSequence(), declaration)

    private fun control() = case("control", 0, CONTROL_ELAPSED, CONTROL_CPU)

    private fun candidate() = case("candidate", 1, CANDIDATE_ELAPSED, CANDIDATE_CPU)

    private fun case(arm: String, index: Int, elapsedNs: Long, cpuNs: Long): JsonObject = Json.parseToJsonElement(
        """{"index":$index,"status":"DONE","arm":"$arm","seed":1,"repeat":0,"host":"node-a",
        "problem":{"benchCase":{"id":"fixed-work-sat/$KEY",
          "parameters":{"unit":"conflicts","limit":"20000","deadline-seconds":"30","measurement":"cold",
            "source-path":"$PATH","sha256":"$SOURCE","seed":"1"},
          "resources":{"cpus":1,"memoryMiB":3072,"timeoutSeconds":150},
          "inputs":[{"path":"$PATH","root":"CORPUS","role":"model","sha256":"$SOURCE"}],
          "attributes":{"comparisonKey":"$KEY","seed":"1"}}},
        "record":{"status":"success","details":{"schema":"fixed-work-sat-v1","measurement":"cold","unit":"conflicts",
          "limit":20000,"deadlineSeconds":30,"recipe":"clausal-balanced",
          "identity":{"revision":"${"e".repeat(40)}","engine":{"klause-jvm-SNAPSHOT.jar":"${"d".repeat(64)}"},
            "harnessFingerprint":"h","libraryFingerprint":"l","sourcePath":"$PATH","declaredSha256":"$SOURCE",
            "plainSha256":"$SOURCE"},
          "runtime":{"javaVersion":"25","inputArguments":["-Xmx2048m"],"os":"Linux","arch":"x86_64"},
          "process":{"exit":0,"emergencyKilled":false},"comparable":true,"interrupted":false,"warmups":[],
          "measured":{"role":"measured","index":0,"jvmState":"cold","seed":1,"recipe":"clausal-balanced",
            "unit":"conflicts","limit":20000,"deadlineMillis":30000,"engine":{"kind":"resumable-backtrack"},
            "sourceSha256":"$SOURCE","measured":{"elapsedNs":$elapsedNs,"threadCpuNs":$cpuNs},
            "limitPoint":{"reached":false,"elapsedNs":null,"threadCpuNs":null},
            "stop":"verdict","limitReached":false,"interrupted":false,"comparable":true,"result":"Sat",
            "trajectory":{"digest":"749b09559072c3536dbe75860a3fe125","prefix":null,"rowLimit":65536,
              "checkpoints":[{"events":1024,"digest":"9a00e402cd3fc2d633a7d9d2f27515ce"},
                {"events":2048,"digest":"0c1d"}]}}},
          "labExecution":{"policy":"worker-receipt-v1","receiptSha256":"${"a".repeat(64)}",
            "frame":{"iterationCases":2,"release":"r"}}}}""",
    ).jsonObject

    private fun JsonObject.with(changes: Map<String, JsonElement>): JsonObject =
        changes.entries.fold(this) { case, (path, value) -> case.with(path, value) }

    private fun JsonObject.with(path: String, value: JsonElement): JsonObject = replace(this, path, value).jsonObject

    private fun replace(node: JsonElement, path: String, value: JsonElement): JsonElement {
        val key = KEYS.firstOrNull { path.startsWith("$it.") || path == it } ?: path.substringBefore('.')
        val rest = path.removePrefix(key).removePrefix(".")
        return when (node) {
            is JsonArray -> JsonArray(
                node.mapIndexed { index, item ->
                    if (index != key.toInt()) {
                        item
                    } else if (rest.isEmpty()) {
                        value
                    } else {
                        replace(item, rest, value)
                    }
                },
            )

            else -> {
                val fields = node.jsonObject
                val replaced = if (rest.isEmpty()) value else replace(fields.getValue(key), rest, value)
                JsonObject(fields + (key to replaced))
            }
        }
    }

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }
        ?.content

    private fun JsonObject.number(key: String): Double? = text(key)?.toDoubleOrNull()

    private fun JsonObject.reasons(): List<String> = getValue("reasons").jsonArray.map { it.jsonPrimitive.content }

    private companion object {
        const val KEY = "cold/conflicts-20000/uf250-0100.cnf/seed-1"
        const val PATH = "satlib/uf250-0100.cnf"
        const val SOURCE = "d518bb7da769344c922fcf6d98cdb3c95f90e407a7fca37b4ca8514346e03ff9"
        const val OTHER_SOURCE = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val PARAMETERS = "problem.benchCase.parameters"
        const val CONTROL_ELAPSED = 2_321_531_375L
        const val CONTROL_CPU = 1_761_852_000L
        const val CANDIDATE_ELAPSED = 2_039_559_333L
        const val CANDIDATE_CPU = 1_911_107_000L
        val KEYS = listOf("klause-jvm-SNAPSHOT.jar")
    }
}
