package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.report.Reports
import java.io.File
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.Json

internal object XcspArchiveReplay {
    internal const val MAX_COMPRESSED_BYTES = 60 * 1024
    internal const val MAX_REQUEST_BYTES = 2 * 1024 * 1024

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Supply one base64 gzip JSON request" }
        val bytes = decode(args.single())
        val out = File(requireNotNull(System.getenv("OUT")))
        require(out.isDirectory)
        val request = out.resolve("request.json").apply { writeBytes(bytes) }
        Reports.writeJson(out.resolve("harness-build.json").path, InstalledBuild.reference)
        val resource = "/xcsp-archive-replay/replay.py"
        val worker = out.resolve("replay.py").apply {
            val stream = requireNotNull(XcspArchiveReplay::class.java.getResourceAsStream(resource))
            writeBytes(stream.use { it.readBytes() })
        }
        CorpusSourceCapture.execute(
            listOf("/usr/bin/setsid", "/usr/bin/python3", "-I", worker.path, request.path, out.path,
                System.getProperty("java.home"), System.getProperty("java.class.path")),
            out,
        )
    }

    internal fun decode(encoded: String): ByteArray {
        require(encoded.length <= MAX_COMPRESSED_BYTES * 4 / 3 + 4) { "Compressed request is oversized" }
        val compressed = Base64.getDecoder().decode(encoded)
        require(compressed.size <= MAX_COMPRESSED_BYTES)
        val bytes = GZIPInputStream(compressed.inputStream()).use { it.readNBytes(MAX_REQUEST_BYTES + 1) }
        require(bytes.size <= MAX_REQUEST_BYTES) { "Expanded request is oversized" }
        val source = bytes.toString(Charsets.UTF_8)
        require(source.toByteArray().contentEquals(bytes)) { "Request must be UTF-8" }
        require(Json.parseToJsonElement(source).toString() == source) { "Request must be canonical JSON" }
        return bytes
    }
}
