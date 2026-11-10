package com.eignex.klause.bench.lab

import com.eignex.klause.bench.source.CommittedCorpusIndexes
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.bench.source.CorpusIndex
import com.eignex.lab.bench.BenchCase
import com.eignex.lab.bench.BenchProtocol
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.system.exitProcess

/**
 * Offline pre-submission checks for frozen-source descriptors: `check DESCRIPTOR [--manifest FILE] [--workspace DIR]`
 * reports descriptor violations and each case's held-out eligibility; `exposures --use USE --label LABEL CASES...`
 * turns retained `lab-cases.jsonl` rows into exposure entries. Neither reads corpus bytes or runs a solver.
 */
fun main(args: Array<String>) {
    val output = when (args.firstOrNull()) {
        "check" -> {
            val options = SourceEligibility.options(args.drop(1), setOf("--manifest", "--workspace"))
            val descriptor = options.positional.singleOrNull() ?: SourceEligibility.usage()
            val report = SourceEligibility.check(
                SourceEligibility.descriptor(Json.parseToJsonElement(File(descriptor).readText())),
                options.named["--manifest"]?.let { SourceEligibility.manifest(File(it).readText()) },
                File(options.named["--workspace"] ?: CorpusFetcher.workspaceRoot().path),
            )
            println(SourceEligibility.json.encodeToString(SourceEligibility.Report.serializer(), report))
            if (report.descriptorErrors.isNotEmpty()) exitProcess(1)
            return
        }

        "exposures" -> {
            val options = SourceEligibility.options(args.drop(1), setOf("--use", "--label"))
            val use = options.named["--use"] ?: SourceEligibility.usage()
            val label = options.named["--label"] ?: SourceEligibility.usage()
            if (options.positional.isEmpty()) SourceEligibility.usage()
            SourceEligibility.exposures(options.positional.map { File(it) }, use, label)
        }

        else -> SourceEligibility.usage()
    }
    println(SourceEligibility.json.encodeToString(SourceEligibility.Manifest.serializer(), output))
}

/**
 * Held-out eligibility of frozen-source cases under an explicit exposure manifest kept outside the source tree.
 *
 * Two inputs are the same source only when they share exact bytes: equal stored hashes, or stored and decoded hashes
 * joined by verified records. Names, paths and families never establish equivalence; related sources count only through
 * the manifest's recorded relations. A case is eligible only when its decoded identity is verified, nothing exposes
 * it, and the manifest declares complete coverage for every collection its identities touch.
 */
internal object SourceEligibility {
    const val SCHEMA = "source-exposure-v1"
    const val REPORT_SCHEMA = "source-eligibility-v1"

    private val SHA256 = Regex("[a-f0-9]{64}")

    val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    /** One input file: its corpus-relative [path] (collection first), stored and decoded hashes when known. */
    @Serializable
    data class Identity(
        val path: String,
        val role: String = "model",
        val packedSha256: String? = null,
        val plainSha256: String? = null,
    ) {
        val collection: String get() = path.substringBefore('/')
        val compressed: Boolean get() = CorpusFiles.isCompressed(File(path))

        /** Decoded hashes this record asserts; an uncompressed corpus member decodes to its stored bytes. */
        val decoded: Set<String> get() = setOfNotNull(plainSha256, packedSha256?.takeUnless { compressed })
    }

    /** A source some solver run, selection or applicability screen used, with that use's [outcome]. */
    @Serializable
    data class Exposure(val inputs: List<Identity>, val use: String, val outcome: String, val evidence: String)

    /** A verified decoded identity, such as a source-identity receipt, that exposes nothing by itself. */
    @Serializable
    data class Verified(val inputs: List<Identity>, val evidence: String)

    /** Sources recorded as one family or as encodings of one source; exposing a member exposes the others. */
    @Serializable
    data class Relation(val members: List<Identity>, val kind: String, val reason: String)

    /** The manifest's claim that [collection]'s exposures are completely recorded, on [basis]. */
    @Serializable
    data class Coverage(val collection: String, val basis: String)

    /** Exposure the manifest knows it could not record; a null [collection] could be any collection. */
    @Serializable
    data class Gap(val collection: String? = null, val reason: String)

