package com.eignex.klause.bench.metric

import com.eignex.klause.bench.source.CorpusFetcher
import java.io.File
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.xcsp.parser.callbacks.SolutionChecker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Xcsp3SourceCheckerTest {
    private val source = File(CorpusFetcher.workspaceRoot(), "klause-bench/smoke-corpus/xcsp3/sum-opt-tiny.xml")

    init {
        val witness = "<instantiation><list>a b c</list><values>2 2 0</values></instantiation>"
        Xcsp3SourceChecker.inspect(source, witness, "10")
    }

    @Test
    fun `a source valid optimization witness retains the checked assignment`() {
        val witness = "<instantiation><list>a b c</list><values>2 2 0</values></instantiation>"

        val checked = Xcsp3SourceChecker.inspect(source, witness, "10")

        assertEquals("valid", checked.status)
        assertEquals("xcsp3-source", checked.scope)
        assertEquals("org.xcsp:xcsp3-tools:2.5", checked.checker)
        assertNotNull(checked.checkedWitness)
    }

    @Test
    fun `a source constraint violation rejects a domain valid assignment`() {
        val witness = "<instantiation><list>a b c</list><values>2 2 1</values></instantiation>"

        val checked = Xcsp3SourceChecker.inspect(source, witness, "11")

        assertEquals("invalid", checked.status)
        assertNull(checked.checkedWitness)
    }

    @ParameterizedTest
    @ValueSource(strings = ["9", "10.0", "1/2", "NaN"])
    fun `incorrect and malformed source objectives are rejected`(objective: String) {
        val witness = "<instantiation><list>a b c</list><values>2 2 0</values></instantiation>"

        assertEquals("invalid", Xcsp3SourceChecker.inspect(source, witness, objective).status)
    }

    @Test
    fun `optimization without a reported objective remains unchecked`() {
        val witness = "<instantiation><list>a b c</list><values>2 2 0</values></instantiation>"

        assertEquals("unknown", Xcsp3SourceChecker.inspect(source, witness, null).status)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "<instantiation><list>a b c</list><values>2 2 0</values>",
        "<instantiation><list>a b</list><values>2 2</values></instantiation>",
        "<instantiation><list>a a c</list><values>2 2 0</values></instantiation>",
        "<instantiation><list>a b unknown</list><values>2 2 0</values></instantiation>",
        "<instantiation><list>a b c</list><values>3 1 0</values></instantiation>",
        "<instantiation><list>a b c</list><values>2 2 *</values></instantiation>",
        "<instantiation><values>2 2 0</values><list>a b c</list></instantiation>",
    ])
    fun `malformed incomplete duplicate unknown and out of domain assignments are rejected`(witness: String) {
        assertEquals("invalid", Xcsp3SourceChecker.inspect(source, witness, "10").status)
    }

    @Test
    fun `source satisfaction checks include every expanded array coordinate`() {
        val model = File(source.parentFile, "graph-coloring-tiny.xml")
        val witness = "<instantiation><list>v[0] v[1] v[2] v[3]</list><values>1 2 1 2</values></instantiation>"

        val checked = Xcsp3SourceChecker.inspect(model, witness, null)

        assertEquals("valid", checked.status)
        assertNotNull(checked.checkedWitness)
    }

    @Test
    fun `reified source constraints remain unchecked`() {
        val model = File.createTempFile("xcsp-reified-", ".xml")
        try {
            model.writeText(source.readText().replace("<sum>", "<sum reifiedBy='a'>"))
            val witness = "<instantiation><list>a b c</list><values>2 2 0</values></instantiation>"

            val checked = Xcsp3SourceChecker.inspect(model, witness, "10")

            assertEquals("unknown", checked.status)
            assertNull(checked.checkedWitness)
        } finally {
            model.delete()
        }
    }

    @Test
    fun `a missing unconstrained source coordinate cannot earn witness credit`() {
        val model = File.createTempFile("xcsp-unconstrained-", ".xml")
        try {
            model.writeText(source.readText().replace("<variables>", "<variables><var id='free'>0..1</var>"))
            val witness = "<instantiation><list>a b c</list><values>2 2 0</values></instantiation>"

            assertEquals("invalid", Xcsp3SourceChecker.inspect(model, witness, "10").status)
        } finally {
            model.delete()
        }
    }

    @ParameterizedTest
    @CsvSource("1000000000000000000000000000,valid", "1000000000000000000000000001,invalid")
    fun `source objectives remain exact beyond the signed word range`(objective: String, status: String) {
        val model = File.createTempFile("xcsp-product-", ".xml")
        try {
            model.writeText(
                source.readText().replace("<maximize type=\"sum\">", "<maximize type=\"product\">")
                    .replace("<coeffs> 3 2 1 </coeffs>", "<coeffs> 1000000000 1000000000 1000000000 </coeffs>"),
            )
            val witness = "<instantiation><list>a b c</list><values>1 1 1</values></instantiation>"

            assertEquals(status, Xcsp3SourceChecker.inspect(model, witness, objective).status)
        } finally {
            model.delete()
        }
    }

    @Test
    fun `compact array assignments remain unchecked until expanded`() {
        val model = File(source.parentFile, "graph-coloring-tiny.xml")
        val witness = "<instantiation><list>v[]</list><values>1 2 1 2</values></instantiation>"

        assertEquals("unknown", Xcsp3SourceChecker.inspect(model, witness, null).status)
    }

    @Test
    fun `an instantiation cost cannot disagree with its reported objective`() {
        val witness = "<instantiation cost='9'><list>a b c</list><values>2 2 0</values></instantiation>"

        assertEquals("invalid", Xcsp3SourceChecker.inspect(source, witness, "10").status)
    }

    @Test
    fun `the pinned checker accepts a direct sum violation after signed overflow`() {
        val model = File.createTempFile("xcsp-overflow-", ".xml")
        try {
            model.writeText(
                """
                <instance format="XCSP3" type="CSP">
                  <variables><var id="x">0..2000000000</var><var id="y">0..2000000000</var></variables>
                  <constraints><sum><list>x y</list><condition>(le,0)</condition></sum></constraints>
                </instance>
                """.trimIndent(),
            )
            val witness = "<instantiation><list>x y</list><values>1500000000 1500000000</values></instantiation>"

            val checker = SolutionChecker(false, model.absolutePath, witness.byteInputStream())

            assertEquals(3000000000L, 1500000000L + 1500000000L)
            assertTrue(checker.violatedCtrs.isEmpty())
        } finally {
            model.delete()
        }
    }

    @ParameterizedTest
    @CsvSource(
        "1500000000 1500000000 0,le,unknown",
        "-1500000000 -1500000000 0,ge,unknown",
        "1073741823 1073741824 0,le,invalid",
        "1073741824 1073741824 0,le,unknown",
        "-1073741824 -1073741824 0,le,valid",
        "-1073741824 -1073741825 0,ge,unknown",
        "1000000000 1000000000 -2000000000,eq,valid",
        "1500000000 1500000000 -1500000000,ge,unknown",
        "-1500000000 -1500000000 1500000000,le,unknown",
        "1000000000 1000000000 0,le,invalid",
        "-1000000000 -1000000000 0,ge,invalid",
    )
    fun `direct sum credit requires every running total to fit a signed integer`(
        values: String,
        operator: String,
        status: String,
    ) {
        val model = File.createTempFile("xcsp-sum-range-", ".xml")
        try {
            model.writeText(
                """
                <instance format="XCSP3" type="CSP">
                  <variables>
                    <var id="x">-2000000000..2000000000</var>
                    <var id="y">-2000000000..2000000000</var>
                    <var id="z">-2000000000..2000000000</var>
                  </variables>
                  <constraints><sum><list>x y z</list><condition>($operator,0)</condition></sum></constraints>
                </instance>
                """.trimIndent(),
            )
            val witness = "<instantiation><list>x y z</list><values>$values</values></instantiation>"

            val checked = Xcsp3SourceChecker.inspect(model, witness, null)

            assertEquals(status, checked.status)
            if (status == "valid") assertNotNull(checked.checkedWitness) else assertNull(checked.checkedWitness)
        } finally {
            model.delete()
        }
    }

    @ParameterizedTest
    @CsvSource(
        "1500000000 0,2 0,unknown",
        "-1500000000 0,2 0,unknown",
        "-1500000000 0,-2 0,unknown",
        "1500000000 0,c d,unknown",
        "-1500000000 0,c d,unknown",
        "-1073741824 0,2 0,valid",
        "-1073741824 0,-2 0,unknown",
        "1073741823 1,2 1,invalid",
        "1073741823 2,2 1,unknown",
        "1073741824 0,2 0,unknown",
        "1500000000 1500000000,2 -2,unknown",
        "1000000000 1000000000,2 -2,valid",
        "1000000000 1000000000,-2 2,valid",
        "500000000 1000000000,c d,valid",
        "500000000 0,c d,invalid",
        "1073741823 -1,c d,invalid",
        "1073741823 -2,c d,unknown",
        "500000000 500000000,2x2,invalid",
        "500000000 1000000000,2x2,unknown",
        "1500000000 0,2x2,unknown",
    )
    fun `weighted direct sums guard products and totals with constant or variable coefficients`(
        values: String,
        coeffs: String,
        status: String,
    ) {
        val model = File.createTempFile("xcsp-weighted-range-", ".xml")
        try {
            model.writeText(
                """
                <instance format="XCSP3" type="CSP">
                  <variables>
                    <var id="x">-2000000000..2000000000</var><var id="y">-2000000000..2000000000</var>
                    <var id="c">-2..2</var><var id="d">-2..2</var>
                  </variables>
                  <constraints><sum><list>x y</list><coeffs>$coeffs</coeffs><condition>(le,0)</condition></sum></constraints>
                </instance>
                """.trimIndent(),
            )
            val witness = "<instantiation><list>x y c d</list><values>$values 2 -1</values></instantiation>"

            val checked = Xcsp3SourceChecker.inspect(model, witness, null)

            assertEquals(status, checked.status)
            if (status == "valid") assertNotNull(checked.checkedWitness) else assertNull(checked.checkedWitness)
        } finally {
            model.delete()
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = [
        "<sum><list>x[]</list><condition>(le,0)</condition></sum>|1500000000 1500000000|unknown",
        "<sum><list>x[0..1]</list><condition>(le,0)</condition></sum>|1500000000 1500000000|unknown",
        "<sum><list>x[0] x[0]</list><condition>(le,0)</condition></sum>|1500000000 1500000000|unknown",
        "<block><sum><list>x[]</list><condition>(le,0)</condition></sum></block>|1500000000 1500000000|unknown",
        "<group><sum><list>%0 %1</list><condition>(le,0)</condition></sum><args>x[0] x[1]</args></group>" +
            "|1500000000 1500000000|unknown",
        "<group><sum><list>%...</list><condition>(le,0)</condition></sum><args>x[]</args></group>" +
            "|1500000000 1500000000|unknown",
        "<slide><list>x[]</list><sum><list>%0 %1</list><condition>(le,0)</condition></sum></slide>" +
            "|1500000000 1500000000|unknown",
        "<sum><list>x[]</list><condition>(le,0)</condition></sum>|0 0|valid",
        "<sum><list>x[0..1]</list><condition>(le,0)</condition></sum>|0 0|valid",
        "<sum><list>x[0] x[0]</list><condition>(le,0)</condition></sum>|0 0|valid",
        "<block><sum><list>x[]</list><condition>(le,0)</condition></sum></block>|0 0|valid",
        "<group><sum><list>%0 %1</list><condition>(le,0)</condition></sum><args>x[0] x[1]</args></group>|0 0|valid",
        "<group><sum><list>%...</list><condition>(le,0)</condition></sum><args>x[]</args></group>|0 0|valid",
        "<slide><list>x[]</list><sum><list>%0 %1</list><condition>(le,0)</condition></sum></slide>|0 0|valid",
    ])
    fun `expanded direct sums require safe row arithmetic`(constraint: String, values: String, status: String) {
        val model = File.createTempFile("xcsp-expanded-range-", ".xml")
        try {
            model.writeText(
                """
                <instance format="XCSP3" type="CSP">
                  <variables><array id="x" size="[2]">0..2000000000</array></variables>
                  <constraints>$constraint</constraints>
                </instance>
                """.trimIndent(),
            )
            val witness = "<instantiation><list>x[0] x[1]</list><values>$values</values></instantiation>"

            val checked = Xcsp3SourceChecker.inspect(model, witness, null)

            assertEquals(status, checked.status)
            if (status == "valid") {
                assertNotNull(checked.checkedWitness)
            } else {
                assertTrue(checked.reason.contains("direct sum exceeds checker integer range"))
                assertNull(checked.checkedWitness)
            }
        } finally {
            model.delete()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "<sum><list>add(x,0) y</list><condition>(eq,0)</condition></sum>",
        "<sum><list>add(x,0) add(y,0)</list><condition>(eq,0)</condition></sum>",
        "<or><sum><list>x y</list><condition>(eq,0)</condition></sum><intension>eq(x,y)</intension></or>",
    ])
    fun `expression sums and logical meta constraints remain unchecked`(constraint: String) {
        val model = File.createTempFile("xcsp-unsupported-range-", ".xml")
        try {
            model.writeText(
                """
                <instance format="XCSP3" type="CSP">
                  <variables><var id="x">0..1</var><var id="y">0..1</var></variables>
                  <constraints>$constraint</constraints>
                </instance>
                """.trimIndent(),
            )
            val witness = "<instantiation><list>x y</list><values>0 0</values></instantiation>"

            val checked = Xcsp3SourceChecker.inspect(model, witness, null)

            assertEquals("unknown", checked.status)
            assertNull(checked.checkedWitness)
        } finally {
            model.delete()
        }
    }
}
