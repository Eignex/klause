package com.eignex.klause.bench.profile

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JvmSourceCompilerIndexTest {
    @ParameterizedTest
    @ValueSource(strings = ["missing", "duplicate", "overlap", "boolean", "unknown-order"])
    fun `an incomplete or duplicated method partition cannot admit a source class`(
        variant: String,
        @TempDir out: File,
    ) {
        val checked = when (variant) {
            "missing" -> "[]"
            "duplicate" -> """[{"methodIndex":0},{"methodIndex":0}]"""
            "boolean" -> """[{"methodIndex":false}]"""
            else -> """[{"methodIndex":0}]"""
        }
        val row = buildJsonObject {
            put("record", "class-audit")
            put("originalSyntaxClassOrdinal", 0)
            put("originalMethodIndexOrder", JvmSavedJson.parse(if (variant == "unknown-order") "null" else "[0]"))
            put("noFormalAlternativeMethodIndexes", JvmSavedJson.parse(if (variant == "overlap") "[0]" else "[]"))
            put("allOriginalMethodFormalChecks", JvmSavedJson.parse(checked))
        }
        JvmSourceCompilerIndex(File(out, "index.sqlite")).use { index ->
            assertFailsWith<IllegalArgumentException> {
                index.add("binding", "head", "audit.gz", 2, row, JsonObject(emptyMap()))
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["checked", "unmatched"])
    fun `source links retain checked and unmatched declarations with complete descriptors`(
        variant: String,
        @TempDir out: File,
    ) {
        val origin = buildJsonObject { put("entry", "Cold.class") }
        val audit = buildJsonObject {
            put("record", "class-audit")
            put("originalSyntaxClassOrdinal", 0)
            put("internalName", "sample/Cold")
            put("originalClassOrigin", origin)
            put("originalMethodIndexOrder", JvmSavedJson.parse("[0,1]"))
            put(
                "noFormalAlternativeMethodIndexes",
                JvmSavedJson.parse(
                if (variant == "checked") "[1]" else "[0,1]",
            )
            )
            put(
                "allOriginalMethodFormalChecks",
                JvmSavedJson.parse(
                if (variant == "checked") {
                    """[{"methodIndex":0,"name":"pick","descriptor":"(I)V","unknown":[false,null]}]"""
                } else {
                    "[]"
                },
            )
            )
        }
        val candidate = buildJsonObject {
            put("bindingRecordOrdinal", 1)
            put("bytecodeClassOrdinal", 0)
            put("studySourceHead", "head")
            put("syntaxArchiveId", "syntax")
            put("syntaxClassOrdinal", 0)
            put("syntaxLineReportFile", "lines.gz")
            put("originalClassIdentity", JsonObject(emptyMap()))
            put("methodIndex", 0)
            put("originalSyntaxOrigin", origin)
        }

        val actual = JvmSourceCompilerIndex(File(out, "index.sqlite")).use { index ->
            index.add(
                "binding",
                "head",
                "source-audit.jsonl.gz",
                2,
                audit,
                buildJsonObject {
                    put("size", 123)
                    put("sha256", "a".repeat(64))
                },
            )
            index.finish()
            index.addStatic(
                "binding", "static.gz", 1,
                buildJsonObject {
                    put("status", "joined-candidates")
                    put("internalName", "sample/Cold")
                    put("bytecodeClassOrdinal", 0)
                    put("studySourceHead", "head")
                    put("syntaxArchiveId", "syntax")
                    put("syntaxClassOrdinal", 0)
                    put("syntaxLineReportFile", "lines.gz")
                    put("classIdentity", JsonObject(emptyMap()))
                    put("syntaxOrigin", origin)
                    put("methods", JvmSavedJson.parse(
                        """[{"methodIndex":0,"name":"pick","descriptor":"(I)V"},
                            {"methodIndex":1,"name":"cold","descriptor":"()V"}]""",
                    ))
                },
                JsonObject(emptyMap()),
            )
            index.sealStaticMethods()
            index.link("binding", JsonPrimitive("sample.Cold.pick(I)V"), candidate)
        }

        assertEquals(candidate, actual["originalDeclarationCandidateCopiedVerbatim"])
        assertEquals(
            JsonPrimitive(
            when (variant) {
                "checked" -> "all-formal-alternatives-retained"
                else -> "no-formal-alternative"
            },
        ),
            actual["formalAlternativeStatus"]
        )
        assertEquals(
            if (variant == "checked") {
            buildJsonObject {
            put("allOriginalMethodFormalChecksIndex", 0)
            put("methodIndex", 0)
        }
        } else {
            JsonNull
        },
            actual["recordedMethodFormalAuditReference"]
        )
        SOURCE_PROOF_FLAGS.forEach { assertEquals(JsonPrimitive(false), actual[it]) }
        assertEquals(JsonPrimitive(true), actual["completeStaticMethodIdentityAgreement"])
    }

    @ParameterizedTest
    @ValueSource(strings = ["owner", "origin", "method", "missing-class"])
    fun `a different declaration cannot borrow another source audit`(variant: String, @TempDir out: File) {
        val origin = buildJsonObject { put("entry", "Cold.class") }
        val row = buildJsonObject {
            put("record", "class-audit")
            put("originalSyntaxClassOrdinal", 0)
            put("internalName", "sample/Cold")
            put("originalClassOrigin", origin)
            put("originalMethodIndexOrder", JvmSavedJson.parse("[0]"))
            put("noFormalAlternativeMethodIndexes", JvmSavedJson.parse("[]"))
            put(
                "allOriginalMethodFormalChecks",
                JvmSavedJson.parse(
                """[{"methodIndex":0,"name":"pick","descriptor":"(I)V"}]""",
            )
            )
        }
        val candidate = buildJsonObject {
            put("bindingRecordOrdinal", 1)
            put("bytecodeClassOrdinal", 0)
            put("studySourceHead", "head")
            put("syntaxArchiveId", "syntax")
            put("syntaxClassOrdinal", if (variant == "missing-class") 1 else 0)
            put("syntaxLineReportFile", "lines.gz")
            put("originalClassIdentity", JsonObject(emptyMap()))
            put("methodIndex", 0)
            put("originalSyntaxOrigin", if (variant == "origin") JsonObject(emptyMap()) else origin)
        }
        val observed = when (variant) {
            "owner" -> "sample.Other.pick(I)V"
            "method" -> "sample.Cold.other(I)V"
            else -> "sample.Cold.pick(I)V"
        }
        JvmSourceCompilerIndex(File(out, "index.sqlite")).use { index ->
            index.add("binding", "head", "audit.gz", 2, row, JsonObject(emptyMap()))
            index.addStatic(
                "binding", "static.gz", 1,
                buildJsonObject {
                    put("status", "joined-candidates")
                    put("internalName", "sample/Cold")
                    put("bytecodeClassOrdinal", 0)
                    put("studySourceHead", "head")
                    put("syntaxArchiveId", "syntax")
                    put("syntaxClassOrdinal", 0)
                    put("syntaxLineReportFile", "lines.gz")
                    put("classIdentity", JsonObject(emptyMap()))
                    put("syntaxOrigin", origin)
                    put("methods", JvmSavedJson.parse("""[{"methodIndex":0,"name":"pick","descriptor":"(I)V"}]"""))
                },
                JsonObject(emptyMap()),
            )
            index.sealStaticMethods()

            assertFailsWith<IllegalArgumentException> { index.link("binding", JsonPrimitive(observed), candidate) }
        }
    }
}
