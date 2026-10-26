package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import java.util.Base64

internal fun trajectorySourceBytes(suite: String, problem: String, expectedSha256: String, source64: String?): ByteArray {
    val bytes = if (source64 == null) {
        val reference = Catalog.uncapped(suite).problems.single { it.name == problem }
        CorpusFiles.open(CorpusFetcher.resolve(reference.source)).use { it.readNBytes(64 * 1024 * 1024 + 1) }
    } else {
        require(source64.length <= 96 * 1024)
        Base64.getDecoder().decode(source64)
    }
    require(bytes.size <= 64 * 1024 * 1024 && sha256(bytes) == expectedSha256)
    return bytes
}
