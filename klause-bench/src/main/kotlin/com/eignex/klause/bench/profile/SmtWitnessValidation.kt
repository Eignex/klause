package com.eignex.klause.bench.profile

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object SmtWitnessValidation {
    internal const val MAX_REQUEST_BYTES = 48 * 1024
    internal const val MAX_PAYLOAD_BYTES = 32 * 1024
    internal const val MAX_GZIP_SOURCE_BYTES = 4 * 1024 * 1024
    internal const val MAX_CASES = 16
    private const val TIMEOUT_SECONDS = 1200L
    private const val REAP_SECONDS = 15L
    private const val RESOURCE_ROOT = "/smt-witness-validation/"
    private val resourceNames = listOf("smt_witness_validation.py", "verify_nra_source_results.py",
        "verify_source_results.py", "requirements-source-checks.txt")

    internal data class Input(val id: String, val source: ByteArray, val model: ByteArray)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1 && args[0].startsWith("request64=")) { "one request64 argument is required" }
        val encoded = args[0].substringAfter('=')
        val inputs = decode(encoded)
        val inherited = File(requireNotNull(System.getenv("OUT")))
        require(inherited.isDirectory) { "OUT must be the profile's existing output directory" }
        val out = Files.createDirectory(inherited.toPath().resolve("smt-witness-validation")).toFile()
        val request = Base64.getDecoder().decode(encoded)
        write(out.resolve("request.json"), request)
        write(out.resolve("summary.json"), buildJsonObject {
            put("schema", "smt-original-source-ground-witness-validation-v1")
            put("requestSha256", hash(request))
            put("allAccepted", false)
            put("groundOnly", true)
            put("solverSearchQueries", 0)
            put("proofCertificateChecked", false)
            put("results", buildJsonArray {
                for ((index, input) in inputs.withIndex()) add(buildJsonObject {
                    put("index", index)
                    put("id", input.id)
                    put("sourceSha256", hash(input.source))
                    put("modelSha256", hash(input.model))
                    put("state", "notReached")
                })
            })
        }.toString().toByteArray())
        val tools = out.resolve("tools").apply { check(mkdir()) }
        val hashes = buildJsonObject {
            for (name in resourceNames) {
                val raw = SmtWitnessValidation::class.java.getResourceAsStream(RESOURCE_ROOT + name)
                    .use { requireNotNull(it).readNBytes(1024 * 1024 + 1) }
                check(raw.size <= 1024 * 1024) { "packaged tool is oversized" }
                write(tools.resolve(name), raw)
                put(name, hash(raw))
            }
        }
        for ((index, input) in inputs.withIndex()) {
            val directory = out.resolve("case-$index").apply { check(mkdir()) }
            write(directory.resolve("source.smt2"), input.source)
            write(directory.resolve("model.smt2"), input.model)
        }
        write(out.resolve("tools.json"), hashes.toString().toByteArray())
        write(out.resolve("jvm-runtime.json"), buildJsonObject {
            put("javaVersion", System.getProperty("java.version"))
            put("javaVendor", System.getProperty("java.vendor"))
            put("osName", System.getProperty("os.name"))
            put("osArch", System.getProperty("os.arch"))
            put("parentPid", ProcessHandle.current().pid())
            put("runtimeFiles", buildJsonObject {
                val home = File(System.getProperty("java.home"))
                for (relative in listOf("release", "bin/java", "lib/modules")) {
                    val file = home.resolve(relative)
                    require(file.isFile) { "runtime identity file is missing" }
                    put(relative, hashFile(file))
                }
            })
            put("classpath", buildJsonArray {
                for (name in System.getProperty("java.class.path").split(File.pathSeparator)) {
                    val file = File(name)
                    if (file.isFile) add(buildJsonObject {
                        put("name", file.name)
                        put("bytes", file.length())
                        put("sha256", hashFile(file))
                    })
                }
            })
        }.toString().toByteArray())
        val gate = Any()
        var active: Process? = null
        val stopping = AtomicBoolean()
        val shutdown = Thread {
            stopping.set(true)
            synchronized(gate) {
                active?.let { process ->
                    process.destroy()
                    if (!process.waitFor(REAP_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
                    process.waitFor(REAP_SECONDS, TimeUnit.SECONDS)
                }
            }
        }
        Runtime.getRuntime().addShutdownHook(shutdown)
        val started = System.nanoTime()
        var timedOut = false
        var exit: Int? = null
        try {
            synchronized(gate) {
                check(!stopping.get())
                active = ProcessBuilder("python3", "-I", tools.resolve("smt_witness_validation.py").absolutePath,
                    "supervise", out.absolutePath, hash(request), hashes.toString(),
                    ProcessHandle.current().pid().toString())
                    .inheritIO().start()
            }
            val process = checkNotNull(active)
            timedOut = !process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (timedOut) {
                process.destroy()
                if (!process.waitFor(REAP_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
                process.waitFor(REAP_SECONDS, TimeUnit.SECONDS)
            }
            if (!process.isAlive) exit = process.exitValue()
        } finally {
            synchronized(gate) {
                active?.let { process ->
                    if (process.isAlive) process.destroy()
                    if (!process.waitFor(REAP_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
                    process.waitFor(REAP_SECONDS, TimeUnit.SECONDS)
                }
                active = null
                write(out.resolve("supervisor-process.json"), buildJsonObject {
                    put("exit", exit?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
                    put("timedOut", timedOut)
                    put("parentStopped", stopping.get())
                    put("processWallNs", System.nanoTime() - started)
                    put("solverSearchQueries", 0)
                }.toString().toByteArray())
                runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
            }
        }
        check(exit == 0 && !timedOut && !stopping.get()) {
            "ground validation incomplete; retained outputs describe each case"
        }
    }

    internal fun decode(encoded: String): List<Input> {
        require(encoded.length <= MAX_REQUEST_BYTES) { "request is oversized" }
        val bytes = Base64.getDecoder().decode(encoded)
        require(Base64.getEncoder().encodeToString(bytes) == encoded) { "request base64 is not canonical" }
        val text = bytes.decodeToString(throwOnInvalidSequence = true)
        val request = Json.parseToJsonElement(text).jsonObject
        require(request.toString() == text) { "request JSON must be canonical, without duplicate keys" }
        require(request.keys == setOf("schema", "cases"))
        require(string(request, "schema") == "smt-ground-witness-request-v1")
        val cases = request.getValue("cases").jsonArray
        require(cases.size in 1..MAX_CASES)
        val inputs = cases.map { element ->
            val row = element.jsonObject
            require(row.keys == setOf("id", "source", "model"))
            val id = string(row, "id")
            require(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}").matches(id))
            Input(id, payload(row.getValue("source").jsonObject, source = true),
                payload(row.getValue("model").jsonObject))
        }
        require(inputs.map { it.id }.distinct().size == inputs.size) { "case ids must be unique" }
        return inputs
    }

    private fun string(value: JsonObject, key: String): String {
        val primitive = value.getValue(key).jsonPrimitive
        require(primitive.isString)
        return primitive.content
    }

    private fun payload(objectValue: JsonObject, source: Boolean = false): ByteArray {
        val compressed = "encoding" in objectValue
        require(objectValue.keys == if (compressed) setOf("bytes", "sha256", "base64", "encoding")
            else setOf("bytes", "sha256", "base64"))
        if (compressed) require(string(objectValue, "encoding") == "gzip") { "unsupported payload encoding" }
        val count = objectValue.getValue("bytes").jsonPrimitive
        val size = requireNotNull(count.intOrNull)
        val limit = if (compressed && source) MAX_GZIP_SOURCE_BYTES else MAX_PAYLOAD_BYTES
        require(!count.isString && size in 1..limit)
        val expected = string(objectValue, "sha256")
        require(Regex("[0-9a-f]{64}").matches(expected))
        val encoded = string(objectValue, "base64")
        require(encoded.length <= MAX_REQUEST_BYTES)
        val transport = Base64.getDecoder().decode(encoded)
        require(Base64.getEncoder().encodeToString(transport) == encoded)
        val raw = if (compressed) try {
            GZIPInputStream(transport.inputStream()).use { it.readNBytes(size + 1) }
        } catch (error: IOException) {
            throw IllegalArgumentException("invalid gzip payload", error)
        } else transport
        require(raw.size == size && hash(raw) == expected) { "payload bytes or hash differ" }
        return raw
    }

    internal fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(Locale.ROOT, it) }

    private fun hashFile(file: File): String {
        require(file.length() <= 256L * 1024 * 1024) { "classpath file is oversized" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    private fun write(file: File, bytes: ByteArray) {
        FileChannel.open(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }
}
