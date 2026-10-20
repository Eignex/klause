package com.eignex.klause.bench.profile

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal class JvmSourceCompilerReport(
    private val index: JvmSourceCompilerIndex,
    private val binding: String,
    input: JsonObject,
    member: String,
    private val originalProof: JsonObject,
    private val output: JvmSavedReport,
) {
    private var records = 0L
    private var expanded = 0L
    private var definitions = 0L
    private var methodDefinitions = 0L
    private var ended = false

    init {
        index.beginLinkReport()
        output.write(
            buildJsonObject {
                put("record", "schema")
                put("encoding", "source-compiler-links-v4")
                put("originalInputCatalogEntryCopiedVerbatim", input)
                put("originalReportMember", member)
                put("originalReportProofCopiedVerbatim", originalProof)
                put("deniedProofFlags", buildJsonObject {
                    (SOURCE_PROOF_FLAGS + COHORT_PROOF_FLAGS).distinct().forEach { put(it, false) }
                })
                put("allOriginalFieldsRetainedByAuthenticatedWholeInputReferences", true)
            },
        )
    }

    fun accept(ordinal: Long, raw: ByteArray, row: JsonObject?) {
        accept(ordinal, raw, JvmCandidateRecord(row, false))
    }

    fun accept(ordinal: Long, raw: ByteArray, row: JvmCandidateRecord) {
        check(!ended)
        require(ordinal == records) { "Original report record order differs" }
        val methods = row.value?.let(::methodCandidates)
        val links = if (row.sourceLinksProjected) methods ?: JsonNull else methods?.let(::linkedMethods) ?: JsonNull
        output.write(
            buildJsonObject {
                put("record", "original-compiler-source-link")
                put("originalRawRecordOrdinal", records++)
                put("originalRawRecordIdentity", JvmSavedReports.identity(raw))
                put("originalExpandedRecordOrdinal", if (row.value == null) JsonNull else JsonPrimitive(expanded++))
                put("sourceAuditMethodReferences", links)
            },
        )
    }

    fun finish() {
        check(!ended)
        require(records == originalProof.savedLong("records")) { "Complete original record census differs" }
        output.write(
            buildJsonObject {
                put("record", "source-compiler-links-end")
                put("originalRawRecords", records)
                put("originalExpandedRecords", expanded)
                put("sourceLinkDefinitions", definitions)
                put("methodLinkDefinitions", methodDefinitions)
            },
        )
        ended = true
    }

    private fun methodCandidates(row: JsonObject): JsonObject? =
        when (row["record"]) {
            JsonPrimitive("compiler-observation-candidates") -> row.getValue("methodCandidates").jsonObject
            JsonPrimitive("xml-observation-candidates") -> buildJsonObject {
                put("method", row.getValue("methodCandidates"))
            }
            else -> null
        }

    private fun linkedMethods(methods: JsonObject): JsonObject =
        JsonObject(methods.mapValues { (_, raw) ->
            val value = raw.jsonObject
            projectCandidates(
                value.getValue("observedMethodCopiedVerbatim"),
                value.getValue("methodIdentityParsed"),
                value.getValue("declarationCandidates").jsonArray.map { it.jsonObject },
            )
        })

    fun projectCandidates(observed: JsonElement, parsed: JsonElement, candidates: List<JsonObject>): JsonPrimitive {
        val references = JsonArray(candidates.map { candidate ->
            JsonPrimitive(index.linkReference(binding, observed, candidate) { reference, link ->
                output.write(buildJsonObject {
                    put("record", "source-audit-link-definition")
                    put("reference", reference)
                    put("wholeSourceAuditLink", link)
                })
                definitions++
            })
        })
        return JsonPrimitive(index.methodReference(binding, observed, parsed, references) { reference ->
            output.write(buildJsonObject {
                put("record", "source-audit-method-definition")
                put("reference", reference)
                put("observedMethodCopiedVerbatim", observed)
                put("methodIdentityParsed", parsed)
                put("allOriginalDeclarationCandidateSourceAuditLinkReferences", references)
            })
            methodDefinitions++
        })
    }
}
