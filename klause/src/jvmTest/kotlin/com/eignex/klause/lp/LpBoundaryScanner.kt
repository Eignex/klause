package com.eignex.klause.lp

/** Strips comments and string literal contents from Kotlin source text, leaving code shape intact for
 *  tests that pattern-match against it without tripping on unrelated text inside comments or strings. */
internal object LpBoundaryScanner {
    fun codeOnly(source: String): String {
        val out = StringBuilder(source.length)
        var index = 0
        var blockDepth = 0
        var state = LexicalState.CODE
        val interpolationStates = ArrayDeque<LexicalState>()
        val interpolationDepths = ArrayDeque<Int>()
        while (index < source.length) {
            val ch = source[index]
            val next = source.getOrNull(index + 1)
            val third = source.getOrNull(index + 2)
            when (state) {
                LexicalState.CODE -> when {
                    ch == '{' && interpolationDepths.isNotEmpty() -> {
                        out.append(ch)
                        index++
                        interpolationDepths.addLast(interpolationDepths.removeLast() + 1)
                    }

                    ch == '}' && interpolationDepths.isNotEmpty() -> {
                        out.append(' ')
                        index++
                        val depth = interpolationDepths.removeLast() - 1
                        if (depth == 0) {
                            state = interpolationStates.removeLast()
                        } else {
                            interpolationDepths.addLast(depth)
                        }
                    }

                    ch == '/' && next == '/' -> {
                        out.append("  ")
                        index += 2
                        state = LexicalState.LINE_COMMENT
                    }

                    ch == '/' && next == '*' -> {
                        out.append("  ")
                        index += 2
                        blockDepth = 1
                        state = LexicalState.BLOCK_COMMENT
                    }

                    ch == '"' && next == '"' && third == '"' -> {
                        out.append("   ")
                        index += 3
                        state = LexicalState.RAW_STRING
                    }

                    ch == '"' -> {
                        out.append(' ')
                        index++
                        state = LexicalState.STRING
                    }

                    ch == '\'' -> {
                        out.append(' ')
                        index++
                        state = LexicalState.CHAR
                    }

                    else -> {
                        out.append(ch)
                        index++
                    }
                }

                LexicalState.LINE_COMMENT -> {
                    out.append(if (ch == '\n') '\n' else ' ')
                    index++
                    if (ch == '\n') state = LexicalState.CODE
                }

                LexicalState.BLOCK_COMMENT -> when {
                    ch == '/' && next == '*' -> {
                        out.append("  ")
                        index += 2
                        blockDepth++
                    }

                    ch == '*' && next == '/' -> {
                        out.append("  ")
                        index += 2
                        blockDepth--
                        if (blockDepth == 0) state = LexicalState.CODE
                    }

                    else -> {
                        out.append(if (ch == '\n') '\n' else ' ')
                        index++
                    }
                }

                LexicalState.STRING, LexicalState.CHAR -> when {
                    ch == '\\' && next != null -> {
                        out.append("  ")
                        index += 2
                    }

                    state == LexicalState.STRING && ch == '$' && next == '{' -> {
                        out.append("  ")
                        index += 2
                        interpolationStates.addLast(LexicalState.STRING)
                        interpolationDepths.addLast(1)
                        state = LexicalState.CODE
                    }

                    state == LexicalState.STRING && ch == '$' && next?.isJavaIdentifierStart() == true -> {
                        out.append(' ')
                        index++
                        while (index < source.length && source[index].isJavaIdentifierPart()) {
                            out.append(source[index++])
                        }
                    }

                    (state == LexicalState.STRING && ch == '"') ||
                        (state == LexicalState.CHAR && ch == '\'') -> {
                        out.append(' ')
                        index++
                        state = LexicalState.CODE
                    }

                    else -> {
                        out.append(if (ch == '\n') '\n' else ' ')
                        index++
                    }
                }

                LexicalState.RAW_STRING -> when {
                    ch == '$' && next == '{' -> {
                        out.append("  ")
                        index += 2
                        interpolationStates.addLast(LexicalState.RAW_STRING)
                        interpolationDepths.addLast(1)
                        state = LexicalState.CODE
                    }

                    ch == '$' && next?.isJavaIdentifierStart() == true -> {
                        out.append(' ')
                        index++
                        while (index < source.length && source[index].isJavaIdentifierPart()) {
                            out.append(source[index++])
                        }
                    }

                    ch == '"' && next == '"' && third == '"' -> {
                        out.append("   ")
                        index += 3
                        state = LexicalState.CODE
                    }

                    else -> {
                        out.append(if (ch == '\n') '\n' else ' ')
                        index++
                    }
                }
            }
        }
        return out.toString()
    }

    private enum class LexicalState { CODE, LINE_COMMENT, BLOCK_COMMENT, STRING, RAW_STRING, CHAR }
}
