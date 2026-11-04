package com.eignex.klause.bench.source

import java.io.File
import java.util.zip.GZIPInputStream

internal object CommittedCorpusIndexes {
    fun load(id: String, workspace: File? = runCatching { CorpusFetcher.workspaceRoot() }.getOrNull()): CorpusIndex? {
        val file = workspace?.let { File(it, "klause-bench/corpus-index/$id.tsv.gz") }?.takeIf { it.isFile } ?: return null
        val lines = GZIPInputStream(file.inputStream()).bufferedReader().use { it.readLines() }
        require(lines.firstOrNull() == "path\tsha256\tbytes") { "committed corpus index '$id' has no header" }
        return CorpusIndex(
            id,
            lines.drop(1).filter { it.isNotEmpty() }.map { line ->
                val fields = line.split('\t')
                require(fields.size == 3) { "committed corpus index '$id' has a malformed line" }
                CorpusIndex.Entry(fields[0], fields[1], fields[2].toLong())
            },
        )
    }
}
