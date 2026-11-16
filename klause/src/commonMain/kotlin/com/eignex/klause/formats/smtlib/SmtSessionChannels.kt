package com.eignex.klause.formats.smtlib

/** A line-oriented destination owned by one SMT session. */
interface SmtSessionSink : AutoCloseable {
    /** Append one complete protocol response or diagnostic. */
    fun write(text: String)
}

internal class SmtSessionChannels(
    regular: (String) -> Unit,
    diagnostic: (String) -> Unit,
    private val openAppend: (String) -> SmtSessionSink,
) : AutoCloseable {
    private fun standard(write: (String) -> Unit): SmtSessionSink = object : SmtSessionSink {
        override fun write(text: String) = write.invoke(text)
        override fun close() = Unit
    }
    private val stdout = standard(regular)
    private val stderr = standard(diagnostic)
    private var regular = stdout
    private var diagnostic = stderr
    private var regularName = "stdout"
    private var diagnosticName = "stderr"

    fun regular(text: String) = regular.write(text)
    fun diagnostic(text: String) = diagnostic.write(text)
    fun name(diagnostic: Boolean): String = if (diagnostic) diagnosticName else regularName
    fun select(name: String, diagnostic: Boolean) {
        val selected = when (name) {
            "stdout" -> stdout
            "stderr" -> stderr
            else -> openAppend(name)
        }
        val previous = if (diagnostic) this.diagnostic else regular
        if (diagnostic) {
            this.diagnostic = selected
            diagnosticName = name
        } else {
            regular = selected
            regularName = name
        }
        if (previous !== stdout && previous !== stderr) previous.close()
    }
    fun reset() {
        close()
        regular = stdout
        diagnostic = stderr
        regularName = "stdout"
        diagnosticName = "stderr"
    }
    override fun close() {
        if (regular !== stdout && regular !== stderr) regular.close()
        if (diagnostic !== stdout && diagnostic !== stderr && diagnostic !== regular) diagnostic.close()
        regular = stdout
        diagnostic = stderr
    }
}

internal fun smtSessionString(value: String): String = "\"${value.replace("\"", "\"\"")}\""
internal fun smtSessionStringValue(value: SExpr): String {
    val text = (value as? SExpr.Atom)?.text ?: throw UnsupportedSmtException("expected a string literal")
    if (text.length < 2 || !text.startsWith('"') || !text.endsWith('"')) {
        throw UnsupportedSmtException("expected a string literal")
    }
    return text.substring(1, text.lastIndex).replace("\"\"", "\"")
}
