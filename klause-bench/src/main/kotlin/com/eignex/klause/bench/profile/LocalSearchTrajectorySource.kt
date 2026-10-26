package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.GZIPInputStream

internal fun trajectorySourceBytes(
    suite: String,
    problem: String,
    expectedSha256: String,
    source64: String?,
    sourceGzip64: String? = null,
): ByteArray {
    require(source64 == null || sourceGzip64 == null)
    val bytes = if (source64 == null && sourceGzip64 == null) {
        val reference = Catalog.uncapped(suite).problems.single { it.name == problem }
        CorpusFiles.open(CorpusFetcher.resolve(reference.source)).use { it.readNBytes(64 * 1024 * 1024 + 1) }
    } else {
        val encoded = checkNotNull(source64 ?: sourceGzip64)
        require(encoded.length <= 96 * 1024)
        val decoded = Base64.getDecoder().decode(encoded)
        if (sourceGzip64 == null) decoded else GZIPInputStream(ByteArrayInputStream(decoded)).use {
            it.readNBytes(64 * 1024 * 1024 + 1)
        }
    }
    require(bytes.size <= 64 * 1024 * 1024 && sha256(bytes) == expectedSha256)
    return bytes
}
