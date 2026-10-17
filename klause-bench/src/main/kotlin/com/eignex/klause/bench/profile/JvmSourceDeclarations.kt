package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.jetbrains.kotlin.psi.KtPsiFactory
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal object JvmSourceDeclarations {
    private const val MAX_INPUT_BYTES = 128L * 1024 * 1024
    private const val MAX_SOURCE_BYTES = 64L * 1024 * 1024
    private const val MAX_REPORT_BYTES = 512L * 1024 * 1024
    private const val MAX_SOURCES = 4096
    private val compact = Json(Reports.json) { prettyPrint = false }
    private val hash = Regex("[0-9a-f]{64}")

    @JvmStatic
    @Suppress("LongMethod")
    fun main(args: Array<String>) {
        val options = parse(args)
        ProfileCaptureArchive.run {
            val out = File(requireNotNull(System.getenv("OUT"))).canonicalFile.apply { mkdirs() }
            val observer = File(requireNotNull(System.getenv("KLAUSE_BENCH")))
            val runtime = File(System.getProperty("java.home"))
            val provenance = File(out, "observer-build-provenance.json")
            InstalledBuild.writeManifest(provenance, observer, runtime)
            val input = File(out, "source-inputs.json")
            var syntaxErrors = 0L
            var compiler: JsonElement? = null
            val execution = runCatching {
                val root = File(requireNotNull(System.getProperty("klause.workspace.root")))
                for (name in listOf("JvmSourceDeclarations.kt", "JvmSourceDeclarationParser.kt",
                    "JvmSourceSyntaxEncoding.kt", "JvmRuntimeMethodMetadata.kt")) {
                    File(root, "klause-bench/src/main/kotlin/com/eignex/klause/bench/profile/$name")
                        .copyTo(File(out, name))
                }
                Reports.writeJson(File(out, "input-origins.json").path, options)
                JvmRuntimeMethodMetadata.download(options.getValue("declarations"), input, MAX_INPUT_BYTES,
                    options.getValue("declarations-sha256"))
                val sources = readSources(input.readBytes().decodeToString(throwOnInvalidSequence = true))
                compiler = compilerIdentity()
                Reports.writeJson(File(out, "compiler-identity.json").path, requireNotNull(compiler))
                val result = inspect(sources, out)
                syntaxErrors = result.getValue("syntaxErrors").jsonPrimitive.content.toLong()
                result
            }
            val preservation = runCatching {
                if (input.isFile) {
                    require(JvmRuntimeMethodMetadata.digest(input) == options.getValue("declarations-sha256"))
                }
                InstalledBuild.readManifest(provenance, observer, runtime, emptyMap())
                compiler?.let { require(compilerIdentity() == it) { "Observer compiler changed" } }
            }
            execution.exceptionOrNull()?.let { failure -> preservation.exceptionOrNull()?.let(failure::addSuppressed) }
            Reports.writeJson(File(out, "completion.json").path, buildJsonObject {
                execution.getOrNull()?.forEach { (name, value) -> put(name, value) }
                put("completed", execution.isSuccess && preservation.isSuccess && syntaxErrors == 0L)
                put("error", (execution.exceptionOrNull() ?: preservation.exceptionOrNull())?.let {
                    "${it.javaClass.name}: ${it.message}"
                })
                put("allSourcesSyntacticallyValid", execution.isSuccess && syntaxErrors == 0L)
                put("loadedClassSelectionProven", false)
                put("symbolResolutionProven", false)
                put("sourcePhaseToJvmDescriptorMappingProven", false)
                put("fullSourcePhaseCoverageProven", false)
                put("compilerElapsedTimeProven", false)
                put("acceptedSavingsCredit", false)
            })
            execution.getOrThrow()
            preservation.getOrThrow()
            check(syntaxErrors == 0L) { "Original source syntax errors remain in the complete report" }
        }
    }

    internal fun parse(args: Array<String>): Map<String, String> {
        val options = args.associate {
            require('=' in it)
            it.substringBefore('=') to it.substringAfter('=')
        }
        require(args.size == options.size && options.keys == setOf("declarations", "declarations-sha256"))
        require(hash.matches(options.getValue("declarations-sha256")))
        val address = options.getValue("declarations")
        require(address.length <= 4096)
        val uri = URI(address)
        require(uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null)
        return options
    }

    internal fun readSources(text: String): List<SourceSyntaxInput> {
        rejectDuplicateKeys(text)
        val specification = Reports.json.parseToJsonElement(text).jsonObject
        require(specification["schema"] == JsonPrimitive(1))
        val sources = specification["sources"] as? JsonArray
        require(sources != null && sources.size in 1..MAX_SOURCES)
        val ids = HashSet<String>()
        val paths = HashSet<String>()
        var bytes = 0L
        return sources.map { value ->
            val source = value.jsonObject
            val id = string(source, "id")
            val path = string(source, "path")
            val expected = string(source, "sha256")
            val content = string(source, "sourceText")
            val raw = content.encodeToByteArray()
            require(id.isNotEmpty() && id.length <= 4096 && id.none(Char::isISOControl) && ids.add(id))
            require(paths.add(path) && safePath(path) && string(source, "sourceFile") == path.substringAfterLast('/'))
            require(path.endsWith(".kt") || path.endsWith(".kts") || path.endsWith(".java"))
            require(hash.matches(expected) && sha256(raw) == expected)
            require(raw.decodeToString(throwOnInvalidSequence = true) == content)
            bytes += raw.size
            require(bytes <= MAX_SOURCE_BYTES)
            SourceSyntaxInput(id, path, expected, content)
        }
    }

    private fun safePath(path: String): Boolean = path.isNotEmpty() && path.length <= 4096 && !path.startsWith('/') &&
        '\\' !in path && ':' !in path && path.none(Char::isISOControl) &&
        path.split('/').none { it.isEmpty() || it == "." || it == ".." }

    private fun string(source: JsonObject, key: String): String {
        val value = source[key] as? JsonPrimitive
        require(value != null && value.isString)
        return value.content
    }

    @Suppress("LongMethod", "TooGenericExceptionCaught")
    private fun inspect(sources: List<SourceSyntaxInput>, out: File): JsonObject {
        val report = File(out, "source-declarations.jsonl.gz")
        check(!report.exists())
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        var records = 0L
        var errors = 0L
        var uncompressedHash = ""
        val encoding = JvmSourceSyntaxEncoding()
        try {
            GZIPOutputStream(report.outputStream().buffered()).use { output ->
                fun write(row: JsonObject) {
                    val raw = (compact.encodeToString(JsonObject.serializer(), row) + "\n").encodeToByteArray()
                    bytes += raw.size
                    check(bytes <= MAX_REPORT_BYTES) { "Complete source declaration report exceeds bounds" }
                    output.write(raw)
                    digest.update(raw)
                    records++
                }
                write(buildJsonObject {
                    put("record", "schema")
                    put("encoding", JvmSourceSyntaxEncoding.ENCODING)
                    put("nodeColumns", JsonArray(JvmSourceSyntaxEncoding.NODE_COLUMNS.map(::JsonPrimitive)))
                    put("rangeColumns", JsonArray(JvmSourceSyntaxEncoding.RANGE_COLUMNS.map(::JsonPrimitive)))
                })
                JvmSourceDeclarationParser().use { parser ->
                    for ((index, source) in sources.withIndex()) {
                        write(buildJsonObject {
                            put("record", "source-start")
                            put("sourceIndex", index)
                            put("sourceId", source.id)
                            put("path", source.path)
                            put("sha256", source.sha256)
                        })
                        val result = parser.inspect(source.path, source.text) { row ->
                            val encoded = encoding.encode(row) { kindIndex, name ->
                                write(buildJsonObject {
                                    put("record", "psi-kind")
                                    put("index", kindIndex)
                                    put("class", name)
                                })
                            }
                            write(buildJsonObject {
                                put("record", "syntax-node")
                                put("sourceIndex", index)
                                put("node", encoded)
                            })
                        }
                        errors += result.syntaxErrors
                        write(buildJsonObject {
                            put("record", "source-end")
                            put("sourceIndex", index)
                            put("syntaxNodes", result.records)
                            put("syntaxErrors", result.syntaxErrors)
                        })
                    }
                }
            }
            val roundTrip = MessageDigest.getInstance("SHA-256")
            var verifiedBytes = 0L
            GZIPInputStream(report.inputStream().buffered()).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    verifiedBytes += count
                    check(verifiedBytes <= MAX_REPORT_BYTES)
                    roundTrip.update(buffer, 0, count)
                }
            }
            val originalHash = digest.digest()
            uncompressedHash = HexFormat.of().formatHex(originalHash)
            check(verifiedBytes == bytes && originalHash.contentEquals(roundTrip.digest()))
        } catch (failure: Exception) {
            if (report.isFile && !report.renameTo(File(out, "source-declarations.partial.jsonl.gz"))) {
                failure.addSuppressed(IllegalStateException("Cannot retain source declaration partial report"))
            }
            throw failure
        }
        return buildJsonObject {
            put("sources", sources.size)
            put("records", records)
            put("syntaxErrors", errors)
            put("allInputSourcesVisited", true)
            put("uncompressedReportBytes", bytes)
            put("uncompressedReportSha256", uncompressedHash)
            put("reportEncoding", JvmSourceSyntaxEncoding.ENCODING)
            put("reportFile", report.name)
            put("reportBytes", report.length())
            put("reportSha256", JvmRuntimeMethodMetadata.digest(report))
            put("completeGzipRoundTripVerified", true)
            put("scope", "Complete syntax declarations, expressions, statements and references; " +
                "exact source ranges and lexical parents only. No symbol, JVM, loader, semantic phase or cost proof.")
        }
    }

    private fun compilerIdentity(): JsonElement {
        require(KotlinCompilerVersion.VERSION == "2.4.10")
        return buildJsonObject {
            put("version", KotlinCompilerVersion.VERSION)
            put("components", buildJsonArray {
                for (type in listOf(KotlinCoreEnvironment::class.java, KtPsiFactory::class.java)) {
                    val jar = File(requireNotNull(type.protectionDomain.codeSource).location.toURI()).canonicalFile
                    require(jar.isFile)
                    add(buildJsonObject {
                        put("class", type.name)
                        put("path", jar.path)
                        put("bytes", jar.length())
                        put("sha256", JvmRuntimeMethodMetadata.digest(jar))
                    })
                }
            })
            put("targetSourcesCompiled", false)
            put("targetExecutableLaunched", false)
            put("sourceTextExecuted", false)
        }
    }

    private fun rejectDuplicateKeys(text: String) {
        val frames = ArrayDeque<SourceJsonObjectKeys?>()
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                '{' -> frames.addLast(SourceJsonObjectKeys())
                '[' -> frames.addLast(null)
                '}', ']' -> if (frames.isNotEmpty()) frames.removeLast()
                ',' -> frames.lastOrNull()?.expectKey = true
                '"' -> {
                    val start = index++
                    while (index < text.length && text[index] != '"') {
                        if (text[index] == '\\') index++
                        index++
                    }
                    require(index < text.length)
                    val frame = frames.lastOrNull()
                    if (frame != null && frame.expectKey) {
                        val key = Reports.json.parseToJsonElement(text.substring(start, index + 1))
                            .jsonPrimitive.content
                        require(frame.keys.add(key)) { "Duplicate source JSON key" }
                        frame.expectKey = false
                    }
                }
            }
            require(frames.size <= 4096) { "Source JSON nesting exceeds bounds" }
            index++
        }
    }
}

internal data class SourceSyntaxInput(val id: String, val path: String, val sha256: String, val text: String)

private class SourceJsonObjectKeys(val keys: HashSet<String> = HashSet(), var expectKey: Boolean = true)
