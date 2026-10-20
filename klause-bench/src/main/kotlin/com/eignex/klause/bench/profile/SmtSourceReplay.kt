package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.SmtSourceReceipt
import com.eignex.klause.bench.metric.SmtSourceTools
import com.eignex.klause.bench.metric.SmtSourceValidation
import com.eignex.klause.bench.metric.SolveRecord
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.EnvInfo
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFiles
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object SmtSourceReplay {
    @JvmStatic
    fun main(args: Array<String>) {
        val started = System.nanoTime()
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys == setOf("input64", "sha256")) { "Use input64=<zstd JSON> sha256=<compressed digest>" }
        val encoded = options.getValue("input64")
        require(encoded.length <= 128 * 1024) { "replay request exceeds inline limit" }
        val compressed = Base64.getDecoder().decode(encoded)
        require(sha256(compressed) == options.getValue("sha256")) { "replay request hash differs" }
        val out = File(requireNotNull(System.getenv("OUT"))).absoluteFile.apply { check(mkdirs() || isDirectory) }
        val input = File(out, "request.json.zst").apply { writeBytes(compressed) }
        val bytes = CorpusFiles.open(input).use { it.readNBytes(16 * 1024 * 1024 + 1) }
        require(bytes.size <= 16 * 1024 * 1024) { "expanded replay request exceeds limit" }
        val request = Reports.json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
        require(request.getValue("schema").jsonPrimitive.int == 1)
        val models = request.getValue("models").jsonArray
        val sources = request.getValue("sources").jsonArray
        require(models.size in 1..4096 && sources.size in 1..4096)
        Reports.writeJson(File(out, "build-provenance.json").path, InstalledBuild.reference)
        Reports.writeJson(File(out, "environment.json").path, EnvInfo.capture())
        val results = ArrayList<JsonObject>()
        var tool: SmtSourceValidation.Tool? = null
        var failure: String? = "replay did not complete; inspect retained process logs"
        fun summary(cleanup: Boolean) {
            val value = buildJsonObject {
                put("requestSha256", sha256(compressed))
                put("sourceCount", sources.size)
                put("modelCount", models.size)
                put("elapsedMs", (System.nanoTime() - started) / 1_000_000)
                put("completedModels", results.size)
                put("allModelsAttempted", results.size == models.size)
                put("allAccepted", cleanup && failure == null && results.size == models.size && results.all {
                    it.getValue("validation").jsonObject.getValue("status").jsonPrimitive.content == "valid"
                })
                put("cleanupComplete", cleanup)
                put("failure", failure)
                put("solverInvocations", 0)
                put("solverSearchQueries", 0)
                put("proofCertificateChecked", false)
                put("sources", sources)
                put("results", JsonArray(results))
            }
            val temporary = File(out, "summary.json.tmp").apply { writeText(value.toString() + "\n") }
            Files.move(temporary.toPath(), File(out, "summary.json").toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        summary(false)
        try {
            val resolved = SmtSourceTools.resolve(SmtSourceValidation.GROUND, File(out, "tool"))
            tool = resolved
            Reports.writeJson(File(out, "tool-identity.json").path, mapOf(
                "executablePath" to resolved.executable.absolutePath,
                "executableSha256" to resolved.binarySha256,
                "artifactUrl" to resolved.artifactUrl,
                "artifactSha256" to resolved.artifactSha256,
            ))
            val versionDirectory = File(out, "version").apply { check(mkdir()) }
            val empty = File(versionDirectory, "stdin").apply { writeBytes(byteArrayOf()) }
            val version = SmtSourceReceipt.run(listOf(resolved.executable.absolutePath, "--version"),
                empty, versionDirectory, 10_000)
            Reports.writeJson(File(out, "tool-version.json").path, version)
            check(version.exit == 0 && !version.timedOut && version.cleanupComplete) {
                "external model checker could not start; model compatibility is unverified"
            }
            check(version.memory?.qualified() == true) { "external tool memory observation incomplete" }
            if (System.getProperty("os.name") == "Mac OS X") {
                val probeDirectory = File(out, "memory-limit-probe").apply { check(mkdir()) }
                val probe = SmtSourceReceipt.run(listOf("/bin/sleep", "60"), empty, probeDirectory,
                    2_000, residentLimitBytes = 1_048_576)
                Reports.writeJson(File(out, "memory-limit-probe.json").path, probe)
                check(probe.memory?.exceeded == true && probe.cleanupComplete && !probe.timedOut) {
                    "resident-memory guard did not reject its below-baseline process allowance"
                }
            }
            for ((index, element) in models.withIndex()) {
                check(!Thread.currentThread().isInterrupted && System.nanoTime() - started < 120_000_000_000) {
                    "whole replay interrupted or timed out"
                }
                val model = element.jsonObject
                val producerJson = model.getValue("producerJson").jsonPrimitive.content
                val record = Reports.json.decodeFromString<SolveRecord>(producerJson)
                require(record.feasible == true && SmtSourceValidation.eligible(record) == SmtSourceValidation.GROUND)
                require(record.sourceHashes["model"] == model.getValue("sourceSha256").jsonPrimitive.content)
                require(sha256(requireNotNull(record.finalWitness).toByteArray()) ==
                    model.getValue("modelSha256").jsonPrimitive.content)
                val ref = Catalog.uncapped(model.getValue("suite").jsonPrimitive.content).problems.single {
                    it.name == model.getValue("problem").jsonPrimitive.content
                }
                require(record.problem == ref.name) { "producer problem identity differs" }
                val result = SmtSourceValidation.validateSaved(ref, record,
                    model.getValue("stdout").jsonPrimitive.content, File(out, "case-$index"),
                    resolved.copy(owned = false), producerJson.toByteArray())
                results += buildJsonObject {
                    put("index", index)
                    put("id", model.getValue("id"))
                    put("sourceSha256", model.getValue("sourceSha256"))
                    put("modelSha256", model.getValue("modelSha256"))
                    put("bindings", model.getValue("bindings"))
                    put("validation", Reports.json.encodeToJsonElement(result.validation))
                    put("stats", Reports.json.encodeToJsonElement(result.stats))
                }
                summary(false)
            }
            resolved.verify()
            failure = null
        } finally {
            val complete = failure == null
            val cleanup = runCatching { tool?.close() }.isSuccess
            if (!cleanup) failure = "external replay tool cleanup incomplete"
            summary(cleanup)
            check(cleanup || !complete) { "external replay tool cleanup incomplete" }
        }
    }
}
