package com.eignex.klause.bench.source

import com.eignex.klause.bench.catalog.ExternalCollection
import java.io.File
import java.util.zip.GZIPInputStream

internal object CommittedCorpusInputs {
    data class Entry(val path: String, val sha256: String, val bytes: Long)

    private val collectionPattern = Regex("[a-z0-9-]+")
    private val digestPattern = Regex("[a-f0-9]{64}")
    private class Scope {
        val indexes = HashMap<String, List<Entry>>()
    }

    private var scope: Scope? = null
    val isPlanning: Boolean get() = scope != null

    fun <T> planning(operation: () -> T): T {
        val previous = scope
        scope = Scope()
        return try { operation() } finally { scope = previous }
    }

    fun entries(collection: ExternalCollection): List<Entry> = checkNotNull(scope).indexes.getOrPut(collection.id) {
        require(collection.id.matches(collectionPattern)) { "Invalid collection identity" }
        val file = File(CorpusFetcher.workspaceRoot(), "klause-bench/corpus-index/${collection.id}.tsv.gz")
        require(file.isFile) { "Missing committed corpus index: ${collection.id}" }
        GZIPInputStream(file.inputStream()).bufferedReader().use { reader ->
            require(reader.readLine() == "path\tsha256\tbytes") { "Invalid committed corpus header" }
            reader.lineSequence().filter { it.isNotBlank() }.map { line ->
                val fields = line.split('\t')
                require(fields.size == 3) { "Invalid committed corpus entry" }
                val (path, sha256, bytes) = fields
                require(!File(path).isAbsolute && path.split('/').none { it == ".." || it.isEmpty() }) {
                    "Invalid committed corpus path"
                }
                require(sha256.matches(digestPattern)) { "Invalid committed corpus digest" }
                Entry(path, sha256, bytes.toLong()).also { require(it.bytes >= 0) }
            }.toList().also { entries ->
                require(entries.map { it.path }.distinct().size == entries.size) { "Duplicate corpus paths" }
            }
        }
    }
}
