package com.eignex.klause.bench.source

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.HexFormat

@Serializable
internal data class MirrorPublication(val url: String, val headers: Map<String, String> = emptyMap())

@Serializable
private data class MirrorSignature(val method: String, val key: String, val sha256: String)

internal fun corpusMirrorPublication(id: String, sha: ByteArray, fallback: URI): MirrorPublication = corpusTransport {
    val signer = System.getenv("LAB_INPUT_SIGNER") ?: System.getenv("KLAUSE_BENCH_CORPUS_SIGNER")
        ?: return@corpusTransport MirrorPublication(fallback.toString())
    val uri = URI(signer)
    require(uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost", "::1") && uri.userInfo == null)
    val checksum = HexFormat.of().formatHex(sha)
    val signature = MirrorSignature("PUT", "corpus-packs/$id.tar", checksum)
    val body = Json.encodeToString(MirrorSignature.serializer(), signature).toByteArray()
    val connection = uri.toURL().openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "POST"
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(body.size)
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body) }
        if (connection.responseCode != 200) throw IOException("corpus cache signing unavailable")
        val reply = connection.inputStream.use { it.readNBytes(16 * 1024 + 1) }
        require(reply.size <= 16 * 1024)
        Json.decodeFromString<MirrorPublication>(String(reply)).also {
            require(URI(it.url).scheme == "https" && URI(it.url).userInfo == null)
        }
    } finally {
        connection.disconnect()
    }
}
