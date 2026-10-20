package com.eignex.klause.bench.profile

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JvmStaticMethodsTest {
    @ParameterizedTest
    @ValueSource(strings = ["complete", "hidden", "context", "census", "proof"])
    fun `static reports require every original row context and report proof`(variant: String, @TempDir out: File) {
        val audit = JvmSavedJson.parse(
            """{
                "record":"class-audit","originalSyntaxClassOrdinal":0,"internalName":"sample/Cold",
                "originalClassOrigin":{},"originalMethodIndexOrder":[0],
                "noFormalAlternativeMethodIndexes":[0],"allOriginalMethodFormalChecks":[]
            }""",
        ).jsonObject
        val hidden = if (variant == "hidden") "[1,99]" else "[0,false,null]"
        val encoded = JvmSavedJson.parse(
            """{
                "encoding":"canonical-json-key-and-string-tables-v1",
                "objectKeys":[["status","internalName","bytecodeClassOrdinal","studySourceHead","syntaxArchiveId",
                               "syntaxClassOrdinal","syntaxLineReportFile","classIdentity","syntaxOrigin","methods","unknown"],
                              ["methodIndex","name","descriptor"],[]],
                "strings":["joined-candidates","sample/Cold","head","syntax","lines.gz","pick","(I)V"],
                "value":[-1,[1,0],[1,1],0,[1,2],[1,3],0,[1,4],[-3],[-3],[0,[-2,0,[1,5],[1,6]]],$hidden]
            }""",
        ).jsonObject
        val report = JvmSavedReport(File(out, "static.gz"), JvmSavedReportBudget())
        report.write(buildJsonObject { put("record", "schema"); put("encoding", "bytecode-source-bindings-v1") })
        report.write(encoded)
        val proofs = mutableListOf(report.verify())
        for (name in listOf("lines.gz", "coverage.gz")) {
            val original = JvmSavedReport(File(out, name), JvmSavedReportBudget())
            original.write(buildJsonObject { put("unknown", name) })
            proofs.add(original.verify())
        }
        val completion = buildJsonObject {
            put("bytecodeClasses", 1)
            put("sourceContextClassRows", if (variant == "census") 2 else 1)
            put("sourceContexts", JsonArray(listOf(buildJsonObject {
                put("studySourceHead", if (variant == "context") "other" else "head")
                put("classContextRows", 1)
                put("syntaxLineReportFile", "lines.gz")
                put("syntaxNodeCoverageFile", "coverage.gz")
            })))
            put("reports", JsonArray(if (variant == "proof") proofs.dropLast(1) else proofs))
        }
        File(out, "completion.json").writeText(completion.toString())
        val paths = out.listFiles().orEmpty().associateBy { it.name }
        val item = buildJsonObject { put("id", "binding"); put("completionMember", "completion.json"); put("reportMember", "static.gz") }

        JvmSourceCompilerIndex(File(out, "index.sqlite")).use { index ->
            index.add("binding", "head", "audit.gz", 2, audit, JsonObject(emptyMap()))

            if (variant == "complete") {
                JvmStaticMethods.read(index, item, paths)
                index.sealStaticMethods()
            } else {
                assertFailsWith<IllegalArgumentException> { JvmStaticMethods.read(index, item, paths) }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `unavailable static classes remain in the whole universe without method credit`(observe: Boolean, @TempDir out: File) {
        val audit = JvmSavedJson.parse(
            """{
                "record":"class-audit","originalSyntaxClassOrdinal":0,"internalName":null,
                "originalClassOrigin":{},"originalMethodIndexOrder":null,
                "noFormalAlternativeMethodIndexes":null,"allOriginalMethodFormalChecks":[]
            }""",
        ).jsonObject
        val static = JvmSavedJson.parse(
            """{"status":"bytecode-metadata-unavailable","studySourceHead":"head","syntaxClassOrdinal":0,"syntaxOrigin":{}}""",
        ).jsonObject
        val candidate = JvmSavedJson.parse(
            """{"studySourceHead":"head","syntaxClassOrdinal":0,"methodIndex":0}""",
        ).jsonObject

        JvmSourceCompilerIndex(File(out, "index.sqlite")).use { index ->
            index.add("binding", "head", "audit.gz", 2, audit, JsonObject(emptyMap()))
            index.addStatic("binding", "static.gz", 1, static, JsonObject(emptyMap()))
            index.sealStaticMethods()

            if (observe) {
                assertFailsWith<IllegalArgumentException> {
                    index.link("binding", JsonPrimitive("sample.Cold.pick()V"), candidate)
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["complete", "descriptor", "name", "record", "class", "archive", "bytecode", "method", "unsealed"])
    fun `generated methods require their complete original static identity`(variant: String, @TempDir out: File) {
        val audit = JvmSavedJson.parse(
            """{
                "record":"class-audit","originalSyntaxClassOrdinal":0,"internalName":"sample/Cold",
                "originalClassOrigin":{"entry":"Cold.class"},"originalMethodIndexOrder":[0,1],
                "noFormalAlternativeMethodIndexes":[0,1],"allOriginalMethodFormalChecks":[]
            }""",
        ).jsonObject
        val static = JvmSavedJson.parse(
            """{
                "status":"joined-candidates","internalName":"sample/Cold","bytecodeClassOrdinal":7,
                "studySourceHead":"head","syntaxArchiveId":"syntax","syntaxClassOrdinal":0,
                "syntaxLineReportFile":"lines.gz","classIdentity":{"sha256":"class"},
                "syntaxOrigin":{"entry":"Cold.class"},
                "methods":[{"methodIndex":0,"name":"pick","descriptor":"(I)V"},
                           {"methodIndex":1,"name":"pick${'$'}default","descriptor":"(J)V"}]
            }""",
        ).jsonObject
        val candidate = buildJsonObject {
            put("bindingRecordOrdinal", if (variant == "record") 2 else 1)
            put("bytecodeClassOrdinal", if (variant == "bytecode") 8 else 7)
            put("studySourceHead", "head")
            put("syntaxArchiveId", if (variant == "archive") "other" else "syntax")
            put("syntaxClassOrdinal", 0)
            put("syntaxLineReportFile", "lines.gz")
            put("originalClassIdentity", if (variant == "class") JsonObject(emptyMap()) else static.getValue("classIdentity"))
            put("originalSyntaxOrigin", static.getValue("syntaxOrigin"))
            put("methodIndex", if (variant == "method") 0 else 1)
        }
        val observed = JsonPrimitive(
            when (variant) {
                "descriptor" -> "sample.Cold.pick${'$'}default(I)V"
                "name" -> "sample.Cold.other(J)V"
                else -> "sample.Cold.pick${'$'}default(J)V"
            },
        )

        JvmSourceCompilerIndex(File(out, "index.sqlite")).use { index ->
            index.add("binding", "head", "audit.gz", 2, audit, JsonObject(emptyMap()))
            index.addStatic("binding", "static.gz", 1, static, JsonObject(emptyMap()))
            if (variant == "unsealed") {
                assertFailsWith<IllegalStateException> { index.link("binding", observed, candidate) }
            } else {
                index.sealStaticMethods()
                if (variant == "complete") {
                    val actual = index.link("binding", observed, candidate)

                    assertEquals(JsonPrimitive("no-formal-alternative"), actual["formalAlternativeStatus"])
                    assertEquals(JsonPrimitive(true), actual["completeStaticMethodIdentityAgreement"])
                    assertEquals(candidate, actual["originalDeclarationCandidateCopiedVerbatim"])
                    SOURCE_PROOF_FLAGS.forEach { assertEquals(JsonPrimitive(false), actual[it]) }
                } else {
                    assertFailsWith<IllegalArgumentException> { index.link("binding", observed, candidate) }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["complete", "cold-source", "cold-static", "owner", "origin", "count", "formal"])
    fun `whole static admission includes cold classes and all formal descriptors`(variant: String, @TempDir out: File) {
        val audit = JvmSavedJson.parse(
            """{
                "record":"class-audit","originalSyntaxClassOrdinal":0,"internalName":"sample/Cold",
                "originalClassOrigin":{"entry":"Cold.class"},"originalMethodIndexOrder":[0],
                "noFormalAlternativeMethodIndexes":[],
                "allOriginalMethodFormalChecks":[{"methodIndex":0,"name":"pick","descriptor":"(I)V"}]
            }""",
        ).jsonObject
        var static = JvmSavedJson.parse(
            """{
                "status":"joined-candidates","internalName":"sample/Cold","bytecodeClassOrdinal":0,
                "studySourceHead":"head","syntaxArchiveId":"syntax","syntaxClassOrdinal":0,
                "syntaxLineReportFile":"lines.gz","classIdentity":{},"syntaxOrigin":{"entry":"Cold.class"},
                "methods":[{"methodIndex":0,"name":"pick","descriptor":"(I)V"}]
            }""",
        ).jsonObject
        static = when (variant) {
            "owner" -> JsonObject(static + ("internalName" to JsonPrimitive("sample/Other")))
            "origin" -> JsonObject(static + ("syntaxOrigin" to JsonObject(emptyMap())))
            "count" -> JsonObject(static + ("methods" to JsonArray(emptyList())))
            "formal" -> JsonObject(static + ("methods" to JvmSavedJson.parse("""[{"methodIndex":0,"name":"pick","descriptor":"(J)V"}]""")))
            else -> static
        }

        JvmSourceCompilerIndex(File(out, "index.sqlite")).use { index ->
            index.add("binding", "head", "audit.gz", 2, audit, JsonObject(emptyMap()))
            index.addStatic("binding", "static.gz", 1, static, JsonObject(emptyMap()))
            if (variant == "cold-source") {
                val cold = JsonObject(audit + ("originalSyntaxClassOrdinal" to JsonPrimitive(1)))
                index.add("binding", "head", "audit.gz", 3, cold, JsonObject(emptyMap()))
            }
            if (variant == "cold-static") {
                val cold = JsonObject(static + ("syntaxClassOrdinal" to JsonPrimitive(1)))
                index.addStatic("binding", "static.gz", 2, cold, JsonObject(emptyMap()))
            }

            if (variant == "complete") {
                index.sealStaticMethods()
            } else {
                assertFailsWith<IllegalArgumentException> { index.sealStaticMethods() }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["duplicate", "reordered", "boolean"])
    fun `a static method table cannot omit or reorder original method indexes`(variant: String, @TempDir out: File) {
        val indexValue = when (variant) {
            "duplicate" -> "0"
            "boolean" -> "true"
            else -> "2"
        }
        val row = JvmSavedJson.parse(
            """{
                "status":"joined-candidates","internalName":"sample/Cold","studySourceHead":"head","syntaxClassOrdinal":0,
                "methods":[{"methodIndex":0,"name":"pick","descriptor":"(I)V"},
                           {"methodIndex":$indexValue,"name":"other","descriptor":"()V"}]
            }""",
        ).jsonObject

        JvmSourceCompilerIndex(File(out, "index.sqlite")).use { index ->
            assertFailsWith<IllegalArgumentException> {
                index.addStatic("binding", "static.gz", 1, row, JsonObject(emptyMap()))
            }
        }
    }
}
