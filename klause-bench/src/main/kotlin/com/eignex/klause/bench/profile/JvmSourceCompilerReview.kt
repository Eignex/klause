package com.eignex.klause.bench.profile

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.Locale

internal object JvmSourceCompilerReview {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 4)
        val out = File(args[2])
        val scratch = File(args[3])
        val result = runCatching {
            recordStage(out, "catalog")
            val catalogFile = File(out, "input-catalog.json")
            savedDownload(args[0], catalogFile, JvmSavedJson.Kind.CATALOG_METADATA.maximumBytes, args[1])
            val catalog = JvmSavedJson.read(catalogFile, JvmSavedJson.Kind.CATALOG_METADATA).jsonObject
            val requirement = catalog.getValue("requirements").jsonObject
            val requirementsFile = File(out, "source-obligations.json")
            savedDownload(
                requirement.savedString("url"),
                requirementsFile,
                JvmSavedJson.Kind.CATALOG_METADATA.maximumBytes,
                requirement.savedString("sha256"),
            )
            val requirements = JvmSavedJson.read(requirementsFile, JvmSavedJson.Kind.CATALOG_METADATA).jsonObject
            review(catalog, requirements, out, scratch)
        }
        val completion = result.getOrElse { failure ->
            buildJsonObject {
                put("completed", false)
                put(
                    "error",
                    buildJsonObject {
                    put("type", failure.javaClass.name)
                    put("message", failure.message)
                }
                )
                PROOF_FLAGS.forEach { put(it, false) }
            }
        }
        val bytes = (completion.toString() + "\n").encodeToByteArray()
        require(bytes.size <= JvmSavedJson.Kind.PRODUCER_COMPLETION.maximumBytes)
        Files.write(File(out, "completion.json").toPath(), bytes, StandardOpenOption.CREATE_NEW)
        if (result.isSuccess) recordStage(out, "complete")
        result.getOrThrow()
    }

    internal fun recordStage(out: File, phase: String, arm: Int? = null) {
        require(phase in STAGES && (arm == null || arm in 0..1))
        File(out, "source-compiler-links-stage.json").writeText(
            buildJsonObject {
                put("schema", "source-compiler-links-stage-v1")
                put("phase", phase)
                put("arm", arm)
                put("scope", "Owned reader lifecycle stage; no original data or savings proof")
            }.toString() + "\n",
        )
    }

    private fun originalInputs(catalog: JsonObject): List<JsonObject> =
        listOf(catalog.getValue("signatureInput"), catalog.getValue("auditInput"))
            .plus(catalog.getValue("bindingInputs").jsonArray.toList())
            .plus(catalog.getValue("observationInputs").jsonArray.toList()).map { it.jsonObject }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    internal fun validateCatalog(
        catalog: JsonObject,
        requirements: JsonObject,
        original: JsonObject,
        completion: JsonObject,
    ): List<JsonObject> {
        val targets = requirements.getValue("targets").jsonArray.map { it.jsonObject }
        val names = JsonArray(targets.map { it.getValue("issueTarget") })
        require(
            catalog["schema"] == JsonPrimitive(1) && catalog["targetNames"] == names &&
                JsonObject(catalog.filterKeys { it != "descriptorInput" }) == original,
        ) {
            "Every original source requirement and catalog field must be retained"
        }
        val items = originalInputs(catalog)
        val heads = targets.flatMap { it.getValue("contexts").jsonObject.values }
            .map { it.jsonObject.savedString("originalStudyHead") }.toSet()
        val descriptor = catalog.getValue("descriptorInput").jsonObject
        require(
            heads.isNotEmpty() && items.size + 1 <= 8 &&
                (items + descriptor).map { it.savedString("id") }.toSet().size == items.size + 1 &&
                completion["completed"] == JsonPrimitive(true) &&
                completion["allAuthenticatedInputsByteIdenticalAfterReview"] == JsonPrimitive(true) &&
                completion["allOriginalFormalSignaturesAndUnmatchedDeclarationsRetained"] == JsonPrimitive(true) &&
                completion["compilerObservationsUsedToSelectSourceScope"] == JsonPrimitive(false) &&
                completion["wholeOriginalTargetNames"] == names &&
                SOURCE_PROOF_FLAGS.all { completion[it] == JsonPrimitive(false) },
        )
        val proofs = completion.getValue("inputArchiveProofs").jsonArray.map { it.jsonObject }
        require(
            proofs.size == items.size && items.zip(proofs).all { (item, proof) ->
            val copied = proof.getValue("inputCatalogEntryCopiedVerbatim").jsonObject
            item.all { (key, value) -> copied[key] == value } &&
                proof["allArchiveMembersAuthenticatedBeforeInterpretation"] == JsonPrimitive(true)
        }
        ) { "The descriptor audit has another whole input universe" }
        val expected = catalog.getValue("bindingInputs").jsonArray.flatMap { item ->
            heads.map { item.jsonObject.savedString("id") to it }
        }.toSet()
        val contexts = completion.getValue("sourceContextAudits").jsonArray.map { it.jsonObject }
        val formal = completion.getValue("sourceFormalDescriptorDispositions").jsonArray.map { it.jsonObject }
        for (rows in listOf(contexts, formal)) {
            require(
                rows.size == expected.size && rows.map {
                it.savedString("bindingInputId") to it.savedString("studySourceHead")
            }.toSet() == expected
            ) { "An original source context is missing or duplicated" }
        }
        require(
            contexts.all { context ->
            JsonArray(
                context.getValue("targetDispositions").jsonArray.map {
                it.jsonObject.getValue("originalRequirement")
            }
            ) == requirements["targets"]
        }
        ) { "A complete original semantic obligation differs" }
        require(
            formal.all { context ->
            JsonArray(
                context.getValue("targetDispositions").jsonArray.map {
                it.jsonObject.getValue("issueTarget")
            }
            ) == names
        }
        ) { "A whole formal target disposition is missing or reordered" }
        return contexts
    }

    @Suppress("LongMethod", "TooGenericExceptionCaught")
    internal fun review(catalog: JsonObject, requirements: JsonObject, out: File, scratch: File): JsonObject {
        val items = originalInputs(catalog)
        require(items.isNotEmpty() && items.size + 1 <= 8)
        val inputs = JvmSavedInputs(out, scratch)
        val descriptor = catalog.getValue("descriptorInput").jsonObject
        recordStage(out, "authenticate-inputs")
        val saved = (items + descriptor).associate { it.savedString("id") to inputs.authenticate(it) }
        val paths = saved.getValue(descriptor.savedString("id"))
        val completion = JvmSavedJson.read(
            paths.getValue(descriptor.savedString("completionMember")),
            JvmSavedJson.Kind.PRODUCER_COMPLETION,
        ).jsonObject
        val original = JvmSavedJson.read(
            paths.getValue(descriptor.savedString("catalogMember")),
            JvmSavedJson.Kind.CATALOG_METADATA,
        ).jsonObject
        recordStage(out, "validate-source-universe")
        val contexts = validateCatalog(catalog, requirements, original, completion)
        val reports = ArrayList<JvmSavedReport>()
        try {
            JvmSourceCompilerIndex(File(scratch, "source-compiler-links.sqlite")).use { index ->
                recordStage(out, "source-audits")
                indexAudits(index, descriptor, paths, completion, contexts)
                val observations = catalog.getValue("observationInputs").jsonArray.map { it.jsonObject }
                val cohorts = observations.filter { it["role"] == JsonPrimitive("compiler-cohort") }
                val bindings = catalog.getValue("bindingInputs").jsonArray.map { it.jsonObject }
                    .associateBy { it.savedString("studySourceHead") }
                require(
                    cohorts.size == bindings.size &&
                        cohorts.map { it.savedString("studySourceHead") }.toSet() == bindings.keys,
                )
                val census = observations.single { it["role"] == JsonPrimitive("compiler-census") }.savedString("id")
                val budget = JvmSavedReportBudget()
                cohorts.forEachIndexed { arm, item ->
                    recordStage(out, "candidate-links", arm)
                    val cohortPaths = saved.getValue(item.savedString("id"))
                    val reportMember = memberNamed(item, "compiler-bytecode-candidates.jsonl.gz")
                    val producer = JvmSavedJson.read(
                        cohortPaths.getValue(memberNamed(item, "completion.json")),
                        JvmSavedJson.Kind.PRODUCER_COMPLETION,
                    ).jsonObject
                    require(
                        listOf(
                            "completed",
                            "sourceBindingCompleted",
                            "allAuthenticatedInputsByteIdenticalAfterReview",
                        )
                            .all { producer[it] == JsonPrimitive(true) },
                    )
                    val binding = bindings.getValue(item.savedString("studySourceHead")).savedString("id")
                    val report = JvmSavedReport(
                        File(out, "source-compiler-links-%03d.jsonl.gz".format(Locale.ROOT, arm)),
                        budget,
                    )
                    reports.add(report)
                    val originalProof = reportProof(producer, reportMember)
                    val writer = JvmSourceCompilerReport(index, binding, item, reportMember, originalProof, report)
                    val candidateIndex = File(scratch, "candidate-contexts-%03d.sqlite".format(Locale.ROOT, arm))
                    JvmCompilerCandidates(candidateIndex).use { codec ->
                        JvmSavedReports.read(cohortPaths.getValue(reportMember), originalProof) { ordinal, raw ->
                            val originalRow = JvmSavedJson.parse(raw.decodeToString(throwOnInvalidSequence = true))
                                .jsonObject
                            val row = codec.accept(originalRow)
                            if (ordinal == 0L) {
                                require(
                                    row != null && row["bindingInputId"] == JsonPrimitive(binding) &&
                                        row["censusInputId"] == JsonPrimitive(census),
                                )
                            }
                            writer.accept(ordinal, raw, row)
                        }
                        codec.finish()
                        writer.finish()
                    }
                    check(candidateIndex.delete()) { "Cannot remove closed candidate index" }
                }
            }
            recordStage(out, "output-round-trip")
            val proofs = reports.map { it.verify() }
            recordStage(out, "input-reverification")
            inputs.verifyUnchanged()
            recordStage(out, "completion-metadata")
            return buildJsonObject {
                put("completed", true)
                put("reports", JsonArray(proofs))
                put("reportEncoding", "source-compiler-links-v2")
                put("allOriginalRawRecordReferencesRetainedInOriginalOrder", true)
                put("allOriginalFieldsRetainedByAuthenticatedWholeInputReferences", true)
                put("inputArchiveProofs", JsonArray(inputs.proofs))
                put("wholeOriginalTargetNames", catalog.getValue("targetNames"))
                put("sourceContextAudits", completion.getValue("sourceContextAudits"))
                put("sourceFormalDescriptorDispositions", completion.getValue("sourceFormalDescriptorDispositions"))
                put("allOriginalCompilerCandidatesRetainedInOriginalOrder", true)
                put("allAuthenticatedInputsByteIdenticalAfterReview", true)
                put(
                    "scope",
                    "Complete saved compiler declaration candidates linked to whole source/debug/formal " +
                        "audit records by shared references. Every original record, alternative, " +
                        "unmatched declaration " +
                        "and opaque field is retained by authenticated whole input references. " +
                        "No observation selects source scope or proves semantic phases, loaded/generated selection, " +
                        "physical roots, compiler cost, runtime controls or savings.",
                )
                PROOF_FLAGS.forEach { put(it, false) }
            }
        } catch (failure: Throwable) {
            reports.forEach { report ->
                runCatching { report.retainPartial() }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        }
    }

    internal fun indexAudits(
        index: JvmSourceCompilerIndex,
        descriptor: JsonObject,
        paths: Map<String, File>,
        completion: JsonObject,
        contexts: List<JsonObject>,
    ) {
        val formalFile = "source-formal-descriptor-coverage.jsonl.gz"
        val expected = contexts.associateBy { it.savedString("report") }
        val proofs = completion.getValue("reports").jsonArray.map { it.jsonObject }
        require(
            expected.size == contexts.size && formalFile !in expected && proofs.size == expected.size + 1 &&
                proofs.map { it.savedString("file") }.toSet() == expected.keys + formalFile,
        )
        proofs.forEach { proof ->
            val name = proof.savedString("file")
            require(savedMemberName(name) && '/' !in name)
            val parent = descriptor.savedString("completionMember").substringBeforeLast('/', "")
            val member = if (parent.isEmpty()) name else "$parent/$name"
            val context = expected[name]
            var count = 0L
            var ended = false
            JvmSavedReports.read(paths.getValue(member), proof) { ordinal, raw ->
                if (context != null) {
                    val row = JvmSavedJson.parse(raw.decodeToString(throwOnInvalidSequence = true)).jsonObject
                    require(!ended) { "Source audit continues after complete obligations" }
                    when (ordinal) {
                        0L -> require(
                            row["record"] == JsonPrimitive("schema") &&
                                row["encoding"] == JsonPrimitive("source-formal-descriptor-audit-v3") &&
                                listOf("bindingInputId", "studySourceHead").all { row[it] == context[it] },
                        )

                        1L -> require(
                            row["record"] == JsonPrimitive("formal-inventory-reference") &&
                                row["report"] == JsonPrimitive(formalFile),
                        )

                        else -> if (row["record"] == JsonPrimitive("source-obligations")) {
                            require(row["targets"] == context["targetDispositions"])
                            ended = true
                        } else {
                            require(count < 100_000 && row.savedLong("originalSyntaxClassOrdinal") == count)
                            index.add(
                                context.savedString("bindingInputId"),
                                context.savedString("studySourceHead"),
                                member,
                                ordinal,
                                row,
                                JvmSavedReports.identity(raw),
                            )
                            count++
                        }
                    }
                }
            }
            if (context != null) {
                require(
                ended && count in 1..100_000 &&
                    count == context.savedLong("allOriginalClassRowsReviewed"),
            )
            }
        }
        index.finish()
    }

    private fun memberNamed(item: JsonObject, name: String): String =
        item.getValue("members").jsonArray.map { it.savedString() }.single { it.substringAfterLast('/') == name }

    private fun reportProof(completion: JsonObject, member: String): JsonObject =
        completion.getValue("reports").jsonArray.map { it.jsonObject }
            .single { it["file"] == JsonPrimitive(member.substringAfterLast('/')) }.also {
                require(it["completeGzipRoundTripVerified"] == JsonPrimitive(true))
            }

    private val PROOF_FLAGS = (SOURCE_PROOF_FLAGS + COHORT_PROOF_FLAGS).distinct()
    private val STAGES = setOf(
        "catalog", "authenticate-inputs", "validate-source-universe", "source-audits", "candidate-links",
        "output-round-trip", "input-reverification", "completion-metadata", "complete",
    )
}
