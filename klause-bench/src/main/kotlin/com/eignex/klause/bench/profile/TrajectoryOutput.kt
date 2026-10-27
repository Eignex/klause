package com.eignex.klause.bench.profile

import java.io.File
import java.lang.Boolean.getBoolean
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun compressTrajectoryOutput(out: File) {
    if (!getBoolean("klause.bench.trajectory.compress")) return
    val started = System.nanoTime()
    val before = trajectoryThreadBytes()
    var inputBytes = 0L
    var outputBytes = 0L
    val records = out.walkTopDown().filter { it.isFile && it.extension == "jsonl" }.toList()
    for (record in records) {
        val compressed = record.resolveSibling("${record.name}.gz")
        inputBytes += record.length()
        record.inputStream().use { input ->
            GZIPOutputStream(compressed.outputStream().buffered()).use { output -> input.copyTo(output) }
        }
        outputBytes += compressed.length()
        check(record.delete())
    }
    out.resolve("compression.json").writeText(buildJsonObject {
        put("files", records.size)
        put("inputBytes", inputBytes)
        put("outputBytes", outputBytes)
        put("elapsedNanos", System.nanoTime() - started)
        put("allocatedBytes", trajectoryThreadBytes() - before)
        put("includedInWalkAllowance", false)
    }.toString())
}
