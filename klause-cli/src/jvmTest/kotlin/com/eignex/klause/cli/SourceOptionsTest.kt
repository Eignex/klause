package com.eignex.klause.cli

import com.eignex.klause.formats.dimacs.dimacsSemantics
import com.eignex.klause.formats.dimacs.wcnfSemantics
import com.eignex.klause.formats.flatzinc.flatZincSemantics
import com.eignex.klause.formats.mps.mpsSemantics
import com.eignex.klause.formats.opb.opbSemantics
import com.eignex.klause.formats.smtlib.smtLibSemantics
import com.eignex.klause.formats.xcsp3.xcsp3Semantics
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class SourceOptionsTest {
    @Test
    fun `source numbers should retain exact decimal scientific and rational values`() {
        val tenth = SemanticRational.of(bigIntOf(1), bigIntOf(10))
        val values = listOf(
            "0.1" to tenth,
            "1e-1" to tenth,
            "-2.50E+1" to SemanticRational.ofLong(-25),
            "1/3" to SemanticRational.of(bigIntOf(1), bigIntOf(3)),
            "0.10000000000000000001" to SemanticRational.of(
                parseBigInt("10000000000000000001"), parseBigInt("100000000000000000000"),
            ),
        )

        for ((text, expected) in values) assertEquals(expected, semanticNumber(text))
    }

    @Test
    fun `invalid source numbers should produce usage errors`() {
        for (text in listOf(
            "NaN", "Infinity", "1/0", "word", "1e10001", "1e-10001", "1e999999999999999999999",
        )) {
            assertFailsWith<CliUsageException>(text) { semanticNumber(text) }
        }
    }

    @Test
    fun `integer source controls should distinguish finite extrema from open endpoints across formats`() {
        for (declared in listOf(flatZincSemantics, smtLibSemantics)) {
            val common = CommonOptions()
            parseArgs(
                arrayOf("--unbounded-int-lo", Long.MIN_VALUE.toString(), "--unbounded-int-hi", "open"),
                commonFlagSpecs(common),
            ) {}

            common.sourceOverrides(declared)

            val bounds = assertNotNull(common.sourceSemantics?.effective?.undeclaredIntegers)
            assertEquals(Long.MIN_VALUE, bounds.lower)
            assertEquals(null, bounds.upper)
        }
    }

    @Test
    fun `grid controls should replace requested fields and preserve declared defaults`() {
        val common = CommonOptions()
        parseArgs(
            arrayOf("--float-semantics", "grid", "--float-buckets", "17", "--float-scale", "3000000000",
                "--float-grid-lo", "-2.5"),
            commonFlagSpecs(common),
        ) {}

        common.sourceOverrides(flatZincSemantics)

        val capture = assertNotNull(common.sourceSemantics)
        val grid = assertIs<FloatSemantics.Grid>(capture.effective.floats)
        assertEquals(17, grid.buckets)
        assertEquals(3_000_000_000L, grid.linearScale)
        assertEquals(SemanticRational.of(bigIntOf(-5), bigIntOf(2)), grid.unrangedBounds.lower)
        assertEquals(assertIs<FloatSemantics.Grid>(flatZincSemantics.floats).unrangedBounds.upper,
            grid.unrangedBounds.upper)
        assertEquals(flatZincSemantics, capture.declared)
    }

    @Test
    fun `exact source controls should require continuous floats and zero applicable tolerance`() {
        for (declared in listOf(flatZincSemantics, smtLibSemantics, mpsSemantics, dimacsSemantics,
            wcnfSemantics, opbSemantics, xcsp3Semantics)) {
            val common = CommonOptions()
            parseArgs(arrayOf("--exact"), commonFlagSpecs(common)) {}

            common.sourceOverrides(declared)

            val capture = assertNotNull(common.sourceSemantics)
            assertFalse(capture.isApproximate)
            if (declared.floats != null) assertEquals(FloatSemantics.Continuous, capture.effective.floats)
            if (declared.feasibilityTolerance != null) {
                assertEquals(SourceTolerance(), capture.effective.feasibilityTolerance)
            }
        }
    }

    @Test
    fun `one tolerance replacement should preserve the other effective component`() {
        for ((flag, absolute) in listOf(
            "--source-absolute-tolerance" to true, "--source-relative-tolerance" to false,
        )) {
            val common = CommonOptions()
            parseArgs(arrayOf(flag, "0"), commonFlagSpecs(common)) {}

            common.sourceOverrides(mpsSemantics)

            val tolerance = assertNotNull(common.sourceSemantics?.effective?.feasibilityTolerance)
            val defaults = assertNotNull(mpsSemantics.feasibilityTolerance)
            assertEquals(if (absolute) SemanticRational.ZERO else defaults.absolute, tolerance.absolute)
            assertEquals(if (absolute) defaults.relative else SemanticRational.ZERO, tolerance.relative)
        }
    }

    @Test
    fun `exact source controls should accept an explicit zero tolerance component`() {
        val common = CommonOptions()
        parseArgs(arrayOf("--exact", "--source-absolute-tolerance", "0"), commonFlagSpecs(common)) {}

        common.sourceOverrides(mpsSemantics)

        assertEquals(SourceTolerance(), common.sourceSemantics?.effective?.feasibilityTolerance)
    }

    @Test
    fun `conflicting approximate and exact source controls should be rejected`() {
        for ((declared, args) in listOf(
            flatZincSemantics to arrayOf("--exact", "--float-buckets", "2"),
            mpsSemantics to arrayOf("--exact", "--source-relative-tolerance", "1/10"),
            flatZincSemantics to arrayOf("--float-semantics", "continuous", "--float-grid-lo", "0"),
        )) {
            val common = CommonOptions()
            parseArgs(args, commonFlagSpecs(common)) {}

            assertFailsWith<IllegalArgumentException> { common.sourceOverrides(declared) }
        }
    }

    @Test
    fun `inapplicable source controls should be rejected before a format opens its input`() {
        val requests = listOf<Pair<CliMode, Array<String>>>(
            DimacsMode to arrayOf("--unbounded-int-lo", "1"),
            WcnfMode to arrayOf("--unbounded-int-lo", "1"),
            OpbMode to arrayOf("--unbounded-int-lo", "1"),
            Xcsp3Mode to arrayOf("--unbounded-int-lo", "1"),
            SmtLibMode to arrayOf("--source-relative-tolerance", "0"),
            MiniZincMode to arrayOf("--pin-absent-optional-values", "false"),
            MpsMode to arrayOf("--pin-absent-optional-values", "false"),
        )
        for ((mode, args) in requests) {
            val common = CommonOptions()
            parseArgs(args, commonFlagSpecs(common)) {}

            assertFailsWith<IllegalArgumentException>(mode.names.first()) { mode.newSession().load("unopened", common) }
        }
    }

    @Test
    fun `the same integer source controls should reach FlatZinc and SMT model declarations`() {
        for ((mode, text) in listOf(
            MiniZincMode to "var int: x; solve satisfy;",
            SmtLibMode to "(declare-const x Int) (check-sat)",
        )) {
            val input = File.createTempFile("source-controls", ".input").apply {
                writeText(text)
                deleteOnExit()
            }
            val common = CommonOptions()
            parseArgs(
                arrayOf("--unbounded-int-lo", "2", "--unbounded-int-hi", "3"), commonFlagSpecs(common),
            ) {}

            val model = assertNotNull(mode.newSession().load(input.absolutePath, common).problem)

            assertEquals(2L, model.intBounds.lower(0))
            assertEquals(3L, model.intBounds.upper(0))
            assertEquals(common.sourceSemantics?.effective, model.semantics.effective)
        }
    }

    @Test
    fun `exact source requests should replace base policy without mutating caller overrides`() {
        val policies = listOf(
            mpsSemantics to SemanticOverrides(
                feasibilityTolerance = SourceTolerance(absolute = SemanticRational.ONE),
            ),
            flatZincSemantics to SemanticOverrides(floats = FloatSemantics.Grid(2, 1000)),
        )
        for ((declared, base) in policies) {
            val common = CommonOptions().apply {
                exact = true
                semanticOverrides = base
            }

            common.sourceOverrides(declared)

            assertSame(base, common.semanticOverrides)
            assertFalse(assertNotNull(common.sourceSemantics).isApproximate)
        }
    }

}