    @Serializable
    data class Manifest(
        val schema: String = SCHEMA,
        val coverage: List<Coverage> = emptyList(),
        val exposures: List<Exposure> = emptyList(),
        val identities: List<Verified> = emptyList(),
        val relations: List<Relation> = emptyList(),
        val gaps: List<Gap> = emptyList(),
    )

    enum class Status { EXPOSED, ELIGIBLE, UNRESOLVED }

    @Serializable
    data class Match(
        val via: String,
        val inputs: List<Identity>,
        val use: String,
        val outcome: String,
        val evidence: String,
    )

    @Serializable
    data class CaseReport(
        val id: String,
        val inputs: List<Identity>,
        val status: Status,
        val reasons: List<String>,
        val exposures: List<Match> = emptyList(),
        /** Other recorded or committed paths holding the same stored or decoded bytes. */
        val aliases: List<String> = emptyList(),
    )

    @Serializable
    data class Report(
        val schema: String = REPORT_SCHEMA,
        val descriptorErrors: List<String>,
        val coverage: List<String>,
        val cases: List<CaseReport>,
    )

    fun manifest(text: String): Manifest {
        val manifest = json.decodeFromString(Manifest.serializer(), text)
        val violations = buildList {
            if (manifest.schema != SCHEMA) add("manifest schema '${manifest.schema}' is not $SCHEMA")
            manifest.coverage.filter { it.collection.isBlank() || it.basis.isBlank() }.forEach {
                add("coverage for '${it.collection}' needs a collection and the basis of its completeness")
            }
            manifest.exposures.forEachIndexed { index, exposure ->
                val where = "exposure $index"
                if (exposure.use.isBlank() || exposure.outcome.isBlank() || exposure.evidence.isBlank()) {
                    add("$where needs use, outcome and evidence; record unsuitable and no-witness outcomes too")
                }
                addAll(identityViolations(where, exposure.inputs))
            }
            manifest.identities.forEachIndexed { index, verified ->
                if (verified.evidence.isBlank()) add("identity $index needs the evidence that verified it")
                addAll(identityViolations("identity $index", verified.inputs))
                verified.inputs.filter { it.compressed && it.plainSha256 == null }.forEach {
                    add("identity $index: ${it.path} records no decoded sha256")
                }
            }
            manifest.relations.forEachIndexed { index, relation ->
                if (relation.members.size < 2 || relation.kind.isBlank() || relation.reason.isBlank()) {
                    add("relation $index needs at least two members, a kind and a reason")
                }
                addAll(identityViolations("relation $index", relation.members))
            }
            manifest.gaps.filter { it.reason.isBlank() }.forEach { add("gap for '${it.collection}' needs a reason") }
        }
        require(violations.isEmpty()) { "Exposure manifest rejected:\n" + violations.joinToString("\n") }
        return manifest
    }

    private fun identityViolations(where: String, inputs: List<Identity>): List<String> = buildList {
        if (inputs.isEmpty()) add("$where lists no inputs")
        for (input in inputs) {
            if (input.path.isBlank() || input.path.startsWith('/')) {
                add("$where: input path '${input.path}' is not corpus-relative")
            }
            if (input.packedSha256 == null && input.plainSha256 == null) add("$where: ${input.path} has no hash")
            listOfNotNull(input.packedSha256, input.plainSha256).filterNot { it.matches(SHA256) }.forEach {
                add("$where: ${input.path} hash '$it' is not a lowercase 64-digit SHA-256")
            }
        }
        if (inputs.map { it.role }.distinct().size != inputs.size) add("$where repeats an input role")
    }

    /** The frozen `cases` templates in [element]: a template array, a bench request, an experiment or a stored job. */
    fun descriptor(element: JsonElement): List<BenchCase> = when {
        element is JsonArray ->
            BenchProtocol.json.decodeFromJsonElement(ListSerializer(BenchCase.serializer()), element)

        element is JsonObject && element["experiment"] != null -> descriptor(element.getValue("experiment"))

        element is JsonObject && element["bench"] is JsonObject ->
            descriptor(element.getValue("bench").jsonObject.getValue("request"))

        element is JsonObject && element["parameters"] is JsonObject ->
            descriptor(element.getValue("parameters").jsonObject.getValue("cases"))

        element is JsonPrimitive && element.isString -> descriptor(Json.parseToJsonElement(element.content))

        else -> error("no frozen-source cases found: expected a template array, request, experiment or job")
    }

