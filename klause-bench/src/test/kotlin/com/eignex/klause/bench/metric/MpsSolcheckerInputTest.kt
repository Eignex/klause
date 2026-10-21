package com.eignex.klause.bench.metric

import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.MpsVar
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
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

    @Test
    fun `source admission should stop when its allowance is exhausted`() {
        val input = source.replace("ROWS", "UNSUPPORTED")
        var checkpoints = 0

        assertFailsWith<IllegalStateException> {
            MpsSolcheckerInput.qualify(input) { ++checkpoints == 2 }
        }

        assertEquals(2, checkpoints)
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
    @CsvSource("true,1,0", "false,1,0", "true,1.0,-0e-2", "true,10e-1,+0.0")
    fun `unit upper then zero lower bounds should preserve source ranges in checker order`(
        integer: Boolean,
        upper: String,
        lower: String,
    ) {
        val columns = if (integer) {
            " marker0 'MARKER' 'INTORG'\n x obj 1 row 1\n marker1 'MARKER' 'INTEND'"
        } else {
            " x obj 1 row 1"
        }
        val input = source.replace(" x obj 1 row 1", columns)
            .replace("ENDATA", "BOUNDS\n UP b x $upper\n LO b x $lower\nENDATA")

        val profile = MpsSolcheckerInput.qualify(input)

        assertEquals(input.replace(" UP b x $upper\n LO b x $lower", " LO b x $lower\n UP b x $upper"), profile.text)
        assertEquals(listOf(MpsVar("x", integer, lower.toDouble(), 1.0)), Mps.parse(input).variables)
        assertEquals(Mps.parse(input).variables, Mps.parse(profile.text).variables)
        assertEquals(1, profile.reorderedBoundPairs)
        assertEquals(profile.text, MpsSolcheckerInput.qualify(profile.text).text)
        assertEquals(0, MpsSolcheckerInput.qualify(profile.text).reorderedBoundPairs)
    }

    @Test
    fun `unit bound normalization should track interleaved columns independently`() {
        val input = source.replace(" x obj 1 row 1", " x obj 1 row 1\n y obj 1 row 1")
            .replace("ENDATA", "BOUNDS\n UP b x 1\n UP b y 1\n LO b x 0\n LO b y 0\nENDATA")
        val expected = source.replace(" x obj 1 row 1", " x obj 1 row 1\n y obj 1 row 1")
            .replace("ENDATA", "BOUNDS\n LO b x 0\n LO b y 0\n UP b x 1\n UP b y 1\nENDATA")

        val profile = MpsSolcheckerInput.qualify(input)

        assertEquals(expected, profile.text)
        assertEquals(Mps.parse(input).variables, Mps.parse(profile.text).variables)
        assertEquals(2, profile.reorderedBoundPairs)
    }

    @ParameterizedTest
    @ValueSource(strings = ["\n", "\r\n", "\r"])
    fun `bound reordering should preserve original line endings`(newline: String) {
        val input = source.replace("ENDATA", "BOUNDS\n UP b x 1\n LO b x 0\nENDATA").replace("\n", newline)
        val expected = source.replace("ENDATA", "BOUNDS\n LO b x 0\n UP b x 1\nENDATA").replace("\n", newline)

        val profile = MpsSolcheckerInput.qualify(input)

        assertEquals(expected, profile.text)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            " UP b x 1\n LO b x 2", " UP b x -1\n LO b x -2", " UP b x 1\n LI b x 0",
            " UP b x 1\n MI b x", " UP b x 1\n FX b x 1\n LO b x 0", " UP b x 1\n UP b x 1\n LO b x 0",
            " UP b x 1\n LO b x 0\n LO b x 0", " LO b x 2\n UP b x 1\n LO b x 0",
            " UP b x 1.0000000000000000000001\n LO b x 0", " UP b x 1\n LO b x 0.0000000000000000000001",
        ],
    )
    fun `other lower bounds after upper bounds should remain unqualified`(bounds: String) {
        val input = source.replace("ENDATA", "BOUNDS\n$bounds\nENDATA")

        assertFailsWith<IllegalArgumentException> { MpsSolcheckerInput.qualify(input) }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "offset", "rational", "wide-number", "exponent", "duplicate-objective", "marker-crossing",
            "unknown-row", "section", "trailing", "vectors", "ranges", "bound-order", "binary-bounds",
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
            "vectors" -> source.replace("ENDATA", " other row 2\nENDATA")
            "ranges" -> source.replace("ENDATA", "RANGES\n rng row 3\n rng row -2\nENDATA")
            "bound-order" -> source.replace("ENDATA", "BOUNDS\n UP b x 1\n LO b x 2\nENDATA")
            "binary-bounds" -> source.replace("ENDATA", "BOUNDS\n BV b x\n LO b x 0\nENDATA")
            "section" -> source.replace("ENDATA", "SOS\nENDATA")
            else -> source + " x obj 2\n"
        }

        assertFailsWith<IllegalArgumentException> { MpsSolcheckerInput.qualify(input) }
    }
}
