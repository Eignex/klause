package com.eignex.klause.bench.report

import java.io.File

/**
 * Minimal Markdown report builder so metrics emit a human-readable summary alongside their
 * JSON. Centralizes table / section / heading formatting; metrics build a [Markdown] and call
 * [Reports.writeMarkdown].
 */
class Markdown {
    private val sb = StringBuilder()

    fun h1(text: String) = apply { sb.append("# ").append(text).append("\n\n") }
    fun h2(text: String) = apply { sb.append("## ").append(text).append("\n\n") }
    fun line(text: String = "") = apply { sb.append(text).append('\n') }
    fun para(text: String) = apply { sb.append(text).append("\n\n") }

    /** A GitHub-flavored table. [rows] cells are stringified as-is; ragged rows are padded. */
    fun table(headers: List<String>, rows: List<List<Any?>>) = apply {
        sb.append("| ").append(headers.joinToString(" | ")).append(" |\n")
        sb.append("|").append(headers.joinToString("") { " --- |" }).append('\n')
        for (r in rows) {
            val cells = (0 until headers.size).map { (r.getOrNull(it) ?: "").toString() }
            sb.append("| ").append(cells.joinToString(" | ")).append(" |\n")
        }
        sb.append('\n')
    }

    override fun toString(): String = sb.toString()
}

/** Build a [Markdown] document. */
inline fun markdown(block: Markdown.() -> Unit): Markdown = Markdown().apply(block)