    fun check(templates: List<BenchCase>, manifest: Manifest?, workspace: File): Report {
        val indexes = mutableMapOf<String, CorpusIndex?>()
        val index = { id: String -> indexes.getOrPut(id) { CommittedCorpusIndexes.load(id, workspace) } }
        val descriptorErrors = FrozenSourceDescriptors.violations(templates) + templates.flatMap { template ->
            template.inputs.mapNotNull { input ->
                indexViolation(input.path, input.sha256, index)
                    ?.let { "case '${template.id}': $it" }
            }
        }
        val committed = if (manifest == null) emptyList() else committedIdentities(workspace)
        val cases = templates.map { template ->
            val attributes = FrozenSourceDescriptors.attributes(template)
            val inputs = template.inputs.map { input ->
                Identity(
                    input.path,
                    input.role,
                    input.sha256,
                    attributes[if (input.role == "data") "sourceDataPlainSha256" else "sourcePlainSha256"],
                )
            }
            if (manifest == null) {
                CaseReport(template.id, inputs, Status.UNRESOLVED, listOf("no exposure manifest supplied"))
            } else {
                classify(template.id, inputs, manifest, committed)
            }
        }
        val coverage = manifest?.coverage.orEmpty().map { it.collection }
        return Report(descriptorErrors = descriptorErrors, coverage = coverage, cases = cases)
    }

    private fun indexViolation(path: String, sha256: String?, index: (String) -> CorpusIndex?): String? {
        val collection = path.substringBefore('/')
        val member = path.substringAfter('/')
        val committed = index(collection)
            ?: return "collection '$collection' has no committed corpus index; refresh it with CommittedCorpusIndexes"
        val entry = committed.entry(member)
        val plain = CorpusFiles.plainPath(member).takeIf { entry == null && it != member }?.let(committed::entry)
        return when {
            entry != null && entry.sha256 != sha256 ->
                "input $path has stored sha256 $sha256, but the committed index records ${entry.sha256}"

            entry != null -> null

            plain != null ->
                "input $path is not in the committed index, which lists its plain member " +
                    "$collection/${plain.path} with sha256 ${plain.sha256}; declare that path and hash"

            else -> "input $path is not in the committed index of '$collection'"
        }
    }

    /** Committed index entries as identities; a plain-named entry records decoded bytes as stored bytes. */
    private fun committedIdentities(workspace: File): List<Identity> =
        File(workspace, CommittedCorpusIndexes.DIRECTORY).listFiles { file -> file.name.endsWith(".tsv.gz") }
            .orEmpty().sortedBy { it.name }.flatMap { file ->
                val id = file.name.removeSuffix(".tsv.gz")
                CommittedCorpusIndexes.load(id, workspace)?.files.orEmpty()
                    .map { Identity("$id/${it.path}", packedSha256 = it.sha256) }
            }

