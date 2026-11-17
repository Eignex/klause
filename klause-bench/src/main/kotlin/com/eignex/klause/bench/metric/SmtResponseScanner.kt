package com.eignex.klause.bench.metric

internal class SmtResponseScanner(
    private val status: (String) -> Unit = {},
    private val model: (Int, Int) -> Unit = { _, _ -> },
) {
    private var offset = 0
    private var depth = 0
    private var start = 0
    private var quote = ' '
    private var closingString = false
    private var comment = false
    private var tokenDepth = -1
    private var collectToken = false
    private val token = StringBuilder()
    private var candidate = false
    private var prefix = false
    private var declarations = 0
    private var declarationHead = false

    val atTopLevel: Boolean get() = depth == 0 && quote == ' ' && !comment && tokenDepth < 0

    fun feed(text: String) {
        for (char in text) {
            consume(char)
            offset++
        }
    }

    fun finish() = endToken()

    private fun consume(char: Char) {
        if (comment) {
            if (char == '\n' || char == '\r') comment = false
            return
        }
        if (quote == '|') {
            if (char == '|') quote = ' '
            return
        }
        if (quote == '"') {
            if (closingString) {
                closingString = false
                if (char == '"') return
                quote = ' '
            } else {
                if (char == '"') closingString = true
                return
            }
        }
        if (char.isWhitespace() || char in "();|\"") endToken()
        when {
            char.isWhitespace() -> Unit
            char == ';' -> comment = true
            char == '|' || char == '"' -> {
                if (depth == 1 || (depth == 2 && declarationHead)) candidate = false
                quote = char
            }
            char == '(' -> {
                when (depth) {
                    0 -> {
                        start = offset
                        candidate = true
                        prefix = false
                        declarations = 0
                    }
                    1 -> {
                        declarations++
                        declarationHead = true
                    }
                    2 -> if (declarationHead) candidate = false
                }
                depth++
            }
            char == ')' -> if (depth > 0) {
                if (depth == 2 && declarationHead) candidate = false
                depth--
                if (depth == 0 && candidate) model(start, offset + 1)
            }
            else -> {
                if (tokenDepth < 0) {
                    tokenDepth = depth
                    collectToken = depth <= 1 || (depth == 2 && declarationHead)
                    token.setLength(0)
                }
                if (collectToken && token.length < 17) token.append(char)
            }
        }
    }

    private fun endToken() {
        if (tokenDepth < 0) return
        val text = if (collectToken) token.toString() else ""
        when (tokenDepth) {
            0 -> if (text == "sat" || text == "unsat" || text == "unknown") status(text)
            1 -> if (text == "model" && !prefix && declarations == 0) prefix = true else candidate = false
            2 -> if (declarationHead) {
                if (text != "define-fun" && text != "define-fun-rec" && text != "define-funs-rec") candidate = false
                declarationHead = false
            }
        }
        tokenDepth = -1
    }
}
