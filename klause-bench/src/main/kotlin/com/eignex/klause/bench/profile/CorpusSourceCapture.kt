package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.catalog.FetchMethod
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.report.Reports
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object CorpusSourceCapture {
    internal const val MAX_REQUEST_BYTES = 48 * 1024
    internal const val MAX_CASES = 128
    internal data class Request(val suite: String, val problem: String, val expectedSha256: String)

    @JvmStatic
    @Suppress("TooGenericExceptionCaught")
    fun main(args: Array<String>) {
        require(args.size == 1 && args[0].startsWith("request64=")) { "one request64 argument required" }
        val inherited = File(requireNotNull(System.getenv("OUT")))
        require(inherited.isDirectory) { "OUT must be the profile's existing directory" }
        val out = Files.createDirectory(inherited.toPath().resolve("corpus-source-capture")).toFile()
        val encoded = args[0].substringAfter('=')
        require(encoded.length <= MAX_REQUEST_BYTES * 4 / 3 + 4) { "request is oversized" }
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size <= MAX_REQUEST_BYTES) { "request is oversized" }
        write(out.resolve("request.json"), bytes)
        try {
            val cases = resolve(decode(encoded))
            val resolved = buildJsonObject {
                put("schema", "corpus-original-source-resolved-request-v1")
                put("cases", JsonArray(cases))
            }
            val request = out.resolve("resolved-request.json").apply { writeText(resolved.toString()) }
            Reports.writeJson(out.resolve("build-provenance.json").path, InstalledBuild.reference)
            write(out.resolve("planned.json"), buildJsonObject {
                put("schema", "corpus-original-source-capture-plan-v1")
                put("revision", JsonNull)
                put("revisionAuthority", "owned profile admission; installed harness and runtime hashes")
                put("requestSha256", hash(bytes))
                put("resolvedRequestSha256", hash(request.readBytes()))
                put("sourceOnly", true)
                put("solverCalls", 0)
                put("sourceParserCalls", 0)
                put("checkerCalls", 0)
                put("cases", cases.size)
                put("ownedLimits", CorpusSourceWorker.limits())
            }.toString().toByteArray())
            val captured = CorpusSourceWorker(out).capture(cases)
            println("allRequestedSourcesCaptured=$captured; receipts in ${out.resolve("capture.json")}")
        } catch (error: Exception) {
            out.resolve("failure.json").writeText(buildJsonObject {
                put("schema", "corpus-original-source-capture-failure-v1")
                put("allRequestedSourcesCaptured", false)
                put("error", error.toString())
            }.toString())
            throw error
        }
    }

    /** Each request with the archive URL, collection and member path its catalog layout declares. */
    internal fun resolve(requests: List<Request>): List<JsonObject> = requests.mapIndexed { index, input ->
        val layout = requireNotNull(Catalog.dynamicSuites.singleOrNull { it.id == input.suite }?.sourceLayout) {
            "suite has no original archive layout"
        }
        require(layout.collection.fetch == FetchMethod.TarballZst) { "unsupported archive transport" }
        buildJsonObject {
            put("index", index)
            put("suite", input.suite)
            put("problem", input.problem)
            put("expectedSha256", input.expectedSha256)
            put("archiveUrl", layout.collection.url)
            put("collection", layout.collection.id)
            put("license", layout.collection.license)
            put("member", layout.member(input.problem))
        }
    }

    internal fun decode(encoded: String): List<Request> {
        require(encoded.length <= MAX_REQUEST_BYTES * 4 / 3 + 4) { "request is oversized" }
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size <= MAX_REQUEST_BYTES)
        val text = bytes.toString(Charsets.UTF_8)
        require(text.toByteArray().contentEquals(bytes)) { "request must be UTF-8" }
        val root = Json.parseToJsonElement(text).jsonObject
        require(root.toString() == text) { "request must be canonical JSON without duplicate fields" }
        require(root.keys == setOf("schema", "cases"))
        require(root.getValue("schema").jsonPrimitive.isString &&
            root.getValue("schema").jsonPrimitive.content == "corpus-original-source-request-v1")
        val cases = root.getValue("cases").jsonArray
        require(cases.isNotEmpty() && cases.size <= MAX_CASES)
        val requests = cases.map { value ->
            val input = value.jsonObject
            require(input.keys == setOf("suite", "problem", "expectedSha256"))
            require(input.values.all { it.jsonPrimitive.isString })
            val suite = input.getValue("suite").jsonPrimitive.content
            val problem = input.getValue("problem").jsonPrimitive.content
            val expected = input.getValue("expectedSha256").jsonPrimitive.content
            require(expected.matches(Regex("[0-9a-f]{64}")))
            val layout = requireNotNull(Catalog.dynamicSuites.singleOrNull { it.id == suite }?.sourceLayout)
            layout.member(problem)
            Request(suite, problem, expected)
        }
        require(requests.map { it.suite to it.problem }.distinct().size == requests.size) { "duplicate identity" }
        return requests
    }

    private fun write(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }

    internal fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
}
