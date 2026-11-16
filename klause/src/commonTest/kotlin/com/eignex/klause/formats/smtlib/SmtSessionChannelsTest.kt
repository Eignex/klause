package com.eignex.klause.formats.smtlib

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmtSessionChannelsTest {
    @Test
    fun `invocation diagnostics follow the currently selected diagnostic destination`() {
        val diagnostics = ArrayList<String>()
        val appended = ArrayList<String>()
        val session = SmtLibSession({ SmtSessionResult("sat") }, {}, diagnostic = diagnostics::add,
            openAppend = { destination ->
                object : SmtSessionSink {
                    override fun write(text: String) {
                        appended += "$destination:$text"
                    }
                    override fun close() = Unit
                }
            })

        session.execute(SExprReader("(set-option :diagnostic-output-channel \"trace.log\")"))
        session.diagnose("first")
        session.execute(SExprReader("(reset)"))
        session.diagnose("second")
        session.close()

        assertEquals(listOf("trace.log:first"), appended)
        assertEquals(listOf("second"), diagnostics)
    }

    @Test
    fun `regular channel changes apply to their own success response`() {
        val standard = ArrayList<String>()
        val appended = ArrayList<String>()
        val closed = ArrayList<String>()
        val session = SmtLibSession({ SmtSessionResult("sat") }, standard::add, openAppend = { path ->
            object : SmtSessionSink {
                override fun write(text: String) {
                    appended += "$path:$text"
                }
                override fun close() {
                    closed += path
                }
            }
        })

        session.execute(SExprReader("""
            (set-option :print-success true)
            (set-option :regular-output-channel "answers.log")
            (echo "hello")
            (set-option :regular-output-channel "stdout")
            (exit)
        """.trimIndent()))
        session.close()

        assertEquals(listOf("success", "success", "success"), standard)
        assertEquals(listOf("answers.log:success", "answers.log:\"hello\""), appended)
        assertEquals(listOf("answers.log"), closed)
    }

    @Test
    fun `reset restores both standard channels and closes files`() {
        val regular = ArrayList<String>()
        val diagnostic = ArrayList<String>()
        val closed = ArrayList<String>()
        val session = SmtLibSession({ SmtSessionResult("sat") }, regular::add,
            diagnostic = diagnostic::add, openAppend = { path ->
                object : SmtSessionSink {
                    override fun write(text: String) = Unit
                    override fun close() {
                        closed += path
                    }
                }
            })

        session.execute(SExprReader("""
            (set-option :regular-output-channel "answers.log")
            (set-option :diagnostic-output-channel "diagnostics.log")
            (reset)
            (get-option :regular-output-channel)
            (get-option :diagnostic-output-channel)
        """.trimIndent()))
        session.close()

        assertEquals(listOf("\"stdout\"", "\"stderr\""), regular)
        assertTrue(diagnostic.isEmpty())
        assertEquals(listOf("answers.log", "diagnostics.log"), closed)
    }

    @Test
    fun `failed channel opens preserve the previous channel`() {
        val replies = ArrayList<String>()
        val session = SmtLibSession({ SmtSessionResult("sat") }, replies::add,
            openAppend = { throw UnsupportedSmtException("unavailable destination") })

        session.execute(SExprReader("""
            (set-option :regular-output-channel "missing/answers.log")
            (get-option :regular-output-channel)
            (echo "still open")
        """.trimIndent()))

        assertTrue(replies[0].startsWith("(error "))
        assertEquals("\"stdout\"", replies[1])
        assertEquals("\"still open\"", replies[2])
    }

    @Test
    fun `either standard stream can be selected for regular output`() {
        val regular = ArrayList<String>()
        val diagnostic = ArrayList<String>()
        val session = SmtLibSession({ SmtSessionResult("sat") }, regular::add,
            diagnostic = diagnostic::add)

        session.execute(SExprReader("""
            (set-option :regular-output-channel "stderr")
            (echo "diagnostic stream")
            (set-option :regular-output-channel "stdout")
            (echo "regular stream")
        """.trimIndent()))

        assertEquals(listOf("\"regular stream\""), regular)
        assertEquals(listOf("\"diagnostic stream\""), diagnostic)
    }
}
