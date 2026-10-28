package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import java.security.MessageDigest
import java.util.Locale

/** How a record's claims were judged: the solver's own report, unchecked by the bench. */
internal const val REPORTED_RESULT_POLICY = "reported-result-v1"

internal object SolveEvidence {
    private const val MAX_WITNESS_CHARS = 8 * 1024 * 1024
    private val ASSIGNMENT = Regex("^\\s*[A-Za-z_][A-Za-z_0-9]*\\s*=")

    fun sourceHashes(ref: ProblemRef): Map<String, String> = buildMap {
        val sources = listOfNotNull("model" to ref.source, ref.data?.let { "data" to it })
        for ((label, source) in sources) {
            val digest = MessageDigest.getInstance("SHA-256")
            CorpusFiles.update(digest, CorpusFetcher.resolve(source))
            put(label, digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) })
        }
    }

    /** [raw]'s final candidate as [SolveRecord.finalWitness], or null when it holds none or it exceeds 8 MiB.
     *  [modelOutput], when given, maps a MiniZinc solution's FlatZinc-level DZN to the model's own output
     *  (see [com.eignex.klause.bench.runner.MiniZincRunner.modelOutput]) before its comment lines are appended. */
    fun finalWitness(format: Format, raw: String, modelOutput: ((String) -> String)? = null): String? {
        val witness = when (format) {
            Format.MINIZINC -> miniZincWitness(raw, modelOutput)

            Format.SMTLIB -> finalSmtModel(raw)

            Format.XCSP3 -> xcspInstantiation(raw)?.let { "v $it" }

            Format.OPB, Format.DIMACS, Format.WCNF, Format.MPS ->
                raw.lineSequence().lastOrNull { it == "v" || it.startsWith("v ") }

            else -> null
        }
        return witness?.takeIf { it.length <= MAX_WITNESS_CHARS }
    }

    /** The assignments of the last complete DZN solution in [raw], from its first assignment line on. */
    fun miniZincSolution(raw: String): String? {
        val complete = raw.split("----------").dropLast(1).lastOrNull() ?: return null
        val lines = complete.lineSequence().filter { line ->
            val trimmed = line.trim()
            trimmed.isNotEmpty() && !trimmed.startsWith('%') && !trimmed.startsWith("=====")
        }.toList()
        val start = lines.indexOfFirst { ASSIGNMENT.containsMatchIn(it) }
        return if (start < 0) null else lines.drop(start).joinToString("\n")
    }

    /** The last complete DZN solution, mapped by [modelOutput] when given, with the `% klause-exact:`,
     *  `% klause-coordinate:` and `% klause-float-` lines of that solution appended; an exact-coordinate-only
     *  solution keeps those lines alone. */
    private fun miniZincWitness(raw: String, modelOutput: ((String) -> String)?): String? = (
        miniZincSolution(raw)?.let { modelOutput?.invoke(it) ?: it }
            ?: if (exactCoordinates(raw) != null) "" else null
        )?.let { candidate ->
        val comments = raw.split("----------").dropLast(1).lastOrNull()
            ?.lineSequence()?.filter {
                it.startsWith("% klause-exact: ") || it.startsWith("% klause-coordinate: ") ||
                    it.startsWith("% klause-float-")
            }?.joinToString("\n").orEmpty()
        if (comments.isEmpty()) candidate else candidate + "\n" + comments
    }

    private fun exactCoordinates(raw: String): String? = raw.split("----------").dropLast(1).lastOrNull()
        ?.lineSequence()?.filter { it.startsWith("% klause-exact: ") || it.startsWith("% klause-coordinate: ") }
        ?.map { it.substringAfter(": ") }?.toList()?.takeIf { it.isNotEmpty() }?.joinToString("\n")

    /** The last complete XCSP3 `<instantiation>` of the `v ` lines, without their prefix. */
    private fun xcspInstantiation(raw: String): String? {
        var candidate: StringBuilder? = null
        var witness: String? = null
        for (line in raw.lineSequence().filter { it.startsWith("v ") }.map { it.removePrefix("v ") }) {
            if (line.trimStart().startsWith("<instantiation")) candidate = StringBuilder()
            val current = candidate ?: continue
            if (current.isNotEmpty()) current.append('\n')
            current.append(line)
            if (line.trimEnd().endsWith("</instantiation>")) {
                witness = current.toString().trim()
                candidate = null
            }
        }
        return witness
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
