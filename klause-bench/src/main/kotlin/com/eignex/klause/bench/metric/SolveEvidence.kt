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
            Format.MINIZINC -> MiniZincSourceValidation.retainedWitness(raw)

            Format.SMTLIB -> finalSmtModel(raw)

            Format.XCSP3 -> Xcsp3SourceValidation.candidate(raw)?.let { "v $it" }

            Format.OPB, Format.DIMACS, Format.WCNF, Format.MPS ->
                raw.lineSequence().lastOrNull { it.startsWith("v ") }

            else -> null
        }
        return witness?.takeIf { it.length <= MAX_WITNESS_CHARS }
    }

    private fun finalSmtModel(raw: String): String? {
        var modelStart = -1
        var modelLength = 0
        var modelHasCr = false
        var witnessStart = -1
        var witnessEnd = 0
        var witnessLength = 0
        var witnessHasCr = false
        var lineStart = 0
        var precedingCr = false
        var nextLf = raw.indexOf('\n')
        var nextCr = raw.indexOf('\r')
        while (lineStart <= raw.length) {
            val lineEnd = when {
                nextLf < 0 -> if (nextCr < 0) raw.length else nextCr
                nextCr < 0 -> nextLf
                else -> minOf(nextLf, nextCr)
            }
            val marker = if (lineEnd - lineStart == 1) raw[lineStart] else null
            if (marker == '(') {
                modelStart = lineStart
                modelLength = 1
                modelHasCr = false
            } else if (modelStart >= 0) {
                modelLength += lineEnd - lineStart + 1
                modelHasCr = modelHasCr || precedingCr
                if (marker == ')') {
                    witnessStart = modelStart
                    witnessEnd = lineEnd
                    witnessLength = modelLength
                    witnessHasCr = modelHasCr
                    modelStart = -1
                }
            }
            if (lineEnd == raw.length) break
            precedingCr = raw[lineEnd] == '\r'
            lineStart = lineEnd + if (precedingCr && raw.getOrNull(lineEnd + 1) == '\n') 2 else 1
            if (nextLf >= 0 && nextLf < lineStart) nextLf = raw.indexOf('\n', lineStart)
            if (nextCr >= 0 && nextCr < lineStart) nextCr = raw.indexOf('\r', lineStart)
        }
        if (witnessStart < 0 || witnessLength > MAX_WITNESS_CHARS) return null
        if (!witnessHasCr) return raw.substring(witnessStart, witnessEnd)
        return buildString(witnessLength) {
            var copied = witnessStart
            var cr = raw.indexOf('\r', copied)
            while (cr >= 0 && cr < witnessEnd) {
                append(raw, copied, cr)
                append('\n')
                copied = cr + 1
                if (copied < witnessEnd && raw[copied] == '\n') copied++
                cr = raw.indexOf('\r', copied)
            }
            append(raw, copied, witnessEnd)
        }
    }
}
