package com.eignex.klause.bench.metric

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.Test

class MpsSolcheckerInputTest {
    private val source = "NAME fixture\nROWS\n N obj\n L row\nCOLUMNS\n x obj 1 row 1\nRHS\n rhs row 1\nENDATA\n"

    @Test
    fun `supported source should retain its declared direction`() {
        val profile = MpsSolcheckerInput.qualify(source.replace("ROWS", "OBJSENSE\n MAX\nROWS"))

        assertEquals(true, profile.maximize)
    }

    @ParameterizedTest
    @ValueSource(strings = ["NAME", "RHS", "both"])
    fun `omitted optional headers should preserve every original data record`(omitted: String) {
        val input = source.replace(if (omitted != "RHS") "NAME fixture\n" else "absent", "")
            .replace(if (omitted != "NAME") "RHS\n rhs row 1\n" else "absent", "")
        val expected = if (omitted == "NAME") {
            "NAME KLAUSE_SOURCE_CHECK\n$input"
        } else if (omitted == "RHS") {
            input.replace("ENDATA", "RHS\nENDATA")
        } else {
            "NAME KLAUSE_SOURCE_CHECK\n" + input.replace("ENDATA", "RHS\nENDATA")
        }

        val profile = MpsSolcheckerInput.qualify(input)

        assertEquals(expected, profile.text)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "offset", "rational", "wide-number", "exponent", "duplicate-objective", "marker-crossing",
            "unknown-row", "section", "trailing",
        ],
    )
    fun `unqualified upstream source features should be declined`(variant: String) {
        val input = when (variant) {
            "offset" -> source.replace("rhs row 1", "rhs obj 1")
            "rational" -> source.replace("row 1", "row 1/3")
            "wide-number" -> source.replace("row 1", "row ${"1".repeat(32)}")
            "exponent" -> source.replace("row 1", "row 1e9999")
            "duplicate-objective" -> source.replace(" x obj 1 row 1", " x obj 1 row 1\n x obj 2")
            "marker-crossing" -> source.replace(" x obj 1 row 1", " x obj 1\n marker 'MARKER' 'INTORG'\n x row 1")
            "unknown-row" -> source.replace(" x obj 1 row 1", " x obj 1 unknown 1")
            "section" -> source.replace("ENDATA", "SOS\nENDATA")
            else -> source + " x obj 2\n"
        }

        assertFailsWith<IllegalArgumentException> { MpsSolcheckerInput.qualify(input) }
    }
}
