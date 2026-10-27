package com.eignex.klause.bench.profile

import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.Json

internal fun trajectoryBatch(args: Array<String>): List<List<String>>? {
    if (args.size != 1) return null
    val mode = args.single().substringBefore('=')
    if (mode !in setOf("batch64", "batchGzip64")) return null
    val encoded = args.single().substringAfter('=')
    if (mode == "batchGzip64") require(encoded.length <= 96 * 1024)
    val bytes = Base64.getDecoder().decode(encoded)
    val json = if (mode == "batchGzip64") {
        GZIPInputStream(bytes.inputStream()).use { it.readNBytes(8 * 1024 * 1024 + 1) }.also {
            require(it.size <= 8 * 1024 * 1024)
        }
    } else bytes
    return Json.decodeFromString<List<List<String>>>(json.decodeToString()).also {
        require(it.size in 1..8)
    }
}