    internal fun classify(
        id: String,
        inputs: List<Identity>,
        manifest: Manifest,
        committed: List<Identity> = emptyList(),
    ): CaseReport {
        inputs.firstOrNull { it.packedSha256 == null }?.let {
            return CaseReport(id, inputs, Status.UNRESOLVED, listOf("input ${it.path} has no stored sha256"))
        }
        val identities = Identities(
            inputs + manifest.exposures.flatMap { it.inputs } +
                manifest.identities.flatMap { it.inputs } + manifest.relations.flatMap { it.members } + committed,
        )
        val aliases = identities.aliases(inputs).map { it.path }.distinct().sorted() - inputs.map { it.path }.toSet()
        val stored = manifest.exposures.filter { exposure ->
            exposure.inputs.size == inputs.size && exposure.inputs.all { other ->
                inputs.any { it.role == other.role && it.packedSha256 == other.packedSha256 }
            }
        }
        if (stored.isNotEmpty()) {
            return CaseReport(
                id,
                inputs,
                Status.EXPOSED,
                listOf("identical stored bytes were already used"),
                stored.map { it.match("stored-bytes") },
                aliases,
            )
        }
        val ambiguous = inputs.filter { identities.ambiguous(it) }
        if (ambiguous.isNotEmpty()) {
            return CaseReport(
                id,
                inputs,
                Status.UNRESOLVED,
                ambiguous.map {
                    "${it.path} joins conflicting decoded identities ${identities.decoded(it).sorted().joinToString()}"
                },
                aliases = aliases,
            )
        }
        val related = manifest.relations.filter { relation -> relation.members.any { identities.same(inputs, it) } }
        val exposures = manifest.exposures.filter { exposure ->
            exposure.inputs.size == inputs.size && exposure.inputs.all { identities.same(inputs, it) }
        }.map { it.match("decoded-identity") } + related.flatMap { relation ->
            manifest.exposures.filter { exposure ->
                exposure.inputs.any { input -> relation.members.any { identities.same(listOf(it), input) } }
            }.map { it.match("${relation.kind}: ${relation.reason}") }
        }
        if (exposures.isNotEmpty()) {
            return CaseReport(
                id,
                inputs,
                Status.EXPOSED,
                exposures.map { it.via }.distinct().map {
                    if (it == "decoded-identity") {
                        "a source with the same decoded bytes was used"
                    } else {
                        "related source used ($it)"
                    }
                },
                exposures.distinct(),
                aliases,
            )
        }
        val touched = (inputs + identities.aliases(inputs) + related.flatMap { it.members })
            .map { it.collection }.toSortedSet()
        val reasons = unresolved(inputs, manifest, identities, touched)
        return if (reasons.isEmpty()) {
            CaseReport(
                id,
                inputs,
                Status.ELIGIBLE,
                listOf("no recorded exposure under complete coverage of ${touched.joinToString()}"),
                aliases = aliases,
            )
        } else {
            CaseReport(id, inputs, Status.UNRESOLVED, reasons, aliases = aliases)
        }
    }

    private fun unresolved(
        inputs: List<Identity>,
        manifest: Manifest,
        identities: Identities,
        touched: Set<String>,
    ): List<String> = buildList {
        manifest.exposures.filter { exposure -> exposure.inputs.any { identities.same(inputs, it) } }.forEach {
            add(
                "shares ${it.inputs.joinToString { input -> input.role }} bytes with an exposed source " +
                    "(${it.evidence}) but not every input; record a relation or a distinct-instance decision",
            )
        }
        inputs.filter { identities.verifiedDecoded(it, manifest) == null }.forEach {
            add("${it.path} has no verified decoded sha256; add its source-identity receipt to the manifest identities")
        }
        val uncovered = touched - manifest.coverage.map { it.collection }.toSet()
        if (uncovered.isNotEmpty()) {
            add("the manifest declares no complete exposure coverage for ${uncovered.joinToString()}")
        }
        manifest.gaps.filter { it.collection == null || it.collection in touched }.forEach {
            add("the manifest records an exposure gap${it.collection?.let { c -> " in $c" }.orEmpty()}: ${it.reason}")
        }
    }

    private fun Exposure.match(via: String) = Match(via, inputs, use, outcome, evidence)

    /** Hashes joined by records that bind one file's stored bytes to its decoded bytes. */
    private class Identities(private val records: List<Identity>) {
        private val parent = mutableMapOf<String, String>()

        init {
            for (record in records) {
                val hashes = listOfNotNull(record.packedSha256) + record.decoded
                hashes.forEach { find(it) }
                hashes.zipWithNext { a, b -> union(a, b) }
            }
        }

        private fun find(hash: String): String {
            val up = parent.getOrPut(hash) { hash }
            return if (up == hash) hash else find(up).also { parent[hash] = it }
        }

        private fun union(a: String, b: String) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }

        /** Whether [other] holds the same bytes as the input of [inputs] with its role. */
        fun same(inputs: List<Identity>, other: Identity): Boolean = inputs.any { input ->
            input.role == other.role && component(input)?.let { it == component(other) } == true
        }

        fun component(identity: Identity): String? =
            (identity.packedSha256 ?: identity.decoded.firstOrNull())?.let(::find)

