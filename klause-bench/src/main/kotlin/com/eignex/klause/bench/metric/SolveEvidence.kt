package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import java.security.MessageDigest
import java.util.Locale

internal object SolveEvidence {
    private const val MAX_WITNESS_CHARS = 8 * 1024 * 1024

    fun sourceHashes(ref: ProblemRef): Map<String, String> = buildMap {
        val sources = listOfNotNull("model" to ref.source, ref.data?.let { "data" to it })
        for ((label, source) in sources) {
            val digest = MessageDigest.getInstance("SHA-256")
            CorpusFiles.update(digest, CorpusFetcher.resolve(source))
            put(label, digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) })
        }
    }

    fun finalWitness(format: Format, raw: String): String? {
        val witness = when (format) {
            Format.MINIZINC -> MiniZincSourceValidation.candidate(raw)
            Format.OPB, Format.XCSP3, Format.DIMACS, Format.WCNF, Format.MPS ->
                raw.lineSequence().lastOrNull { it.startsWith("v ") }
            else -> null
        }
        return witness?.takeIf { it.length <= MAX_WITNESS_CHARS }
    }
}
