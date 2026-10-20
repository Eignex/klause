package com.eignex.klause.bench.profile

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal object JvmSavedReports {
    fun readObject(file: File, proof: JsonObject): JsonObject {
        require(savedIdentity(file) == savedExpectedIdentity(proof.getValue("compressed").jsonObject))
        val expected = savedExpectedIdentity(proof.getValue("uncompressed").jsonObject)
        val maximum = expected.savedLong("size")
        require(maximum <= MAX_ROW_BYTES) { "Complete JSON report exceeds record bound" }
        val bytes = ByteArrayOutputStream(minOf(maximum, 64 * 1024L).toInt())
        GZIPInputStream(Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).buffered()).use { source ->
            val buffer = ByteArray(64 * 1024)
            var count = 0L
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                count += read
                require(count <= maximum) { "Complete JSON report exceeds its original byte identity" }
                bytes.write(buffer, 0, read)
            }
        }
        val raw = bytes.toByteArray()
        require(identity(raw) == expected) { "Complete JSON report identity differs" }
        return JvmSavedJson.parse(raw.decodeToString(throwOnInvalidSequence = true)).jsonObject
    }

    fun read(file: File, proof: JsonObject, consume: (Long, ByteArray) -> Unit) {
        require(savedIdentity(file) == savedExpectedIdentity(proof)) { "Compressed report identity differs" }
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        var records = 0L
        GZIPInputStream(file.inputStream().buffered()).use { source ->
            val buffer = ByteArray(64 * 1024)
            val row = ByteArrayOutputStream()
            fun emit() {
                val bytes = row.toByteArray()
                require(bytes.any { it != 32.toByte() && it.toInt() !in 9..13 })
                digest.update(bytes)
                consume(records++, bytes)
                row.reset()
            }
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                size += count
                require(size <= MAX_LOGICAL_BYTES) { "Complete logical report exceeds bounds" }
                var start = 0
                for (index in 0 until count) {
                    if (buffer[index] == 10.toByte()) {
                        require(row.size().toLong() + index + 1 - start <= MAX_ROW_BYTES)
                        row.write(buffer, start, index + 1 - start)
                        emit()
                        start = index + 1
                    }
                }
                require(row.size().toLong() + count - start <= MAX_ROW_BYTES)
                row.write(buffer, start, count - start)
            }
            if (row.size() > 0) emit()
        }
        require(
            size == proof.savedLong("uncompressedBytes") && records == proof.savedLong("records") &&
                HexFormat.of().formatHex(digest.digest()) == proof.savedString("uncompressedSha256"),
        ) {
            "Complete logical report identity or census differs"
        }
    }

    fun identity(bytes: ByteArray): JsonObject = buildJsonObject {
        put("size", bytes.size)
        put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
    }

    const val MAX_LOGICAL_BYTES = 4L * 1024 * 1024 * 1024
    const val MAX_ROW_BYTES = 128L * 1024 * 1024
}

internal class JvmSavedReportBudget {
    private var logical = 0L
    private var compressed = 0L

    fun reserveLogical(count: Int) {
        logical += count
        require(logical <= JvmSavedReports.MAX_LOGICAL_BYTES) { "Complete logical output set exceeds bounds" }
    }

    fun reserveCompressed(count: Int) {
        compressed += count
        require(compressed <= 512L * 1024 * 1024) { "Complete compressed output set exceeds bounds" }
    }
}

internal class JvmSavedReport(private val file: File, private val budget: JvmSavedReportBudget) : AutoCloseable {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var size = 0L
    private var records = 0L
    private var failed = false
    private var closed = false
    private val target = Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE_NEW)
    private val compressed = object : FilterOutputStream(target) {
        override fun write(value: Int) {
            budget.reserveCompressed(1)
            out.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            budget.reserveCompressed(length)
            out.write(bytes, offset, length)
        }
    }
    private val gzip = object : GZIPOutputStream(compressed, 64 * 1024) {
        init {
            def.setLevel(1)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun write(value: JsonObject) {
        check(!failed && !closed)
        try {
            val bytes = (value.toString() + "\n").encodeToByteArray()
            budget.reserveLogical(bytes.size)
            gzip.write(bytes)
            digest.update(bytes)
            size += bytes.size
            records++
        } catch (failure: Throwable) {
            failed = true
            throw failure
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override fun close() {
        if (!closed) {
            try {
                gzip.close()
            } catch (failure: Throwable) {
                failed = true
                throw failure
            } finally {
                closed = true
                target.close()
            }
        }
    }

    fun verify(): JsonObject {
        close()
        check(!failed) { "A failed output cannot admit its prefix" }
        val proof = buildJsonObject {
            put("file", file.name)
            put("records", records)
            put("uncompressedBytes", size)
            put("uncompressedSha256", HexFormat.of().formatHex(digest.digest()))
            put("completeGzipRoundTripVerified", true)
            savedIdentity(file).forEach { (key, value) -> put(key, value) }
        }
        val roundTrip = MessageDigest.getInstance("SHA-256")
        var verified = 0L
        GZIPInputStream(file.inputStream().buffered()).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                verified += count
                require(verified <= JvmSavedReports.MAX_LOGICAL_BYTES)
                roundTrip.update(buffer, 0, count)
            }
        }
        require(
            verified == size && HexFormat.of().formatHex(roundTrip.digest()) ==
            proof.savedString("uncompressedSha256")
        ) { "Complete output does not round trip" }
        return proof
    }

    fun retainPartial() {
        runCatching { close() }
        if (file.exists()) {
            val partial = File(file.parentFile, file.name.replace(".jsonl.gz", ".partial.jsonl.gz"))
            Files.move(file.toPath(), partial.toPath())
        }
    }
}
