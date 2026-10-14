package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
        val bundleOptions = options.keys - setOf("checker", "witnessOnly", "catalog", "captureSources")
        require(
            bundleOptions == setOf("bundle", "sha256") || bundleOptions == setOf("input64", "sha256"),
        ) {
            "Use bundle=<https URL> or input64=<base64 gzip bytes>, with sha256=<digest> and checker=source|boolean"
        }
        require(options["captureSources"] in listOf(null, "true", "false")) { "captureSources must be true or false" }
        require(options["witnessOnly"] in listOf(null, "true", "false")) { "witnessOnly must be true or false" }
        val witnessOnly = options["witnessOnly"] == "true"
        require(options["catalog"] == null || options["catalog"] == "true")
        val checker = when (options["checker"] ?: "source") {
            "source" -> "verify_source_results.py"
            "boolean" -> "verify_boolean_source_results.py"
            else -> error("checker must be source or boolean")
        }
        require(!witnessOnly || checker == "verify_source_results.py") { "witnessOnly requires the source checker" }

        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        val root = File(System.getProperty("klause.workspace.root", "."))
        val bytes = options["input64"]?.let { encoded ->
            require(encoded.length <= 96 * 1024) { "Inline bundle is oversized; use a URL" }
            Base64.getDecoder().decode(encoded)
        } ?: run {
            val url = URI(requireNotNull(options["bundle"]))
            require(url.scheme == "https" && url.userInfo == null)
            val connection = url.toURL().openConnection().apply {
                connectTimeout = 30_000
                readTimeout = 30_000
            }
            connection.getInputStream().use { it.readNBytes(32 * 1024 * 1024 + 1) }
        }
        require(bytes.size <= 32 * 1024 * 1024 && sha(bytes) == options["sha256"]) { "Bundle identity differs" }
        out.resolve("input.json.gz").writeBytes(bytes)
        val text = GZIPInputStream(bytes.inputStream()).use { it.readNBytes(64 * 1024 * 1024 + 1) }
        require(text.size <= 64 * 1024 * 1024)
        val bundle = Json.parseToJsonElement(text.decodeToString()).jsonObject
        require(bundle.keys == setOf("cases", "sources"))
        val records = bundle.getValue("cases").jsonArray
        val cases = out.resolve("cases.json").apply { writeText(records.toString()) }
        val sources = out.resolve("sources").apply { mkdirs() }
        var sourceBytes = 0L
        for ((digest, encoded) in bundle.getValue("sources").jsonObject) {
            require(digest.matches(Regex("[0-9a-f]{64}")))
            val source = if (encoded is JsonObject) {
                require(encoded.keys == setOf("gzip64")) { "Unsupported source encoding" }
                val compressed = Base64.getDecoder().decode(encoded.getValue("gzip64").jsonPrimitive.content)
                GZIPInputStream(compressed.inputStream()).use { it.readNBytes(64 * 1024 * 1024 + 1) }
            } else Base64.getDecoder().decode(encoded.jsonPrimitive.content)
            sourceBytes += source.size
            require(sourceBytes <= 64 * 1024 * 1024 && sha(source) == digest) {
                "Original source size or identity differs"
            }
            sources.resolve("$digest.txt").writeBytes(source)
        }
        if (options["catalog"] == "true") collectCatalogSources(bundle.getValue("cases").jsonArray, sources, out)
        if (options["captureSources"] == "true") {
            val captures = buildJsonObject {
                for (case in records) {
                    val row = case.jsonObject
                    val record = row.getValue("record").jsonObject
                    val digest = record.getValue("sourceHashes").jsonObject.getValue("model").jsonPrimitive.content
                    require(digest.matches(Regex("[0-9a-f]{64}")))
                    val retained = sources.resolve("$digest.txt")
                    if (retained.isFile) continue
                    val problem = row.getValue("problem").jsonObject
                    val suite = problem.getValue("suite").jsonPrimitive.content
                    val name = problem.getValue("problem").jsonPrimitive.content
                    val ref = Catalog.uncapped(suite).problems.single { it.name == name }
                    val file = CorpusFetcher.resolve(ref.source)
                    val original = CorpusFiles.open(file).use { it.readNBytes(32 * 1024 * 1024 + 1) }
                    require(original.size <= 32 * 1024 * 1024 && sha(original) == digest) {
                        "Original corpus source differs from the saved record"
                    }
                    retained.writeBytes(original)
                    put(digest, buildJsonObject {
                        put("suite", suite)
                        put("problem", name)
                        put("source", ref.source.toString())
                        put("license", ref.license)
                        put("bytes", original.size)
                    })
                }
            }
            out.resolve("source-capture.json").writeText(captures.toString() + "\n")
        }
        val log = out.resolve("checker-install.log")
        fun execute(file: File, command: List<String>) {
            val process = ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(file)).start()
            check(process.waitFor() == 0) { "${command.first()} failed; see ${file.path}" }
        }
        execute(log, listOf("sudo", "apt-get", "update", "-qq"))
        execute(log, listOf("sudo", "apt-get", "install", "-yq", "python3-venv"))
        val environment = out.resolve("checker-environment")
        execute(log, listOf("python3", "-m", "venv", environment.absolutePath))
        val python = environment.resolve("bin/python").absolutePath
        val tools = root.resolve("klause-bench/tools")
        execute(log, listOf(python, "-m", "pip", "install", "-r",
            tools.resolve("requirements-source-checks.txt").absolutePath))
        execute(out.resolve("checker-dependencies.txt"), listOf(python, "-m", "pip", "freeze"))
        out.resolve("checker-tool-hashes.json").writeText(buildJsonObject {
            for (name in listOf(checker, "requirements-source-checks.txt")) {
                put(name, sha(tools.resolve(name).readBytes()))
            }
        }.toString() + "\n")
        execute(out.resolve("checker-runtime.txt"), listOf(python, "--version"))
        val extraArguments = buildList {
            if (checker == "verify_source_results.py") {
                addAll(listOf("--progress", out.resolve("checked-prefix.jsonl").absolutePath))
                if (witnessOnly) add("--witness-only")
            }
            addAll(listOf("--output", out.resolve("source-checks.json").absolutePath))
        }
        execute(out.resolve("checker.log"), listOf(
            python, tools.resolve(checker).absolutePath, cases.absolutePath,
            "--source-root", sources.absolutePath,
        ) + extraArguments)

        check(environment.deleteRecursively())
        println(out.resolve("source-checks.json").readText())
    }

    private fun collectCatalogSources(cases: JsonArray, sources: File, out: File) {
        val origins = buildJsonObject {
            val suites = cases.groupBy {
                it.jsonObject.getValue("problem").jsonObject.getValue("suite").jsonPrimitive.content
            }
            for ((suite, suiteCases) in suites) {
                val references = Catalog.uncapped(suite).problems.associateBy { it.name }
                for (case in suiteCases) {
                    val record = case.jsonObject.getValue("record").jsonObject
                    val digest = record.getValue("sourceHashes").jsonObject.getValue("model").jsonPrimitive.content
                    require(digest.matches(Regex("[0-9a-f]{64}")))
                    if (sources.resolve("$digest.txt").exists()) continue
                    val name = record.getValue("problem").jsonPrimitive.content
                    val reference = requireNotNull(references[name]) { "Catalog source absent: $suite/$name" }
                    val source = CorpusFiles.open(CorpusFetcher.resolve(reference.source)).use {
                        it.readNBytes(64 * 1024 * 1024 + 1)
                    }
                    require(source.size <= 64 * 1024 * 1024 && sha(source) == digest) {
                        "Catalog source identity differs: $suite/$name"
                    }
                    sources.resolve("$digest.txt").writeBytes(source)
                    put(digest, "$suite/$name")
                }
            }
        }
        out.resolve("catalog-sources.json").writeText(origins.toString() + "\n")
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
}
