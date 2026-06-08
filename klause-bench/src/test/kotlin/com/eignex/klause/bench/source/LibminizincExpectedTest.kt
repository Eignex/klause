package com.eignex.klause.bench.source

import com.eignex.klause.bench.catalog.Expected
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class LibminizincExpectedTest {

    private fun parse(content: String): Expected {
        val f = Files.createTempFile("lmz", ".mzn").toFile()
        f.writeText(content)
        f.deleteOnExit()
        return LibminizincExpected.parse(f)
    }

    @Test fun `SATISFIED directive parses as Sat`() = assertEquals(
        Expected.Sat,
        parse("/***\n!Test\nexpected:\n- !Result\n  status: SATISFIED\n***/\nvar 1..3: x;"),
    )

    @Test fun `UNSATISFIABLE directive parses as Unsat`() = assertEquals(
        Expected.Unsat,
        parse("/***\nexpected:\n  status: UNSATISFIABLE\n***/\n"),
    )

    @Test fun `optimal with objective`() = assertEquals(
        Expected.Opt(42),
        parse("/***\nstatus: OPTIMAL_SOLUTION\nobjective: 42\n***/\n"),
    )

    @Test fun `optimal without objective falls back to sat`() = assertEquals(
        Expected.Sat,
        parse("/***\nstatus: OPTIMAL_SOLUTION\n***/\n"),
    )

    @Test fun `no directive is unknown`() = assertEquals(
        Expected.Unknown,
        parse("int: n = 8;\nvar 1..n: x;\nsolve satisfy;"),
    )
}
