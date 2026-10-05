package com.eignex.klause.bench

import java.io.File
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProblemFormatTest {

    private fun file(suffix: String, text: String): File = createTempFile(suffix = suffix).toFile().apply {
        writeText(text)
        deleteOnExit()
    }

    @Test
    fun `an smt-lib maximization reports its sense even when its model has an open column`() {
        val source = file(
            ".smt2",
            "(set-logic QF_LIA)\n(declare-const x Int)\n(assert (<= x 3))\n(maximize x)\n(check-sat)",
        )

        val ingested = SmtLibFormat.ingest(source)

        assertTrue(ingested.maximize)
        assertFailsWith<IllegalStateException> { ingested.problem }
    }
}
