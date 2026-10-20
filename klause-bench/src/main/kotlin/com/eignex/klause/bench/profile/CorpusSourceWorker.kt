package com.eignex.klause.bench.profile

import com.eignex.klause.bench.source.CorpusFiles
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The capture's own stop: its deadline passed or its thread was interrupted. */
internal class CaptureStopped(message: String) : RuntimeException(message)

/** An opened archive download: the URL that answered, its declared length and the body. */
internal class ArchiveResponse(val finalUrl: String, val declaredBytes: Long?, val body: InputStream) :
    Closeable by body

/**
 * Copies requested original members out of `.tar.zst` collection archives into the capture directory, without
 * parsing the source or extracting anything else. Every transfer and copy is byte-bounded and hashed, and each one
 * leaves a durable receipt whether it succeeds or not, so a failed capture still shows how far it got.
 */
@Suppress("TooGenericExceptionCaught")
internal class CorpusSourceWorker(
    private val out: File,
    private val checkpoint: () -> Unit = deadline(SECONDS),
    private val open: (String) -> ArchiveResponse = { openHttps(it) },
) {
    internal class Allowance(val limit: Long) {
        var used = 0L
        val remaining: Long get() = limit - used
    }

    internal data class SourceCopy(val bytes: Long, val sha256: String)

    private class Case(val resolved: JsonObject) {
        val index = resolved.getValue("index").jsonPrimitive.int
        val archiveUrl = resolved.getValue("archiveUrl").jsonPrimitive.content
        val member = resolved.getValue("member").jsonPrimitive.content
        val expectedSha256 = resolved.getValue("expectedSha256").jsonPrimitive.content
        var state = NOT_REACHED
        val issues = ArrayList<String>()
        var copy: SourceCopy? = null
        var partialPath: String? = null
        var path: String? = null
        var partialBytes: Long? = null

        fun json(): JsonObject = buildJsonObject {
            resolved.forEach { (key, value) -> put(key, value) }
            put("state", state)
            put("issues", JsonArray(issues.map { JsonPrimitive(it) }))
            copy?.let {
                put("bytes", it.bytes)
                put("sha256", it.sha256)
            }
            partialPath?.let { put("partialPath", it) }
            path?.let { put("path", it) }
            partialBytes?.let { put("partialBytes", it) }
        }
    }

    private class Archive(url: String) {
        val fields = linkedMapOf<String, JsonElement>("url" to JsonPrimitive(url), "complete" to JsonPrimitive(false))
        val issues = ArrayList<String>()

        fun json() = JsonObject(fields + ("issues" to JsonArray(issues.map { JsonPrimitive(it) })))
    }

    val downloads = Allowance(MAX_DOWNLOADS)
    val sources = Allowance(MAX_SOURCES)
    private val receiptSizes = HashMap<String, Long>()

    /** Capture every resolved case, grouped by archive. True when every case was captured. */
    fun capture(cases: List<JsonObject>): Boolean {
        val results = cases.map(::Case)
        writeJson(out.resolve("planned-results.json"), buildJsonObject {
            put("allRequestedSourcesCaptured", false)
            put("results", JsonArray(results.map { it.json() }))
        })
        val archives = ArrayList<Archive>()
        val issues = ArrayList<String>()
        try {
            for ((index, group) in results.groupBy { it.archiveUrl }.entries.withIndex()) {
                checkpoint()
                if (downloads.remaining <= 0 || sources.remaining <= 0) {
                    throw CaptureStopped("containing transfer byte allowance exhausted")
                }
                val archive = Archive(group.key).also { archives += it }
                captureArchive(index, group.value, archive) { writeProgress(results, archives) }
            }
        } catch (error: Exception) {
            issues += errorText(error)
            results.filter { it.state == NOT_REACHED }.forEach { it.issues += errorText(error) }
        }
        val captured = results.all { it.state == CAPTURED } && issues.isEmpty()
        writeJson(out.resolve("capture.json"), buildJsonObject {
            put("schema", "corpus-original-source-byte-capture-v1")
            put("results", JsonArray(results.map { it.json() }))
            put("archives", JsonArray(archives.map { it.json() }))
            put("allRequestedSourcesCaptured", captured)
            put("sourceOnly", true)
            put("solverCalls", 0)
            put("sourceParserCalls", 0)
            put("checkerCalls", 0)
            put("sourceBytesRetained", sources.used)
            put("downloadBytes", downloads.used)
            put("issues", JsonArray(issues.map { JsonPrimitive(it) }))
            put("limits", limits())
        })
        return captured
    }

    private fun captureArchive(index: Int, planned: List<Case>, archive: Archive, progress: () -> Unit) {
        val packed = out.resolve("archive-$index.tar${CorpusFiles.SUFFIX}")
        val cleanupErrors = ArrayList<String>()
        try {
            requireHttps(archive.fields.getValue("url").jsonPrimitive.content)
            archive.fields["transferPath"] = JsonPrimitive(receipt(packed).name)
            open(archive.fields.getValue("url").jsonPrimitive.content).use { response ->
                requireHttps(response.finalUrl)
                archive.fields["finalUrl"] = JsonPrimitive(response.finalUrl)
                archive.fields.putAll(transferArchive(response.body, response.declaredBytes, packed))
            }
            val retained = copyMembers(packed, planned, archive)
            checkpoint()
            archive.fields["complete"] = JsonPrimitive(true)
            for (case in retained) {
                checkpoint()
                val source = out.resolve("source-${case.index}.smt2")
                Files.move(out.resolve(requireNotNull(case.partialPath)).toPath(), source.toPath())
                case.state = CAPTURED
                case.path = source.name
            }
            for (case in planned.filter { it.state != CAPTURED }) {
                case.state = FAILED
                case.issues.clear()
                case.issues += "requested member absent from archive"
            }
            Files.delete(packed.toPath())
            archive.fields["archiveRawRetained"] = JsonPrimitive(false)
        } catch (error: Exception) {
            val issue = errorText(error)
            archive.fields["complete"] = JsonPrimitive(false)
            archive.issues += issue
            archive.fields["archiveRawRetained"] = JsonPrimitive(packed.exists())
            for (case in planned) {
                case.state = FAILED
                case.issues += issue
            }
        } finally {
            attempt(cleanupErrors, "transfer receipt read") {
                val transfer = receipt(packed)
                if (transfer.isFile) {
                    archive.fields.putAll(Json.parseToJsonElement(transfer.readText()).jsonObject)
                } else {
                    archive.fields["transportComplete"] = JsonPrimitive(false)
                    archive.fields["transferUnavailable"] = JsonPrimitive("transfer receipt unavailable")
                }
            }
            archive.fields["retainedArchiveBytes"] = JsonPrimitive(if (packed.exists()) packed.length() else 0L)
            for (case in planned) {
                val partial = out.resolve("source-${case.index}.part")
                case.partialBytes = if (partial.exists()) partial.length() else 0L
            }
            attempt(cleanupErrors, "progress receipt", progress)
            if (cleanupErrors.isNotEmpty()) {
                archive.fields["complete"] = JsonPrimitive(false)
                archive.issues += cleanupErrors
                for (case in planned) {
                    case.state = FAILED
                    case.issues += "collection cleanup or durability incomplete"
                }
            }
        }
        if (cleanupErrors.isNotEmpty()) throw CaptureStopped("collection cleanup or durability incomplete")
    }

    /** Streams the archive's tar listing and copies each requested member to its indexed partial file. */
    private fun copyMembers(packed: File, planned: List<Case>, archive: Archive): List<Case> {
        val byMember = planned.associateBy { it.member }
        val seen = HashSet<String>()
        val retained = ArrayList<Case>()
        var members = 0
        // Closing the decoder after a full drain checks its exit status, so a truncated archive fails here.
        DecodedStream(CorpusFiles.open(packed)).use { decoded ->
            val tar = TarStream(decoded)
            while (true) {
                val member = tar.next() ?: break
                checkpoint()
                members++
                require(members <= MAX_MEMBERS) { "archive member count cap reached" }
                if (member.isDirectory && member.name in setOf(".", "./")) continue
                val name = canonicalMember(member.name)
                val case = byMember[name] ?: continue
                require(seen.add(name)) { "duplicate requested archive member" }
                require(member.isRegular && member.size <= MAX_SOURCE) {
                    "requested source is not a bounded regular member"
                }
                val partial = out.resolve("source-${case.index}.part")
                case.partialPath = partial.name
                val copy = copySource(member.content, partial, case.expectedSha256)
                require(copy.bytes == member.size) { "source size differs from archive member" }
                case.copy = copy
                retained += case
            }
            val rest = ByteArray(BLOCK)
            while (decoded.read(rest) >= 0) continue
            archive.fields["decodedBytes"] = JsonPrimitive(decoded.bytes)
            archive.fields["memberCount"] = JsonPrimitive(members)
        }
        return retained
    }

    /** Copy one source into [destination], accepting it only when its bytes hash to [expected]. */
    fun copySource(input: InputStream, destination: File, expected: String, maximum: Long = MAX_SOURCE): SourceCopy {
        val digest = MessageDigest.getInstance("SHA-256")
        var sha256: String? = null
        var received = 0L
        var complete = false
        var matched = false
        var output: FileOutputStream? = null
        var primary: Throwable? = null
        val errors = ArrayList<String>()
        try {
            output = createExclusive(destination)
            val buffer = ByteArray(BLOCK)
            while (true) {
                checkpoint()
                val size = input.read(buffer, 0, minOf(BLOCK.toLong(), maximum - received + 1).toInt())
                if (size < 0) {
                    complete = true
                    break
                }
                val allowed = minOf(size.toLong(), maximum - received, sources.remaining).coerceAtLeast(0).toInt()
                output.write(buffer, 0, allowed)
                digest.update(buffer, 0, allowed)
                received += allowed
                sources.used += allowed
                require(allowed == size) { "source byte cap reached" }
            }
            sha256 = hex(digest.digest())
            matched = sha256 == expected
            require(matched) { "original source hash differs" }
        } catch (error: Throwable) {
            primary = error
        } finally {
            output?.let { closeDurably(it, errors) }
            val receivedSha256 = sha256 ?: hex(digest.digest())
            sha256 = receivedSha256
            val copyError = primary?.let(::errorText)
            val durabilityErrors = errors.toList()
            attempt(errors, "source receipt") {
                writeJson(receipt(destination), buildJsonObject {
                    put("expectedSha256", expected)
                    put("receivedBytes", received)
                    put("receivedSha256", receivedSha256)
                    put("streamComplete", complete)
                    put("hashMatched", matched)
                    put("durabilityComplete", durabilityErrors.isEmpty())
                    put("copyError", copyError)
                    put("durabilityErrors", JsonArray(durabilityErrors.map { JsonPrimitive(it) }))
                })
            }
        }
        preserveFailure(primary, errors, "source prefix durability failed")
        return SourceCopy(received, requireNotNull(sha256))
    }

    /**
     * Download an archive into [destination], retaining at most [maximum] bytes. Bytes read past a cap are still
     * debited from [downloads] and reported as omitted, since they crossed the network.
     */
    fun transferArchive(
        input: InputStream, declared: Long?, destination: File, maximum: Long = MAX_ARCHIVE,
    ): JsonObject {
        val digest = MessageDigest.getInstance("SHA-256")
        var retained = 0L
        var observed = 0L
        var eof = false
        var complete = false
        var output: FileOutputStream? = null
        var primary: Throwable? = null
        val errors = ArrayList<String>()
        var result = JsonObject(emptyMap())
        try {
            output = createExclusive(destination)
            require(declared == null || declared in 0..maximum) { "declared archive size exceeds cap" }
            val buffer = ByteArray(BLOCK)
            while (true) {
                checkpoint()
                val request = minOf(BLOCK.toLong(), maximum - retained + 1, downloads.remaining + 1)
                val size = input.read(buffer, 0, request.coerceAtLeast(1).toInt())
                if (size < 0) {
                    eof = true
                    break
                }
                observed += size
                val allowed = minOf(size.toLong(), maximum - retained, downloads.remaining).coerceAtLeast(0).toInt()
                downloads.used += size
                output.write(buffer, 0, allowed)
                digest.update(buffer, 0, allowed)
                retained += allowed
                require(allowed == size) { "archive transfer byte cap reached" }
            }
            require(declared == null || retained == declared) { "archive HTTP length differs" }
            complete = true
        } catch (error: Throwable) {
            primary = error
        } finally {
            output?.let { closeDurably(it, errors) }
            result = buildJsonObject {
                put("downloadBytes", observed)
                put("observedBytes", observed)
                put("retainedBytes", retained)
                put("omittedBytes", observed - retained)
                put("sha256", hex(digest.digest()))
                put("streamEof", eof)
                put("declaredBytes", declared)
                put("transportComplete", complete && errors.isEmpty())
                put("transportError", primary?.let(::errorText))
                put("durabilityErrors", JsonArray(errors.map { JsonPrimitive(it) }))
            }
            attempt(errors, "transfer receipt") { writeJson(receipt(destination), result) }
        }
        preserveFailure(primary, errors, "archive prefix durability failed")
        return result
    }

    private fun writeProgress(results: List<Case>, archives: List<Archive>) =
        writeJson(out.resolve("progress.json"), buildJsonObject {
            put("results", JsonArray(results.map { it.json() }))
            put("archives", JsonArray(archives.map { it.json() }))
        })

    /** Write a receipt durably, within the per-receipt and aggregate receipt byte caps. */
    private fun writeJson(file: File, value: JsonObject) {
        val raw = (PRETTY.encodeToString(JsonObject.serializer(), value) + "\n").toByteArray()
        require(raw.size <= MAX_RECEIPT) { "receipt byte cap reached" }
        val others = receiptSizes.values.sum() - (receiptSizes[file.path] ?: 0L)
        require(others + raw.size <= MAX_RECEIPTS) { "aggregate receipt byte cap reached" }
        receiptSizes[file.path] = raw.size.toLong()
        val errors = ArrayList<String>()
        var primary: Throwable? = null
        val output = FileOutputStream(file)
        try {
            output.write(raw)
        } catch (error: Throwable) {
            primary = error
        } finally {
            closeDurably(output, errors)
        }
        preserveFailure(primary, errors, "receipt durability failed")
    }

    /** Counts decoded archive bytes against [MAX_DECODED] and honours the capture's stop on every read. */
    private inner class DecodedStream(input: InputStream) : FilterInputStream(input) {
        var bytes = 0L

        override fun read(): Int {
            val single = ByteArray(1)
            return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and BYTE_MASK
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            checkpoint()
            val size = super.read(b, off, minOf(len.toLong(), MAX_DECODED - bytes + 1).toInt())
            if (size > 0) bytes += size
            if (bytes > MAX_DECODED) throw IOException("decoded archive byte cap reached")
            return size
        }

        override fun skip(n: Long): Long {
            val buffer = ByteArray(minOf(n, BLOCK.toLong()).coerceAtLeast(0).toInt())
            val size = if (buffer.isEmpty()) 0 else read(buffer, 0, buffer.size)
            return size.coerceAtLeast(0).toLong()
        }
    }

    internal companion object {
        const val SECONDS = 1200L
        const val MAX_ARCHIVE = 256L * 1024 * 1024
        const val MAX_DOWNLOADS = 512L * 1024 * 1024
        const val MAX_DECODED = 8L * 1024 * 1024 * 1024
        const val MAX_SOURCE = 32L * 1024 * 1024
        const val MAX_SOURCES = 128L * 1024 * 1024
        const val MAX_MEMBERS = 200_000
        const val MAX_RECEIPT = 4 * 1024 * 1024
        const val MAX_RECEIPTS = 32L * 1024 * 1024
        const val NETWORK_TIMEOUT_MS = 30_000
        private const val BLOCK = 65536
        private const val BYTE_MASK = 0xff
        private const val MAX_ERROR_TEXT = 2048
        private const val HTTP_OK = 200
        private const val NOT_REACHED = "notReached"
        private const val CAPTURED = "captured"
        private const val FAILED = "failed"
        private val PRETTY = Json { prettyPrint = true }

        fun limits(): JsonObject = buildJsonObject {
            put("seconds", SECONDS)
            put("networkReadTimeoutMs", NETWORK_TIMEOUT_MS)
            put("archiveBytes", MAX_ARCHIVE)
            put("downloadBytes", MAX_DOWNLOADS)
            put("decodedArchiveBytes", MAX_DECODED)
            put("sourceBytes", MAX_SOURCE)
            put("totalSourceBytes", MAX_SOURCES)
            put("members", MAX_MEMBERS)
            put("receiptBytes", MAX_RECEIPT)
            put("totalReceiptBytes", MAX_RECEIPTS)
        }

        fun deadline(seconds: Long): () -> Unit {
            val end = System.nanoTime() + seconds * 1_000_000_000L
            return {
                if (Thread.currentThread().isInterrupted || System.nanoTime() - end >= 0) {
                    throw CaptureStopped("containing capture stopped")
                }
            }
        }

        /** The archive path with a leading `./` removed, refusing names that could leave the archive root. */
        fun canonicalMember(raw: String): String {
            var name = raw
            while (name.startsWith("./")) name = name.substring(2)
            require(name.isNotEmpty() && !name.startsWith("/") && '\\' !in name && '\u0000' !in name) {
                "unsafe archive member"
            }
            val parts = name.trimEnd('/').split('/')
            require(parts.none { it.isEmpty() || it == "." || it == ".." }) { "unsafe archive member" }
            return parts.joinToString("/")
        }

        fun requireHttps(url: String) {
            val uri = try {
                URI(url)
            } catch (error: URISyntaxException) {
                throw IllegalArgumentException("HTTPS URL without credentials required", error)
            }
            require(uri.scheme == "https" && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null) {
                "HTTPS URL without credentials required"
            }
        }

        /** Opens [url] without inherited proxies. [HttpURLConnection] never follows a redirect across
         *  protocols, so a downgrade to plain HTTP surfaces as a non-200 status. */
        fun openHttps(url: String): ArchiveResponse {
            requireHttps(url)
            val connection = URI(url).toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
            try {
                connection.connectTimeout = NETWORK_TIMEOUT_MS
                connection.readTimeout = NETWORK_TIMEOUT_MS
                connection.instanceFollowRedirects = true
                require(connection.responseCode == HTTP_OK) { "archive HTTP status differs" }
                val declared = connection.contentLengthLong.takeIf { it >= 0 }
                val body = object : FilterInputStream(connection.inputStream) {
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            connection.disconnect()
                        }
                    }
                }
                return ArchiveResponse(connection.url.toString(), declared, body)
            } catch (error: Throwable) {
                connection.disconnect()
                throw error
            }
        }

        fun errorText(error: Throwable): String =
            "${error::class.simpleName}: ${error.message}".take(MAX_ERROR_TEXT)

        fun receipt(file: File): File = file.resolveSibling(file.name.substringBefore('.') + ".transfer.json")

        private fun createExclusive(file: File): FileOutputStream {
            if (!file.createNewFile()) throw IOException("$file already exists")
            return FileOutputStream(file)
        }

        private inline fun attempt(errors: MutableList<String>, label: String, action: () -> Unit) {
            try {
                action()
            } catch (error: Exception) {
                errors += "$label: ${errorText(error)}"
            }
        }

        private fun closeDurably(output: FileOutputStream, errors: MutableList<String>) {
            attempt(errors, "flush") { output.flush() }
            attempt(errors, "fsync") { output.fd.sync() }
            attempt(errors, "close") { output.close() }
        }

        /** Rethrow [primary] with every cleanup failure attached, or report cleanup failures on their own. */
        private fun preserveFailure(primary: Throwable?, errors: List<String>, message: String) {
            if (primary != null) {
                errors.forEach { primary.addSuppressed(IOException(it)) }
                throw primary
            }
            if (errors.isNotEmpty()) throw IOException("$message: ${errors.joinToString("; ")}")
        }

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(Locale.ROOT, it) }
    }
}
