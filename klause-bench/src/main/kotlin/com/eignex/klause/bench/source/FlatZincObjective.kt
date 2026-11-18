package com.eignex.klause.bench.source

import com.eignex.klause.ir.ObjectiveSense
import java.io.File
import java.io.InterruptedIOException
import java.io.Reader
import java.io.StreamTokenizer

internal object FlatZincObjective {
    fun read(file: File): ObjectiveSense? {
        checkActive()
        return CorpusFiles.open(file).bufferedReader().use(::read)
    }

    fun read(reader: Reader): ObjectiveSense? {
        val input = StreamTokenizer(reader).apply {
            resetSyntax()
            wordChars('a'.code, 'z'.code)
            wordChars('A'.code, 'Z'.code)
            wordChars('0'.code, '9'.code)
            wordChars('_'.code, '_'.code)
            whitespaceChars(0, ' '.code)
            quoteChar('"'.code)
            quoteChar('\''.code)
            commentChar('%'.code)
            slashStarComments(true)
        }
        var depth = 0
        var head = true
        var solve = false
        while (true) {
            checkActive()
            val token = input.nextToken()
            if (token == StreamTokenizer.TT_EOF) return null
            if (depth == 0) {
                if (head) {
                    solve = token == StreamTokenizer.TT_WORD && input.sval == "solve"
                    head = false
                }
                if (solve && token == StreamTokenizer.TT_WORD) {
                    when (input.sval) {
                        "minimize" -> return ObjectiveSense.MINIMIZE
                        "maximize" -> return ObjectiveSense.MAXIMIZE
                        "satisfy" -> return null
                    }
                }
                if (token == ';'.code) {
                    head = true
                    solve = false
                }
            }
            when (token) {
                '('.code, '['.code, '{'.code -> depth++
                ')'.code, ']'.code, '}'.code -> depth--
            }
        }
    }

    private fun checkActive() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("FlatZinc objective scan interrupted")
    }
}
