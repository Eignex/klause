package com.eignex.klause.bench.profile

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal object JvmBytecodeReport {
    const val MAX_LOGICAL_BYTES = 4L * 1024 * 1024 * 1024
    const val MAX_COMPRESSED_BYTES = 512L * 1024 * 1024
    private const val MAX_ROW_BYTES = 128 * 1024 * 1024

    @Suppress("LongMethod", "TooGenericExceptionCaught")
    fun write(
        out: File,
        maximumLogicalBytes: Long = MAX_LOGICAL_BYTES,
        maximumCompressedBytes: Long = MAX_COMPRESSED_BYTES,
        action: ((JsonObject) -> Unit) -> JsonObject,
    ): JsonObject {
        require(maximumLogicalBytes in 1..MAX_LOGICAL_BYTES)
        require(maximumCompressedBytes in 1..MAX_COMPRESSED_BYTES)
        val report = File(out, "bytecode-metadata.jsonl.gz")
        val partial = File(out, "bytecode-metadata.partial.jsonl.gz")
        check(!report.exists() && !partial.exists())
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        var records = 0L
        val summary: JsonObject
        val uncompressedHash: String
        try {
            summary = report.outputStream().buffered().use { target ->
                GZIPOutputStream(BytecodeReportOutput(target, maximumCompressedBytes)).use { output ->
                    fun emit(row: JsonObject) {
                        val text = row.toString() + "\n"
                        check(text.length <= MAX_ROW_BYTES) { "Bytecode record exceeds bounds" }
                        val raw = text.encodeToByteArray()
                        check(raw.decodeToString(throwOnInvalidSequence = true) == text) {
                            "Bytecode record cannot retain its text in UTF-8"
                        }
                        check(raw.size <= MAX_ROW_BYTES && bytes <= maximumLogicalBytes - raw.size) {
                            "Whole bytecode report exceeds bounds"
                        }
                        output.write(raw)
                        digest.update(raw)
                        bytes += raw.size
                        records++
                    }
                    emit(buildJsonObject {
                        put("record", "schema")
                        put("encoding", JvmBytecodeEncoding.ENCODING)
                        put("columns", JsonObject(JvmBytecodeEncoding.columns.mapValues { (_, names) ->
                            JsonArray(names.map(::JsonPrimitive))
                        }))
                    })
                    action(::emit).also { result ->
                        emit(JsonObject(linkedMapOf("record" to JsonPrimitive("inventory-end")) + result))
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
                    check(verifiedBytes <= maximumLogicalBytes - count)
                    verifiedBytes += count
                    roundTrip.update(buffer, 0, count)
                }
            }
            val originalHash = digest.digest()
            check(verifiedBytes == bytes && originalHash.contentEquals(roundTrip.digest()))
            uncompressedHash = HexFormat.of().formatHex(originalHash)
        } catch (failure: Throwable) {
            if (report.isFile && !report.renameTo(partial)) {
                failure.addSuppressed(IllegalStateException("Cannot retain partial bytecode report"))
            }
            throw failure
        }
        return JsonObject(summary + buildJsonObject {
            put("reportFile", report.name)
            put("reportEncoding", JvmBytecodeEncoding.ENCODING)
            put("reportBytes", report.length())
            put("reportSha256", JvmBytecodeInputs.digest(report))
            put("uncompressedReportBytes", bytes)
            put("uncompressedReportSha256", uncompressedHash)
            put("records", records)
            put("completeGzipRoundTripVerified", true)
        })
    }
}

private class BytecodeReportOutput(private val target: OutputStream, private val maximum: Long) : OutputStream() {
    private var bytes = 0L

    override fun write(value: Int) {
        reserve(1)
        target.write(value)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        reserve(length)
        target.write(buffer, offset, length)
    }

    private fun reserve(length: Int) {
        check(length >= 0 && bytes <= maximum - length) { "Compressed bytecode report exceeds bounds" }
        bytes += length
    }
}