        fun decoded(identity: Identity): Set<String> {
            val root = component(identity) ?: return emptySet()
            return records.filter { component(it) == root }.flatMap { it.decoded }.toSet()
        }

        fun ambiguous(identity: Identity): Boolean = decoded(identity).size > 1

        fun aliases(inputs: List<Identity>): List<Identity> =
            records.filter { record -> inputs.any { component(it) == component(record) } }

        fun verifiedDecoded(identity: Identity, manifest: Manifest): String? {
            if (!identity.compressed) return identity.packedSha256
            val verified = manifest.exposures.flatMap { it.inputs } + manifest.identities.flatMap { it.inputs }
            return verified.firstOrNull { it.packedSha256 == identity.packedSha256 && it.plainSha256 != null }
                ?.plainSha256
        }
    }

    /** Exposure entries for every retained row, including failed, unsuitable and no-witness outcomes. */
    fun exposures(files: List<File>, use: String, label: String): Manifest {
        val rows = linkedMapOf<Pair<List<Identity>, String>, MutableList<String>>()
        val gaps = mutableListOf<Gap>()
        for (file in files) {
            file.bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.forEachIndexed { line, text ->
                    val row = Json.parseToJsonElement(text).jsonObject
                    val where = "$label ${file.path} row ${row["index"]?.jsonPrimitive?.contentOrNull ?: "line $line"}"
                    val problem = row["problem"] as? JsonObject
                    val inputs = (problem?.get("benchCase") as? JsonObject)?.get("inputs") as? JsonArray
                    val record = row["record"] as? JsonObject
                    val hashes = record?.get("sourceHashes") as? JsonObject
                    val identities = inputs?.map { it.jsonObject }?.mapNotNull { input ->
                        val path = input["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val role = input["role"]?.jsonPrimitive?.contentOrNull ?: "model"
                        Identity(
                            path,
                            role,
                            input["sha256"]?.jsonPrimitive?.contentOrNull,
                            hashes?.get(role)?.jsonPrimitive?.contentOrNull,
                        )
                    }.orEmpty()
                    if (identities.isEmpty() || identities.any { it.packedSha256 == null && it.plainSha256 == null }) {
                        gaps += Gap(
                            problem?.get("collection")?.jsonPrimitive?.contentOrNull,
                            "$where has no hashed declared inputs",
                        )
                    } else {
                        rows.getOrPut(identities to outcome(row["status"]?.jsonPrimitive?.contentOrNull, record)) {
                            mutableListOf()
                        } += where
                    }
                }
            }
        }
        return Manifest(
            exposures = rows.map { (key, evidence) ->
                Exposure(key.first, use, key.second, evidence.joinToString("; "))
            },
            gaps = gaps,
        )
    }

    private fun outcome(status: String?, record: JsonObject?): String {
        val stats = record?.get("stats") as? JsonObject
        val check = (record?.get("labCheck") as? JsonObject)?.get("state")?.jsonPrimitive?.contentOrNull
        val feasible = (record?.get("feasible") as? JsonPrimitive)?.booleanOrNull
        val proven = (record?.get("proven") as? JsonPrimitive)?.booleanOrNull == true
        return when {
            status != "DONE" || record == null -> "failed${status?.let { ":$it" }.orEmpty()}"
            stats?.containsKey("unsupported") == true -> "unsuitable"
            check == "invalid" -> "invalid-witness"
            feasible == true && check == "valid" -> "checked-witness"
            feasible == true -> "witness"
            feasible == false && proven -> "infeasibility-claim"
            else -> "no-witness"
        }
    }

    class Options(val named: Map<String, String>, val positional: List<String>)

    fun options(args: List<String>, names: Set<String>): Options {
        val named = mutableMapOf<String, String>()
        val positional = mutableListOf<String>()
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            if (arg in names) {
                named[arg] = args.getOrNull(index + 1) ?: usage()
                index += 2
            } else {
                positional += arg
                index++
            }
        }
        return Options(named, positional)
    }

    fun usage(): Nothing {
        System.err.println(
            "usage: check DESCRIPTOR [--manifest FILE] [--workspace DIR]\n" +
                "       exposures --use USE --label LABEL LAB_CASES_JSONL...",
        )
        exitProcess(2)
    }
}
