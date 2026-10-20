package com.eignex.klause.bench.profile

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JvmSourceCompilerReviewTest {
    @ParameterizedTest
    @ValueSource(strings = ["owned", "external", "empty"])
    fun `failure metadata identifies only an owned reader source location`(variant: String) {
        val failure = IllegalArgumentException("retained failure")
        val owned = StackTraceElement(
            "com.eignex.klause.bench.profile.JvmSavedTables", "project", "JvmSavedTables.kt", 42,
        )
        val external = StackTraceElement("external.Library", "parse", "Other.kt", 7)
        failure.stackTrace = when (variant) {
            "owned" -> arrayOf(external, owned)
            "external" -> arrayOf(external)
            else -> emptyArray()
        }

        val actual = JvmSourceCompilerReview.failureMetadata(failure)

        assertEquals(JsonPrimitive("java.lang.IllegalArgumentException"), actual["type"])
        assertEquals(JsonPrimitive("retained failure"), actual["message"])
        assertEquals(
            if (variant == "owned") buildJsonObject {
                put("class", owned.className)
                put("method", owned.methodName)
                put("file", owned.fileName)
                put("line", owned.lineNumber)
            } else JsonNull,
            actual["ownedReaderLocation"],
        )
    }

    @Test
    fun `the reader stage retains the latest owned phase`(@TempDir out: File) {
        JvmSourceCompilerReview.recordStage(out, "catalog")

        JvmSourceCompilerReview.recordStage(out, "candidate-links", 1)

        val actual = JvmSavedJson.read(
            File(out, "source-compiler-links-stage.json"), JvmSavedJson.Kind.CATALOG_METADATA,
        ).jsonObject
        assertEquals(JsonPrimitive("source-compiler-links-stage-v1"), actual["schema"])
        assertEquals(JsonPrimitive("candidate-links"), actual["phase"])
        assertEquals(JsonPrimitive(1), actual["arm"])
        assertEquals(
            JsonPrimitive("Owned reader lifecycle stage; no original data or savings proof"), actual["scope"],
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["phase", "arm"])
    fun `unknown diagnostic fields cannot replace a valid owned stage`(variant: String, @TempDir out: File) {
        JvmSourceCompilerReview.recordStage(out, "catalog")
        val file = File(out, "source-compiler-links-stage.json")
        val original = file.readText()

        assertFailsWith<IllegalArgumentException> {
            JvmSourceCompilerReview.recordStage(
                out, if (variant == "phase") "original-input-content" else "candidate-links",
                if (variant == "arm") 2 else null,
            )
        }

        assertEquals(original, file.readText())
    }

    @ParameterizedTest
    @ValueSource(strings = ["complete", "missing-end", "late-class", "wrong-target"])
    fun `a whole source audit requires its final obligations after every original class`(
        variant: String,
        @TempDir out: File,
    ) {
        val targets = JsonArray(listOf(buildJsonObject { put("unknown", "retained") }))
        val context = buildJsonObject {
            put("bindingInputId", "binding")
            put("studySourceHead", "head")
            put("report", "audit.jsonl.gz")
            put("allOriginalClassRowsReviewed", 1)
            put("targetDispositions", targets)
        }
        val audit = JvmSavedReport(File(out, "audit.jsonl.gz"), JvmSavedReportBudget())
        audit.write(
            buildJsonObject {
            put("record", "schema")
            put("encoding", "source-formal-descriptor-audit-v3")
            put("bindingInputId", "binding")
            put("studySourceHead", "head")
        }
        )
        audit.write(
            buildJsonObject {
            put("record", "formal-inventory-reference")
            put("report", "source-formal-descriptor-coverage.jsonl.gz")
        }
        )
        val row = buildJsonObject {
            put("record", "class-audit")
            put("originalSyntaxClassOrdinal", 0)
            put("internalName", "sample/Cold")
            put("originalClassOrigin", JsonObject(emptyMap()))
            put("originalMethodIndexOrder", JsonArray(emptyList()))
            put("noFormalAlternativeMethodIndexes", JsonArray(emptyList()))
            put("allOriginalMethodFormalChecks", JsonArray(emptyList()))
        }
        audit.write(row)
        if (variant != "missing-end") {
            audit.write(
                buildJsonObject {
            put("record", "source-obligations")
            put("targets", if (variant == "wrong-target") JsonArray(emptyList()) else targets)
        }
            )
        }
        if (variant == "late-class") audit.write(row)
        val formal = JvmSavedReport(File(out, "source-formal-descriptor-coverage.jsonl.gz"), JvmSavedReportBudget())
        formal.write(buildJsonObject { put("record", "schema") })
        val completion = buildJsonObject { put("reports", JsonArray(listOf(audit.verify(), formal.verify()))) }
        val paths = out.listFiles().orEmpty().filter { it.name.endsWith(".gz") }.associateBy { it.name }
        val descriptor = buildJsonObject { put("completionMember", "completion.json") }
        JvmSourceCompilerIndex(File(out, "index.sqlite")).use { index ->
            if (variant == "complete") {
                JvmSourceCompilerReview.indexAudits(index, descriptor, paths, completion, listOf(context))
            } else {
                assertFailsWith<IllegalArgumentException> {
                    JvmSourceCompilerReview.indexAudits(index, descriptor, paths, completion, listOf(context))
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["complete", "target", "context", "input", "semantic", "credit", "catalog"])
    fun `catalog admission requires the whole original source obligation universe`(variant: String) {
        val requirement = JvmSavedJson.parse(
            """{
            "issueTarget":"pick","contexts":{"original":{"originalStudyHead":"head"}},
            "unknown":{"cold":[false,null]}
        }"""
        ).jsonObject
        val requirements = buildJsonObject { put("targets", JsonArray(listOf(requirement))) }
        val items = (0..4).map { index ->
            buildJsonObject {
                put("id", index.toString())
                put("members", JsonArray(emptyList()))
            }
        }
        val names = JsonArray(listOf(JsonPrimitive("pick")))
        val original = buildJsonObject {
            put("schema", 1)
            put("targetNames", names)
            put("signatureInput", items[0])
            put("auditInput", items[1])
            put("bindingInputs", JsonArray(listOf(items[2])))
            put("observationInputs", JsonArray(items.drop(3)))
            put("requirements", buildJsonObject { put("sha256", "f".repeat(64)) })
        }
        val catalog = JsonObject(original + ("descriptorInput" to buildJsonObject { put("id", "descriptor") }))
        val context = buildJsonObject {
            put("bindingInputId", "2")
            put("studySourceHead", "head")
            put("targetDispositions", JsonArray(listOf(buildJsonObject { put("originalRequirement", requirement) })))
        }
        val formalTargets = JsonArray(listOf(buildJsonObject { put("issueTarget", "pick") }))
        val formalContext = JsonObject(context + ("targetDispositions" to formalTargets))
        val completion = buildJsonObject {
            put("completed", true)
            put("allAuthenticatedInputsByteIdenticalAfterReview", true)
            put("allOriginalFormalSignaturesAndUnmatchedDeclarationsRetained", true)
            put("compilerObservationsUsedToSelectSourceScope", false)
            put("wholeOriginalTargetNames", names)
            put(
                "inputArchiveProofs",
                JsonArray(
                    items.map { item ->
                buildJsonObject {
                    put("inputCatalogEntryCopiedVerbatim", item)
                    put("allArchiveMembersAuthenticatedBeforeInterpretation", true)
                }
            }
                )
            )
            put("sourceContextAudits", JsonArray(listOf(context)))
            put("sourceFormalDescriptorDispositions", JsonArray(listOf(formalContext)))
            SOURCE_PROOF_FLAGS.forEach { put(it, false) }
        }
        var candidate = catalog
        var producer = completion
        when (variant) {
            "target" -> candidate = JsonObject(candidate + ("targetNames" to JsonArray(emptyList())))

            "context" -> producer = JsonObject(producer + ("sourceContextAudits" to JsonArray(emptyList())))

            "input" -> producer = JsonObject(producer + ("inputArchiveProofs" to JsonArray(emptyList())))

            "semantic" -> producer = JsonObject(
                producer + (
                    "sourceContextAudits" to JsonArray(
                        listOf(
                JsonObject(context + ("targetDispositions" to JsonArray(emptyList()))),
            )
                    )
                )
            )

            "credit" -> producer = JsonObject(producer + ("acceptedSavingsCredit" to JsonPrimitive(true)))

            "catalog" -> candidate = JsonObject(candidate + ("unknown" to JsonPrimitive("changed")))
        }

        if (variant == "complete") {
            val actual = JvmSourceCompilerReview.validateCatalog(candidate, requirements, original, producer)

            assertEquals(listOf(context), actual)
        } else {
            assertFailsWith<IllegalArgumentException> {
                JvmSourceCompilerReview.validateCatalog(candidate, requirements, original, producer)
            }
        }
    }
}
