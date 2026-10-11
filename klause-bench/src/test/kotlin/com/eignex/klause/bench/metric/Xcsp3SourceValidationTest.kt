package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.report.Reports
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Xcsp3SourceValidationTest {
    @Test
    fun `the final multiline instantiation is retained without diagnostics`() {
        val witness = "<instantiation>\n<list>x</list><values>2</values>\n</instantiation>"
        val raw = "o 1\nv <instantiation><list>x</list><values>1</values></instantiation>\n" +
            "o 2\ns SATISFIABLE\nv <instantiation>\nv <list>x</list><values>2</values>\n" +
            "c solveTime=1\nv </instantiation>\n"

        assertEquals(witness, Xcsp3SourceValidation.candidate(raw))
        assertEquals("v $witness", SolveEvidence.finalWitness(Format.XCSP3, raw))
        assertEquals("2", Xcsp3SourceValidation.objective(raw))
    }

    @Test
    fun `a trailing incomplete instantiation cannot reuse an earlier witness`() {
        val raw = "o 1\nv <instantiation><list>x</list><values>1</values></instantiation>\n" +
            "o 2\nv <instantiation><list>x</list>\n"

        assertNull(Xcsp3SourceValidation.candidate(raw))
        assertNull(SolveEvidence.finalWitness(Format.XCSP3, raw))
    }

    @Test
    fun `a checker success requires its complete structured result and successful exit`() {
        val valid = Xcsp3SourceChecker.result("valid", "checked", "v <instantiation/>")
        val log = "OK\t10\n" + Xcsp3SourceValidation.RESULT_PREFIX + Reports.json.encodeToString(valid)

        assertEquals(valid, Xcsp3SourceValidation.checkerResult(0, log))
        for ((exit, output) in listOf(1 to log, 0 to "OK\t10", 0 to "klause-source-check: {")) {
            assertEquals("unknown", Xcsp3SourceValidation.checkerResult(exit, output).status)
        }
    }
}
