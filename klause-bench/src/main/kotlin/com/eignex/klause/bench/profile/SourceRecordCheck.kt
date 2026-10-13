package com.eignex.klause.bench.profile

import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Validate saved lab source records on an analysis host without rerunning their solver. */
object SourceRecordCheck {
    /** Read a hash-pinned gzip JSON bundle and run the maintained independent source verifier. */
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys == setOf("bundle", "sha256")) { "Use bundle=<https URL> sha256=<digest>" }
        val url = URI(requireNotNull(options["bundle"]))
        require(url.scheme == "https" && url.userInfo == null)
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        val root = File(System.getProperty("klause.workspace.root", "."))
        val connection = url.toURL().openConnection().apply { connectTimeout = 30_000; readTimeout = 30_000 }
        val bytes = connection.getInputStream().use { it.readNBytes(32 * 1024 * 1024 + 1) }
        require(bytes.size <= 32 * 1024 * 1024 && sha(bytes) == options["sha256"]) { "Bundle identity differs" }
        out.resolve("input.json.gz").writeBytes(bytes)
        val text = GZIPInputStream(bytes.inputStream()).use { it.readNBytes(64 * 1024 * 1024 + 1) }
        require(text.size <= 64 * 1024 * 1024)
        val bundle = Json.parseToJsonElement(text.decodeToString()).jsonObject
        require(bundle.keys == setOf("cases", "sources"))
        val cases = out.resolve("cases.json").apply { writeText(bundle.getValue("cases").jsonArray.toString()) }
        val sources = out.resolve("sources").apply { mkdirs() }
        for ((digest, encoded) in bundle.getValue("sources").jsonObject) {
            require(digest.matches(Regex("[0-9a-f]{64}")))
            val source = Base64.getDecoder().decode(encoded.jsonPrimitive.content)
            require(sha(source) == digest) { "Original source identity differs" }
            sources.resolve("$digest.txt").writeBytes(source)
        }
        val log = out.resolve("checker-install.log")
        fun execute(file: File, vararg command: String) {
            val process = ProcessBuilder(command.toList()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(file)).start()
            check(process.waitFor() == 0) { "${command.first()} failed; see ${file.path}" }
        }
        execute(log, "sudo", "apt-get", "update", "-qq")
        execute(log, "sudo", "apt-get", "install", "-yq", "python3-venv")
        val environment = out.resolve("checker-environment")
        execute(log, "python3", "-m", "venv", environment.absolutePath)
        val python = environment.resolve("bin/python").absolutePath
        val tools = root.resolve("klause-bench/tools")
        execute(log, python, "-m", "pip", "install", "-r", tools.resolve("requirements-source-checks.txt").absolutePath)
        execute(out.resolve("checker-dependencies.txt"), python, "-m", "pip", "freeze")
        out.resolve("checker-tool-hashes.json").writeText(buildJsonObject {
            for (name in listOf("verify_source_results.py", "requirements-source-checks.txt")) {
                put(name, sha(tools.resolve(name).readBytes()))
            }
        }.toString() + "\n")
        execute(out.resolve("checker-runtime.txt"), python, "--version")
        execute(out.resolve("checker.log"), python, tools.resolve("verify_source_results.py").absolutePath,
            cases.absolutePath, "--source-root", sources.absolutePath,
            "--progress", out.resolve("checked-prefix.jsonl").absolutePath,
            "--output", out.resolve("source-checks.json").absolutePath)
        check(environment.deleteRecursively())
        println(out.resolve("source-checks.json").readText())
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
}
