package com.eignex.klause.bench.profile

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

internal class JvmRecordedOrigins(
    private val index: JvmSourceCompilerIndex,
    private val input: JsonObject,
    private val paths: Map<String, File>,
    private val output: JvmSavedReport,
) {
    private var staticClasses = 0L
    private var cases = 0L
    private var originInputs = 0L
    private var originRecords = 0L
    private var unparsedLines = 0L
    private var candidatePairs = 0L
    private val represented = HashSet<String>()

    @Suppress("LongMethod")
    fun review() {
        val member = input.savedString("indexMember")
        val file = paths.getValue(member)
        val census = JvmSavedJson.read(file, JvmSavedJson.Kind.PRODUCER_COMPLETION).jsonObject
        val originalCases = census.getValue("originalInventory").jsonObject.getValue("cases").jsonArray
        val retainedCases = census.getValue("cases").jsonArray
        require(
            census["completed"] == JsonPrimitive(true) &&
                originalCases.map { it.jsonObject.getValue("caseDirectory") } ==
                retainedCases.map { it.jsonObject.getValue("caseDirectory") },
        ) { "Complete original case order differs" }
        output.write(buildJsonObject {
            put("record", "schema")
            put("encoding", "recorded-source-origin-candidates-v1")
            put("originalInputCatalogEntryCopiedVerbatim", input)
            put("originalCensusMember", member)
            put("originalCensusIdentity", savedIdentity(file))
            put("deniedProofFlags", buildJsonObject {
                (SOURCE_PROOF_FLAGS + COHORT_PROOF_FLAGS + listOf(
                    "classLoaderIdentityProven", "actualLoadedByteDigestProven",
                )).distinct().forEach { put(it, false) }
            })
            put("allOriginalFieldsRetainedByAuthenticatedWholeInputReferences", true)
        })
        index.visitClasses { source ->
            output.write(buildJsonObject {
                put("record", "static-source-class")
                put("originalClassAuditReference", source)
            })
            staticClasses++
        }
        retainedCases.forEach { raw ->
            val case = raw.jsonObject
            output.write(buildJsonObject {
                put("record", "original-case")
                put("originalCaseOrdinal", cases)
                put("wholeCaseProtocolCopiedVerbatim", case)
            })
            case.getValue("classOriginInputs").jsonArray.forEachIndexed { ordinal, value ->
                originInput(member, ordinal, value.jsonObject)
            }
            cases++
        }
        val parent = member.substringBeforeLast('/', "")
        val originMembers = paths.keys.filter {
            it.substringBeforeLast('/', "") == parent &&
                Regex("class-origins-[0-9]{6}-[0-9]{3}\\.json\\.gz").matches(it.substringAfterLast('/'))
        }.toSet()
        require(represented == originMembers) { "Complete class-origin member census differs" }
        output.write(buildJsonObject {
            put("record", "recorded-source-origin-candidates-end")
            put("staticSourceClasses", staticClasses)
            put("originalCases", cases)
            put("originalOriginInputs", originInputs)
            put("originalOriginRecords", originRecords)
            put("originalUnparsedLines", unparsedLines)
            put("candidatePairs", candidatePairs)
        })
    }

    private fun originInput(censusMember: String, ordinal: Int, original: JsonObject) {
        val proof = original.getValue("retainedMetadata").jsonObject
        val name = proof.savedString("path")
        require(savedMemberName(name) && '/' !in name)
        val parent = censusMember.substringBeforeLast('/', "")
        val member = if (parent.isEmpty()) name else "$parent/$name"
        require(represented.add(member)) { "An original class-origin input was repeated" }
        val value = JvmSavedReports.readObject(paths.getValue(member), proof)
        val observations = value.getValue("observations").jsonObject
        output.write(buildJsonObject {
            put("record", "original-class-origin-input")
            put("originalCaseOrdinal", cases)
            put("originalOriginInputOrdinal", ordinal)
            put("originalCensusMember", member)
            put("originalInputProtocolCopiedVerbatim", original)
            put("originalMetadataSections", JsonObject(value.filterKeys { it != "observations" }))
            put("originalObservationSections", JsonObject(observations.filterKeys {
                it !in setOf("records", "unparsedLines")
            }))
        })
        originInputs++
        observations["records"]?.jsonArray.orEmpty().forEachIndexed { position, record ->
            originRecord(ordinal, position, record.jsonObject)
        }
        observations["unparsedLines"]?.jsonArray.orEmpty().forEachIndexed { position, record ->
            output.write(buildJsonObject {
                put("record", "original-unparsed-origin-line")
                put("originalCaseOrdinal", cases)
                put("originalOriginInputOrdinal", ordinal)
                put("originalUnparsedLineOrdinal", position)
                put("wholeOriginalLineCopiedVerbatim", record)
            })
            unparsedLines++
        }
    }

    private fun originRecord(inputOrdinal: Int, ordinal: Int, record: JsonObject) {
        val name = record.savedString("className")
        require(name.isNotEmpty())
        output.write(buildJsonObject {
            put("record", "original-recorded-class-origin")
            put("originalOriginRecordReference", originRecords)
            put("originalCaseOrdinal", cases)
            put("originalOriginInputOrdinal", inputOrdinal)
            put("originalOriginRecordOrdinal", ordinal)
            put("wholeOriginalRecordCopiedVerbatim", record)
        })
        var found = false
        index.visitClasses(name.replace('.', '/')) { source ->
            found = true
            output.write(buildJsonObject {
                put("record", "recorded-origin-static-candidate")
                put("originalOriginRecordReference", originRecords)
                put("staticSourceClassReference", JsonObject(source.filterKeys {
                    it in setOf("bindingInputId", "studySourceHead", "originalSyntaxClassOrdinal")
                }))
                put("recordedJarHashAgreement", jarHashAgreement(
                    source.getValue("originalClassOrigin").jsonObject,
                    record.getValue("recordedInstalledJar"),
                ))
            })
            candidatePairs++
        }
        if (!found) {
            output.write(buildJsonObject {
                put("record", "no-static-owner-candidate")
                put("originalOriginRecordReference", originRecords)
                put("missingIsNotAbsenceZeroOrRetirement", true)
            })
        }
        originRecords++
    }

    companion object {
        private val HASH = Regex("[0-9a-f]{64}")

        fun jarHashAgreement(origin: JsonObject, recorded: JsonElement): JsonElement {
            require(recorded == JsonNull || recorded is JsonObject)
            val actual = (recorded as? JsonObject)?.get("sha256") as? JsonPrimitive
            val candidate = origin["jarSha256"] as? JsonPrimitive
            val jar = origin["jar"] as? JsonPrimitive
            return if (jar?.isString == true && jar.content.isNotEmpty() &&
                (origin["module"] == null || origin["module"] == JsonNull) &&
                actual?.isString == true && candidate?.isString == true &&
                HASH.matches(actual.content) && HASH.matches(candidate.content)
            ) {
                JsonPrimitive(actual == candidate)
            } else {
                JsonNull
            }
        }
    }
}
