package com.eignex.klause.bench.lab

import com.eignex.klause.bench.metric.sha256
import com.eignex.lab.bench.BenchCase
import com.eignex.lab.bench.BenchProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PairedAuditTest {
    private val declaration = PairedAudit.Declaration(listOf("base", "candidate"))

    @Test
    fun `complete rows may run on different machines`() {
        val report = audit(row(seed = 1, host = "node-a") + row(seed = 2, host = "node-b", first = 2))

        assertEquals(2, report.complete)
        assertTrue(report.accepted)
    }

    @Test
    fun `a row without a declared arm is missing`() {
        val report = audit(row().take(1))

        assertEquals(1, report.missing)
        assertEquals(listOf("candidate not retained"), report.rows.single().reasons)
        assertFalse(report.accepted)
    }

    @Test
    fun `a failed arm invalidates its row`() {
        val (base, candidate) = row()

        val report = audit(listOf(base, candidate.with("status", "FAILED")))

        assertEquals(listOf("candidate ended FAILED"), report.rows.single().reasons)
        assertEquals(RowState.INVALID, report.rows.single().state)
    }

    @Test
    fun `arms from different worker attempts invalidate the row`() {
        val (base, candidate) = row()

        val report = audit(listOf(base, candidate.with("record.labExecution.receiptSha256", "b".repeat(64))))

        assertEquals(RowState.INVALID, report.rows.single().state)
        assertTrue("arms come from 2 worker attempts" in report.rows.single().reasons)
    }

    @ParameterizedTest
    @CsvSource(
        "record.sourceHashes.model, plain source identity differs between arms",
        "record.comparisonControls.runtimeFingerprint, undeclared runtime difference",
        "record.gitSha, undeclared revision difference",
        "problem.benchCase.parameters.presolve, undeclared parameter difference presolve",
    )
    fun `undeclared drift between arms invalidates the row`(path: String, reason: String) {
        val (base, candidate) = row()

        val report = audit(listOf(base, candidate.with(path, "drifted")))

        assertTrue(reason in report.rows.single().reasons, report.rows.single().reasons.toString())
        assertFalse(report.accepted)
    }

    @ParameterizedTest
    @CsvSource(
        "record.comparisonControls.runtimeFingerprint, runtime, ''",
        "problem.benchCase.parameters.presolve, '', presolve",
    )
    fun `declared differences keep the row complete`(path: String, allowed: String, treatment: String) {
        val (base, candidate) = row()
        val declared = declaration.copy(
            allowed = setOfNotNull(allowed.ifBlank { null }),
            treatments = setOfNotNull(treatment.ifBlank { null }),
        )

        val report = audit(listOf(base, candidate.with(path, "drifted")), declared)

        assertEquals(RowState.COMPLETE, report.rows.single().state, report.rows.single().reasons.toString())
    }

    @Test
    fun `plain source drift between rows with the same inputs invalidates them`() {
        val drifted = row(seed = 2, first = 2).map { it.with("record.sourceHashes.model", "drifted") }

        val report = audit(row() + drifted)

        assertEquals(2, report.invalid)
    }

    @Test
    fun `a case retained twice is rejected and costed once`() {
        val rows = row()

        val report = audit(rows + rows.first())

        assertEquals(RowState.INVALID, report.rows.single().state)
        assertEquals(2, report.retainedCosts.cases)
        assertEquals(200.0, report.retainedCosts.solverElapsedMs.sum)
        assertFalse(report.accepted)
    }

    @Test
    fun `an arm without a terminal verdict censors an accepted row`() {
        val (base, candidate) = row()

        val report = audit(listOf(base, candidate.with("record.proven", false)))

        assertEquals(listOf("candidate"), report.rows.single().censoredArms)
        assertEquals(1, report.censored)
        assertTrue(report.accepted)
    }

    @Test
    fun `checked witnesses and native proofs are counted apart`() {
        val (base, candidate) = row()

        val report = audit(listOf(base, candidate.with("record.labCheck.witnessSha256", "c".repeat(64))))

        assertEquals(mapOf("valid" to 1), report.outcomes.getValue("base").checkedWitnesses)
        assertEquals(mapOf("valid-unbound" to 1), report.outcomes.getValue("candidate").checkedWitnesses)
        assertEquals(mapOf("proven-optimum" to 1), report.outcomes.getValue("base").native)
        assertEquals(1, report.outcomes.getValue("base").nativeProofsWithoutIndependentProof)
    }

    @Test
    fun `nested cost scopes are reported separately`() {
        val costs = audit(row()).costs.getValue("base")

        assertEquals(
            listOf(100.0, 2.0, 2.5, 5.0),
            listOf(
                costs.solverElapsedMs.sum,
                costs.cliCpuSeconds.sum,
                costs.adapterCpuSeconds.sum,
                costs.checkerElapsedMs.sum,
            ),
        )
    }

    @Test
    fun `failed attempts keep their costs and committed rows must match accepted rows`() {
        val execution = buildJsonArray {
            add(attempt("lost", committed = 0, elapsedMs = 700))
            add(attempt("kept", committed = 2, elapsedMs = 300))
        }

        val report = audit(row(), execution = execution)
        val solve = requireNotNull(report.attempts).classes.single()

        assertEquals(listOf(1, 700.0), listOf(solve.uncommitted, solve.uncommittedElapsedMs.sum))
        assertEquals(listOf("solve attempts committed 2 rows, 1 rows ran every arm"), report.jobErrors)
    }

    @Test
    fun `request configs declare arms treatments and revision freedom`() {
        val request = Json.parseToJsonElement(
            """{"configs":[
            {"label":"base","ref":"a","param.param.cuts":"false"},
            {"label":"candidate","ref":"b","param.param.cuts":"true"}]}""",
        ).jsonObject

        val declared = DeclaredExperiment.declaration(request)

        assertEquals(
            listOf(listOf("base", "candidate"), setOf("param.cuts"), setOf("revision")),
            listOf(declared.arms, declared.treatments, declared.allowed),
        )
    }

    @Test
    fun `declared treatments revisions and resolved defaults verify the experiment`() {
        val cases = treated(1) + treated(2, first = 2)

        val report = audit(cases, declared(cases))

        assertTrue(report.accepted, report.rows.flatMap { it.reasons }.toString())
        assertTrue(report.declared.verified, report.declared.gaps.toString())
        assertEquals(mapOf("presolve" to null), report.declared.arms.getValue("base").parameters)
    }

    @ParameterizedTest
    @CsvSource(
        "on, '', $COMMIT, 'base treatment presolve is on, declared unset'",
        "'', '', $COMMIT, 'candidate treatment presolve is unset, declared on'",
        "'', on, $OTHER_COMMIT, 'candidate ran revision $OTHER_COMMIT, declared $COMMIT'",
    )
    fun `a row that ran another treatment or revision than declared is rejected`(
        base: String,
        candidate: String,
        revision: String,
        reason: String,
    ) {
        val (first, second) = treated(1)
        val cases = listOf(
            first.with("problem.benchCase.parameters.presolve", base.ifEmpty { null }),
            second.with("problem.benchCase.parameters.presolve", candidate.ifEmpty { null })
                .with("record.gitSha", revision),
        )

        val report = audit(cases, declared(treated(1)))

        assertTrue(reason in report.rows.single().reasons, report.rows.single().reasons.toString())
        assertFalse(report.accepted)
    }

    @Test
    fun `a declared row without any retained case is missing`() {
        val report = audit(treated(1), declared(treated(1) + treated(2, first = 2)))

        assertEquals(1, report.missing)
        assertEquals(listOf("suite/p/2/0 seed 2 repeat 0/0"), report.declared.cohort?.absent)
        assertFalse(report.accepted)
    }

    @Test
    fun `a substituted row is rejected although the row count matches`() {
        val declared = declared(treated(1) + treated(2, first = 2)).copy(rows = 2)

        val report = audit(treated(1) + treated(3, first = 2), declared)

        assertEquals(listOf(1, 1), listOf(report.missing, report.invalid))
        assertTrue("base case suite/p/3/0 seed 3 repeat 0/0 is not in the declared cohort" in report.rows[1].reasons)
    }

    @Test
    fun `retained inputs that differ from the resolved plan are rejected`() {
        val cases = treated(1).map { it.with("problem.benchCase.inputs", buildJsonArray {}) }

        val report = audit(cases, declared(treated(1)))

        assertTrue("candidate inputs differ from its resolved plan" in report.rows.single().reasons)
    }

    @Test
    fun `resolved plans swapped between arms contradict the request`() {
        val (first, second) = treated(1)

        val swapped = declared(listOf(second.with("arm", "base"), first.with("arm", "candidate")))

        val report = audit(listOf(first, second), swapped)

        assertTrue("resolved plan of candidate sets presolve to unset, the request declares on" in report.jobErrors)
    }

    @Test
    fun `treatment values stay unverified without the executed cases`() {
        val report = audit(treated(1), declared(treated(1)), executed = false)

        assertTrue(report.accepted)
        assertEquals(
            listOf("executed cases unavailable, so per-arm treatment values are unverified"),
            report.declared.gaps,
        )
    }

    @ParameterizedTest
    @CsvSource("'', false", "$COMMIT, true")
    fun `a branch ref needs a pinned commit before the experiment is verified`(pinned: String, verified: Boolean) {
        val cases = treated(1)
        val revisions = if (pinned.isEmpty()) emptyMap() else mapOf("base" to pinned, "candidate" to pinned)

        val report = audit(cases, declared(cases, ref = "main", revisions = revisions))

        assertTrue(report.accepted)
        assertEquals(verified, report.declared.verified, report.declared.gaps.toString())
    }

    private fun declared(
        planned: List<JsonObject>,
        ref: String = COMMIT,
        revisions: Map<String, String> = emptyMap(),
    ): PairedAudit.Declaration {
        val request = Json.parseToJsonElement(
            """{"base":{"ref":"$ref"},"repeats":1,
            "configs":[{"label":"base"},{"label":"candidate","param.presolve":"on"}]}""",
        ).jsonObject
        val plans = declaration.arms.withIndex().associate { (index, arm) ->
            index to planned.filter { it["arm"] == JsonPrimitive(arm) }.map(::benchCase)
        }
        return DeclaredExperiment.declaration(request, plans, revisions)
    }

    private fun treated(seed: Int, first: Int = 0): List<JsonObject> {
        val (base, candidate) = row(seed = seed, first = first)
        return listOf(base.with("problem.benchCase.parameters.presolve", null), candidate)
            .map { it.with("record.gitSha", COMMIT) }
    }

    private fun audit(
        cases: List<JsonObject>,
        declared: PairedAudit.Declaration = declaration,
        execution: JsonArray? = null,
        executed: Boolean = true,
    ) = PairedAudit.audit(
        cases.joinToString("\n").reader(),
        declared,
        execution,
        executed = cases.associate { it.getValue("index").jsonPrimitive.int to benchCase(it) }.takeIf { executed },
    )

    private fun benchCase(case: JsonObject): BenchCase = BenchProtocol.json.decodeFromJsonElement(
        BenchCase.serializer(),
        case.getValue("problem").jsonObject.getValue("benchCase"),
    )

    private fun row(seed: Int = 1, host: String = "node-a", first: Int = 0) =
        declaration.arms.mapIndexed { offset, arm -> case(first + offset, arm, seed, host) }

    private fun attempt(id: String, committed: Int, elapsedMs: Long) = buildJsonObject {
        put(
            "session",
            buildJsonObject {
                put("id", id)
                put("executionClass", "solve")
            },
        )
        put("generation", 1)
        put("committed", committed)
        put(
            "resources",
            buildJsonObject {
                put("elapsedMs", elapsedMs)
                put("scope", "worker-cumulative-lower-bound")
            },
        )
    }

    private fun case(index: Int, arm: String, seed: Int, host: String): JsonObject {
        val witness = "v x=1"
        val controls = mapOf(
            "policy" to "matched-source-v1", "series" to "s", "solverSeed" to "$seed",
            "compilerSeed" to "1", "solverBudgetMs" to "1000", "compilerTimeoutSeconds" to "60",
            "caseTimeoutSeconds" to "60", "cpus" to "1", "memoryMiB" to "4096", "heap-mib" to "2560", "gc" to "default",
            "debug" to "false", "measurement" to "resources", "exact" to "false", "complete-assignment" to "false",
            "deadlinePolicy" to "cli-inclusive-v1", "runtimeFingerprint" to "r", "harnessFingerprint" to "h",
            "libraryFingerprint" to "l", "inputDigest" to "i",
        )
        return buildJsonObject {
            put("index", index)
            put("status", "DONE")
            put("arm", arm)
            put("seed", seed)
            put("repeat", 0)
            put("host", host)
            put(
                "problem",
                buildJsonObject {
                    put("problem", "suite/p")
                    put(
                        "benchCase",
                        Json.parseToJsonElement(
                            """{"id":"suite/p/$seed","parameters":{"presolve":"on"},
                    "inputs":[{"path":"suite/p.mps.zst","root":"CORPUS","role":"model","sha256":"${"1".repeat(64)}"}],
                    "attributes":{"pairKey":"suite/p/$seed/0","seed":"$seed","repeat":"0"}}""",
                        ),
                    )
                },
            )
            put(
                "record",
                buildJsonObject {
                    put("problem", "p")
                    put("solver", "klause")
                    put("engine", "cp")
                    put("processors", 1)
                    put("seed", seed)
                    put("budgetMs", 1000)
                    put("kind", "optimize")
                    put("maximize", false)
                    put("feasible", true)
                    put("objective", 1.0)
                    put("timeToBestMs", 10)
                    put("proven", true)
                    put("gitSha", "g")
                    put("timestamp", "t")
                    put("command", "c")
                    put("elapsedMs", 100)
                    put("finalWitness", witness)
                    put("sourceHashes", buildJsonObject { put("model", "2".repeat(64)) })
                    put("comparisonControls", JsonObject(controls.mapValues { JsonPrimitive(it.value) }))
                    put(
                        "labExecution",
                        buildJsonObject {
                            put("receiptSha256", "a".repeat(64))
                            put(
                                "frame",
                                buildJsonObject {
                                    put("enforcement", "cgroup-v2-hard")
                                    put("iterationCases", 2)
                                },
                            )
                        },
                    )
                    put(
                        "labCheck",
                        buildJsonObject {
                            put("state", "valid")
                            put("policy", "checker")
                            put("elapsedMs", 5)
                            put("witnessSha256", sha256(witness.toByteArray()))
                        },
                    )
                    put(
                        "labBench",
                        Json.parseToJsonElement(
                            """{"metrics":[{"name":"userSeconds","value":1.5},
                    {"name":"systemSeconds","value":0.5}]}""",
                        ),
                    )
                    put(
                        "labAdapterResources",
                        Json.parseToJsonElement(
                            """{"state":"complete",
                    "receipt":{"resources":{"userSeconds":"2.0","systemSeconds":"0.5"}}}""",
                        ),
                    )
                },
            )
        }
    }

    private fun JsonObject.with(path: String, value: Any?): JsonObject {
        val key = path.substringBefore('.')
        val replaced: JsonElement = if ('.' in path) {
            getValue(key).jsonObject.with(path.substringAfter('.'), value)
        } else {
            when (value) {
                null -> return JsonObject(this - key)
                is Boolean -> JsonPrimitive(value)
                is JsonElement -> value
                else -> JsonPrimitive(value.toString())
            }
        }
        return JsonObject(this + (key to replaced))
    }

    private companion object {
        const val COMMIT = "0123456789abcdef0123456789abcdef01234567"
        const val OTHER_COMMIT = "89abcdef0123456789abcdef0123456789abcdef"
    }
}
