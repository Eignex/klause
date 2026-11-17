package com.eignex.klause.bench.source

import com.eignex.klause.ir.ObjectiveSense
import java.io.File
import java.io.InterruptedIOException
import java.io.PushbackReader

internal object SmtObjective {
    fun read(file: File): ObjectiveSense? = CorpusFiles.open(file).bufferedReader().use { input ->
        val reader = PushbackReader(input, 1)
        var depth = 0
        var head = false
        var next = reader.next()
        while (next >= 0) {
            when (val char = next.toChar()) {
                ';' -> while (true) {
                    val comment = reader.next()
                    if (comment < 0 || comment == '\n'.code || comment == '\r'.code) break
                }
                '"', '|' -> {
                    reader.skipQuoted(char)
                    head = false
                }
                '(' -> {
                    depth++
                    head = depth == 1
                }
                ')' -> {
                    depth--
                    head = false
                }
                else -> if (head && !char.isWhitespace()) {
                    val command = reader.command(char)
                    head = false
                    when (command) {
                        "minimize" -> return@use ObjectiveSense.MINIMIZE
                        "maximize" -> return@use ObjectiveSense.MAXIMIZE
                        "exit" -> return@use null
                    }
                }
            }
            next = reader.next()
        }
        null
    }

    private fun PushbackReader.command(first: Char): String {
        val token = StringBuilder().append(first)
        while (true) {
            val next = next()
            if (next < 0) break
            val char = next.toChar()
            if (char.isWhitespace() || char in "();\"|") {
                unread(next)
                break
            }
            if (token.length < 9) token.append(char)
        }
        return token.toString()
    }

    private fun PushbackReader.skipQuoted(quote: Char) {
        while (true) {
            val next = next()
            if (next < 0) return
            if (next != quote.code) continue
            if (quote == '|') return
            val following = next()
            if (following == '"'.code) continue
            if (following >= 0) unread(following)
            return
        }
    }

    private fun PushbackReader.next(): Int {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("SMT objective scan interrupted")
        return read()
    }
}
