package com.eignex.klause.bench.metric

import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.formats.smtlib.SExpr
import com.eignex.klause.formats.smtlib.SExprReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files

internal object SmtModelInput {
    private const val MAX_BYTES = 16 * 1024 * 1024
    private const val POLICY = "smt-get-model-v1"
    private val SESSION_COMMANDS = setOf("push", "pop", "reset", "reset-assertions", "check-sat-assuming", "echo")
    private val AFTER_QUERY_COMMANDS = setOf("get-model", "get-info", "get-option", "exit")
    private data class Command(val end: Int, val head: String, val start: Int)
    private class Unavailable(message: String) : IllegalArgumentException(message)

    fun withInput(file: File, checkpoint: () -> Unit,
        solve: (File) -> SolverInvocation.Result): SolverInvocation.Result {
        val hashes = LinkedHashMap<String, String>()
        val prepared = try {
            if (CorpusFiles.isCompressed(file)) decline("model requests require harness-decoded SMT input")
            val bytes = read(file, checkpoint)
            hashes["smtOriginal"] = sha256(bytes)
            val source = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) {
                decline("model requests require valid UTF-8 input")
            }
            augment(source, checkpoint).toByteArray(Charsets.UTF_8).also {
                hashes["smtEffective"] = sha256(it)
            }
        } catch (failure: Unavailable) {
            hashes["smtOriginal"]?.let { hashes["smtEffective"] = it }
            return annotate(hashes, "unsupported", failure.message.orEmpty()) { solve(file) }
        }
        checkpoint()
        val directory = Files.createTempDirectory("klause-smt-model-").toFile()
        var retirement = 0L
        val result = try {
            val staged = directory.resolve("model.smt2")
            staged.outputStream().use { output ->
                var offset = 0
                while (offset < prepared.size) {
                    checkpoint()
                    val length = minOf(8192, prepared.size - offset)
                    output.write(prepared, offset, length)
                    offset += length
                }
            }
            annotate(hashes, "requested", POLICY) { solve(staged) }
        } finally {
            val started = System.nanoTime()
            try {
                Files.deleteIfExists(directory.resolve("model.smt2").toPath())
                Files.deleteIfExists(directory.toPath())
            } finally {
                retirement = (System.nanoTime() - started) / 1_000_000
            }
        }
        return result.copy(stats = result.stats + ("smtModelRetirementMs" to retirement.toString()))
    }

    private fun annotate(hashes: Map<String, String>, status: String, reason: String,
        solve: () -> SolverInvocation.Result): SolverInvocation.Result {
        fun decorate(result: SolverInvocation.Result): SolverInvocation.Result = result.copy(
            compiledInputHashes = result.compiledInputHashes + hashes,
            stats = result.stats + mapOf("smtModelRequest" to status, "smtModelRequestReason" to reason,
                "smtModelRequestPolicy" to POLICY))
        return try {
            decorate(solve())
        } catch (failure: SolverInvocation.Failure) {
            throw SolverInvocation.Failure(decorate(failure.result), failure.message.orEmpty(), failure)
        }
    }

    private fun read(file: File, checkpoint: () -> Unit): ByteArray = file.inputStream().use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            checkpoint()
            val size = input.read(buffer)
            if (size < 0) break
            if (output.size() + size > MAX_BYTES) decline("SMT model-request input exceeds $MAX_BYTES bytes")
            output.write(buffer, 0, size)
        }
        output.toByteArray()
    }

    internal fun augment(source: String, checkpoint: () -> Unit = {}): String {
        val commands = commands(source, checkpoint)
        val query = commands.singleOrNull { it.head == "check-sat" }
            ?: decline("model requests require exactly one check-sat")
        if (commands.any { it.head in SESSION_COMMANDS })
            decline("incremental, assumption and echo command streams require sequence-aware validation")
        val after = commands.dropWhile { it !== query }.drop(1)
        if (after.any { it.head !in AFTER_QUERY_COMMANDS })
            decline("commands after check-sat change or ambiguously report the checked context")
        if (commands.indexOfFirst { it.head == "exit" }.let { it >= 0 && it != commands.lastIndex })
            decline("commands follow exit")
        val models = commands.filter { it.head == "get-model" }
        if (models.size > 1 || models.any { it.start < query.start }) decline("ambiguous existing model requests")
        var enabled = false
        for (command in commands.filter { it.head == "set-option" }) {
            checkpoint()
            if (command.end - command.start > 4096) decline("model-request option exceeds 4096 characters")
            val option = runCatching {
                SExprReader(source.substring(command.start, command.end)).readAll().single() as? SExpr.SList
            }.getOrNull()
                ?: decline("unrecognized model-request option")
            val flag = (option.items.getOrNull(1) as? SExpr.Atom)?.text
            val value = (option.items.getOrNull(2) as? SExpr.Atom)?.text
            if (flag == ":produce-models") {
                if (value != "true" || option.items.size != 3 || command.start > query.start)
                    decline("existing produce-models setting cannot be overridden")
                enabled = true
            }
            if (flag == ":regular-output-channel" && value != "\"stdout\"")
                decline("existing regular output channel cannot be overridden")
        }
        checkpoint()
        val prefix = if (enabled) "" else "(set-option :produce-models true)\n"
        return if (models.isEmpty()) prefix + source.substring(0, query.end) + "\n(get-model)\n" +
            source.substring(query.end) else prefix + source
    }

    private fun commands(source: String, checkpoint: () -> Unit): List<Command> {
        val commands = ArrayList<Command>()
        var depth = 0
        var start = 0
        var quote = ' '
        var comment = false
        var index = 0
        while (index < source.length) {
            if (index % 1024 == 0) checkpoint()
            val char = source[index]
            when {
                comment -> if (char == '\n' || char == '\r') comment = false
                quote == '|' -> if (char == '|') quote = ' '
                quote == '"' -> if (char == '"') {
                    if (source.getOrNull(index + 1) == '"') index++ else quote = ' '
                }
                char == ';' -> comment = true
                char == '|' || char == '"' -> {
                    if (depth == 0) decline("model-request input has a non-command top-level token")
                    quote = char
                }
                char == '(' -> if (depth++ == 0) start = index
                char == ')' -> {
                    if (--depth < 0) decline("model-request input has unmatched parentheses")
                    if (depth == 0) commands += Command(index + 1, head(source, start + 1, checkpoint), start)
                }
                depth == 0 && !char.isWhitespace() -> decline("model-request input has a non-command top-level token")
            }
            index++
        }
        if (depth != 0 || quote != ' ') decline("model-request input has an incomplete command")
        return commands
    }

    private fun head(source: String, offset: Int, checkpoint: () -> Unit): String {
        var index = offset
        while (index < source.length) {
            if (index % 1024 == 0) checkpoint()
            if (source[index].isWhitespace()) index++
            else if (source[index] == ';') {
                while (index < source.length && source[index] != '\n' && source[index] != '\r') {
                    if (index % 1024 == 0) checkpoint()
                    index++
                }
            } else break
        }
        val start = index
        while (index < source.length && !source[index].isWhitespace() && source[index] !in "();") {
            if (index % 1024 == 0) checkpoint()
            index++
        }
        return source.substring(start, index)
    }

    private fun decline(reason: String): Nothing = throw Unavailable(reason)
}
