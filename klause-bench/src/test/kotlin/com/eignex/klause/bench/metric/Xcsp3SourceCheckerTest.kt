package com.eignex.klause.bench.metric

import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import java.io.File
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.xcsp.parser.callbacks.SolutionChecker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Xcsp3SourceCheckerTest {
    private val source = File(CorpusFetcher.workspaceRoot(), "klause-bench/smoke-corpus/xcsp3/sum-opt-tiny.xml")

    private val regions = File(
        CorpusFetcher.workspaceRoot(),
        "klause-bench/src/test/resources/xcsp3/region-counts-tiny.xml",
    )

    init {
        val witness = "<instantiation><list>a b c</list><values>2 2 0</values></instantiation>"
        Xcsp3SourceChecker.inspect(source, witness, "10")
        val parsed = Xcsp3.parse(regions.readText())
        BacktrackSolver(parsed.problem.bake()).solve(BacktrackPresets.satOptimized(randomSeed = 3L))
    }

    @ParameterizedTest
    @ValueSource(strings = ["satOptimized", "conflictDriven"])
    fun `source region count witnesses survive complete search`(preset: String) {
        val parsed = Xcsp3.parse(regions.readText())
        val params = when (preset) {
            "satOptimized" -> BacktrackPresets.satOptimized(randomSeed = 3L)
            else -> BacktrackPresets.conflictDriven(randomSeed = 3L)
        }

        val result = BacktrackSolver(parsed.problem.bake()).solve(params)

        val sample = assertIs<SolveResult.Sat>(result).assignment
        val names = parsed.intVarNames.keys.joinToString(" ")
        val values = parsed.intVarNames.values.joinToString(" ") { sample.ints[it].toString() }
        val witness = "<instantiation><list>$names</list><values>$values</values></instantiation>"
        val checked = Xcsp3SourceChecker.inspect(regions, witness, null)
        assertEquals("valid", checked.status, checked.reason)
    }

    @ParameterizedTest
    @ValueSource(strings = ["satOptimized", "conflictDriven"])
    fun `inconsistent source region counts are refuted by complete search`(preset: String) {
        val text = regions.readText().replace("<values>0 1 2 1</values>", "<values>0 1 2 2</values>")
        val parsed = Xcsp3.parse(text)
        val params = when (preset) {
            "satOptimized" -> BacktrackPresets.satOptimized(randomSeed = 3L)
            else -> BacktrackPresets.conflictDriven(randomSeed = 3L)
        }

        val result = BacktrackSolver(parsed.problem.bake()).solve(params)

        assertIs<SolveResult.Unsat>(result)
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
    @CsvSource(delimiter = '|', value = [
        "add(x,0) y||1 2|eq,3|valid",
        "add(x,0) add(y,0)||1 2|eq,3|valid",
        "eq(x,1) eq(y,2)|2 3|1 2|ge,5|valid",
        "eq(x,1) eq(y,2)|2 3|0 2|ge,5|invalid",
        "add(x,0) add(y,0)|c d|1 2|eq,0|valid",
        "add(x,0) add(y,0)|c d|2 2|eq,0|invalid",
        "add(x,0) add(y,0)|2x2|1 2|eq,6|valid",
        "add(x,0) y||1 2|eq,c|invalid",
        "add(x,0) y||1 1|eq,c|valid",
        "add(x,0) y||1500000000 1500000000|le,0|unknown",
        "add(x,0) y||-1073741824 -1073741824|ge,-2147483648|valid",
        "add(x,0) y||1073741823 1073741824|le,2147483647|valid",
        "mul(x,x) neg(mul(y,y))||1500000000 1500000000|eq,0|valid",
        "mul(x,x) neg(mul(y,y))|5 5|1500000000 1500000000|eq,0|unknown",
        "mul(x,x) neg(mul(y,y))|c c|2147483637 2147483637|eq,0|valid",
        "mul(x,x) neg(mul(y,y))|c c|2147483637 2147483636|eq,0|unknown",
        "mul(x,x,x) neg(mul(y,y,y))||1500000000 1500000000|eq,0|unknown",
        "sub(mul(x,x,x),mul(x,x,x)) y||1500000000 0|eq,0|unknown",
        "add(9223372036854775807,1,-9223372036854775807) y||0 0|eq,1|unknown",
        "pow(x,2) y||0 0|eq,0|unknown",
        "div(x,y) x||0 0|eq,0|unknown",
    ])
    fun `expression sums require bounded exact terms and a signed integer total`(
        expressions: String,
        coefficients: String?,
        values: String,
        condition: String,
        status: String,
    ) {
        val model = File.createTempFile("xcsp-expression-sum-", ".xml")
        try {
            val coeffs = coefficients?.let { "<coeffs>$it</coeffs>" }.orEmpty()
            model.writeText(
                """
                <instance format="XCSP3" type="CSP">
                  <variables>
                    <var id="x">-2000000000..2147483637</var><var id="y">-2000000000..2147483637</var>
                    <var id="c">2</var><var id="d">-1</var>
                  </variables>
                  <constraints><sum><list>$expressions</list>$coeffs<condition>($condition)</condition></sum></constraints>
                </instance>
                """.trimIndent(),
            )
            val witness = "<instantiation><list>x y c d</list><values>$values 2 -1</values></instantiation>"

            val checked = Xcsp3SourceChecker.inspect(model, witness, null)

            assertEquals(status, checked.status, checked.reason)
            if (status == "valid") assertNotNull(checked.checkedWitness) else assertNull(checked.checkedWitness)
        } finally {
            model.delete()
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = [
        "x y|1 1|8|valid",
        "x y|0 1|5|invalid",
        "x y|1 1|9|invalid",
        "x y|1 1|8.0|invalid",
        "x y|1 1||unknown",
        "x x|1 1|8|invalid",
        "x|1|8|invalid",
        "x y|1 nope|8|invalid",
        "x y|1 2|13|invalid",
    ])
    fun `expression sum admission retains source assignment and objective checks`(
        names: String,
        values: String,
        objective: String?,
        status: String,
    ) {
        val model = File.createTempFile("xcsp-expression-sum-objective-", ".xml")
        try {
            model.writeText(
                """
                <instance format="XCSP3" type="COP">
                  <variables><var id="x">0..1</var><var id="y">0..1</var></variables>
                  <constraints><sum><list>eq(x,1) eq(y,1)</list><condition>(eq,2)</condition></sum></constraints>
                  <objectives><minimize type="sum"><list>x y</list><coeffs>3 5</coeffs></minimize></objectives>
                </instance>
                """.trimIndent(),
            )
            val witness = "<instantiation><list>$names</list><values>$values</values></instantiation>"

            val checked = Xcsp3SourceChecker.inspect(model, witness, objective)

            assertEquals(status, checked.status, checked.reason)
            if (status == "valid") assertNotNull(checked.checkedWitness) else assertNull(checked.checkedWitness)
        } finally {
            model.delete()
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = [
        "<block><sum><list>eq(x[0],1) eq(x[1],1)</list><condition>(eq,2)</condition></sum></block>|valid",
        "<group><sum><list>eq(%0,1) eq(%1,1)</list><condition>(eq,2)</condition></sum>" +
            "<args>x[0] x[1]</args></group>|unknown",
        "<slide><list>x[]</list><sum><list>eq(%0,1) eq(%1,1)</list>" +
            "<condition>(eq,2)</condition></sum></slide>|unknown",
        "<group><sum><list>%0 %1</list><coeffs>%...</coeffs><condition>(eq,5)</condition></sum>" +
            "<args>eq(x[0],1) eq(x[1],1) 2 3</args></group>|valid",
        "<group><sum><list>%0 %1</list><condition>(eq,1)</condition></sum>" +
            "<args>eq(x[0],1) eq(x[1],1)</args></group>|invalid",
    ])
    fun `expanded expression sums check every source row`(constraint: String, status: String) {
        val model = File.createTempFile("xcsp-expanded-expression-", ".xml")
        try {
            model.writeText(
                """
                <instance format="XCSP3" type="CSP">
                  <variables><array id="x" size="[2]">0..1</array></variables>
                  <constraints>$constraint</constraints>
                </instance>
                """.trimIndent(),
            )
            val witness = "<instantiation><list>x[0] x[1]</list><values>1 1</values></instantiation>"

            val checked = Xcsp3SourceChecker.inspect(model, witness, null)

            assertEquals(status, checked.status, checked.reason)
            if (status == "valid") assertNotNull(checked.checkedWitness) else assertNull(checked.checkedWitness)
        } finally {
            model.delete()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "<or><sum><list>x y</list><condition>(eq,0)</condition></sum><intension>eq(x,y)</intension></or>",
    ])
    fun `logical meta constraints remain unchecked`(constraint: String) {
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

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = [
        "<list>a b</list><index>i</index><value>v</value>|0 7|valid",
        "<list>a b</list><index>i</index><value>v</value>|1 9|valid",
        "<list>a b</list><index>i</index><value>v</value>|1 7|invalid",
        "<list>7 9</list><index>i</index><value>v</value>|1 9|valid",
        "<list startIndex=\"1\">a b</list><index>i</index><value>v</value>|1 7|valid",
        "<list>a b</list><index rank=\"first\">i</index><value>v</value>|0 7|unknown",
        "<list>a b</list><index rank=\"last\">i</index><value>v</value>|1 9|unknown",
        "<list>a b</list><index>i</index><value>v</value>|2 9|unknown",
        "<list startIndex=\"-2000000000\">a b</list><index>i</index><value>v</value>|2000000000 9|unknown",
    ])
    fun `vector elements admit only bounded unranked indexing`(children: String, values: String, status: String) {
        val model = File.createTempFile("xcsp-vector-element-", ".xml")
        try {
            model.writeText(
                """
                <instance format="XCSP3" type="CSP">
                  <variables><var id="a">7</var><var id="b">9</var>
                    <var id="i">-2000000000..2000000000</var><var id="v">7..9</var></variables>
                  <constraints><element>$children</element></constraints>
                </instance>
                """.trimIndent(),
            )
            val witness = "<instantiation><list>a b i v</list><values>7 9 $values</values></instantiation>"

            val checked = Xcsp3SourceChecker.inspect(model, witness, null)

            assertEquals(status, checked.status, checked.reason)
            if (status == "valid") assertNotNull(checked.checkedWitness) else assertNull(checked.checkedWitness)
        } finally {
            model.delete()
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = [
        "<cumulative><origins>x y</origins><lengths>1 1</lengths><heights>1500000000 1500000000</heights>" +
            "<condition>(le,1)</condition></cumulative>|0 0|cumulative load",
        "<cumulative><origins>x y</origins><lengths>1500000000 1500000000</lengths><heights>2 2</heights>" +
            "<condition>(le,1)</condition></cumulative>|1500000000 1500000000|task endpoint",
        "<noOverlap><origins>x y</origins><lengths>1500000000 1500000000</lengths></noOverlap>" +
            "|1500000000 1500000000|task endpoint",
        "<binPacking><list>x y</list><sizes>1500000000 1500000000</sizes>" +
            "<condition>(le,1)</condition></binPacking>|0 0|bin-packing load",
        "<intension>lt(mul(x,x,x),0)</intension>|1500000000 0|expression",
        "<ordered><list>x y</list><lengths>1500000000</lengths><operator>le</operator></ordered>" +
            "|1500000000 1500000000|ordered offset",
        "<knapsack><list>x y</list><weights>2 2</weights><condition>(le,0)</condition>" +
            "<profits>0 0</profits><condition>(eq,0)</condition></knapsack>|1500000000 0|weighted term",
    ])
    fun `unchecked non sum overflow cannot earn source witness credit`(
        constraint: String,
        values: String,
        diagnostic: String,
    ) {
        val model = File.createTempFile("xcsp-nonsum-overflow-", ".xml")
        try {
            model.writeText(
                """
                <instance format="XCSP3" type="CSP">
                  <variables><var id="x">0..2000000000</var><var id="y">0..2000000000</var></variables>
                  <constraints>$constraint</constraints>
                </instance>
                """.trimIndent(),
            )
            val witness = "<instantiation><list>x y</list><values>$values</values></instantiation>"

            val upstream = SolutionChecker(false, model.absolutePath, witness.byteInputStream())
            val checked = Xcsp3SourceChecker.inspect(model, witness, null)

            assertTrue(upstream.violatedCtrs.isEmpty())
            assertEquals("unknown", checked.status)
            assertTrue(checked.reason.contains(diagnostic), checked.reason)
            assertNull(checked.checkedWitness)
        } finally {
            model.delete()
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = [
        "<cumulative><origins>x y</origins><lengths>1 1</lengths><heights>1073741823 1073741824</heights>" +
            "<condition>(le,2147483647)</condition></cumulative>|0 0|valid",
        "<cumulative><origins>x y</origins><lengths>1 1</lengths><heights>1500000000 1500000000</heights>" +
            "<condition>(le,1500000000)</condition></cumulative>|0 1|valid",
        "<cumulative><origins>x y</origins><lengths>1 1</lengths><heights>1 1</heights>" +
            "<condition>(le,1)</condition></cumulative>|0 0|invalid",
        "<noOverlap><origins>x y</origins><lengths>1 16</lengths></noOverlap>|2147483630 2147483631|valid",
        "<noOverlap><origins>x y</origins><lengths>1 1</lengths></noOverlap>|1500000000 1500000000|invalid",
        "<binPacking><list>x y</list><sizes>1073741823 1073741824</sizes>" +
            "<condition>(le,2147483647)</condition></binPacking>|0 0|valid",
        "<binPacking><list>x y</list><sizes>1500000000 1500000000</sizes>" +
            "<condition>(le,1500000000)</condition></binPacking>|0 1|valid",
        "<binPacking><list>x y</list><sizes>1 1</sizes><condition>(le,1)</condition></binPacking>|0 0|invalid",
        "<binPacking><list>x y</list><sizes>1500000000 1500000000</sizes>" +
            "<limits>1500000000 1500000000</limits></binPacking>|0 1|valid",
        "<binPacking><list>x y</list><sizes>1500000000 1500000000</sizes>" +
            "<loads>1500000000 1500000000</loads></binPacking>|0 1|valid",
        "<binPacking><list>x y</list><sizes>1 1</sizes><limits>1</limits></binPacking>|0 0|invalid",
        "<group><noOverlap><origins>%0 %1</origins><lengths>1500000000 1500000000</lengths>" +
            "</noOverlap><args>x y</args></group>|1500000000 1500000000|unknown",
        "<noOverlap><origins>(x,y)(x,y)</origins><lengths>(1500000000,1)(1500000000,1)</lengths>" +
            "</noOverlap>|1500000000 0|unknown",
        "<knapsack><list>x y</list><weights>1 -1</weights><condition>(eq,0)</condition>" +
            "<profits>0 0</profits><condition>(eq,0)</condition></knapsack>|1500000000 1500000000|valid",
        "<ordered><list>x y</list><lengths>1</lengths><operator>le</operator></ordered>|2147483636 2147483637|valid",
        "<intension>eq(add(mul(x,x),neg(mul(y,y))),0)</intension>|1500000000 1500000000|valid",
        "<intension>eq(mul(x,x,x),3375000000000000)</intension>|150000 0|valid",
        "<intension>eq(sub(x,y),3000000000)</intension>|1500000000 -1500000000|valid",
        "<intension>eq(dist(x,y),3000000000)</intension>|1500000000 -1500000000|valid",
        "<intension>eq(div(x,2),-1)</intension>|-3 0|valid",
        "<intension>eq(mod(x,2),-1)</intension>|-3 0|valid",
        "<intension>eq(abs(x),1500000000)</intension>|-1500000000 0|valid",
        "<intension>if(eq(x,y),eq(sqr(x),2250000000000000000),eq(x,0))</intension>|1500000000 1500000000|valid",
        "<intension>lt(mul(x,x),0)</intension>|1500000000 0|invalid",
        "<intension>eq(x,add(9223372036854775807,9223372036854775807,2))</intension>|0 0|unknown",
        "<allDifferent>mul(x,x) mul(y,y)</allDifferent>|1500000000 1499999999|valid",
    ])
    fun `safe non sum boundaries cancellation and violations remain checkable`(
        constraint: String,
        values: String,
        status: String,
    ) {
        val model = File.createTempFile("xcsp-nonsum-boundary-", ".xml")
        try {
            model.writeText(
                """
                <instance format="XCSP3" type="CSP">
                  <variables><var id="x">-2000000000..2147483637</var><var id="y">-2000000000..2147483637</var></variables>
                  <constraints>$constraint</constraints>
                </instance>
                """.trimIndent(),
            )
            val witness = "<instantiation><list>x y</list><values>$values</values></instantiation>"

            val checked = Xcsp3SourceChecker.inspect(model, witness, null)

            assertEquals(status, checked.status, checked.reason)
            if (status == "valid") assertNotNull(checked.checkedWitness) else assertNull(checked.checkedWitness)
        } finally {
            model.delete()
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = [
        "mul(x,x,x)|-7250882478129283072|unknown",
        "mul(x,x,x)|3375000000000000000000000000|unknown",
        "mul(x,x)|2250000000000000000|valid",
        "mul(x,x)|2250000000000000001|invalid",
        "sub(mul(x,x,x),mul(x,x,x))|0|valid",
        "add(mul(x,x),neg(mul(x,x)))|0|valid",
        "pow(x,3)|3375000000000000000000000000|unknown",
    ])
    fun `expression objectives require agreement with exact arithmetic`(
        expression: String,
        objective: String,
        status: String,
    ) {
        val model = File.createTempFile("xcsp-expression-objective-", ".xml")
        try {
            model.writeText(
                """
                <instance format="XCSP3" type="COP">
                  <variables><var id="x">0..1500000000</var></variables>
                  <objectives><minimize>$expression</minimize></objectives>
                </instance>
                """.trimIndent(),
            )
            val witness = "<instantiation><list>x</list><values>1500000000</values></instantiation>"

            val checked = Xcsp3SourceChecker.inspect(model, witness, objective)

            assertEquals(status, checked.status, checked.reason)
            if (status == "valid") {
                assertTrue(checked.checkedWitness.orEmpty().contains("cost=\"$objective\""))
            } else {
                assertNull(checked.checkedWitness)
            }
        } finally {
            model.delete()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "<intension>eq(pow(x,3),0)</intension>",
        "<channel><list>x y</list></channel>",
        "<flow><list>x y</list><balance>0 0</balance><arcs>(0,1)(1,0)</arcs></flow>",
    ])
    fun `unaudited arithmetic callbacks explicitly decline witness credit`(constraint: String) {
        val model = File.createTempFile("xcsp-unaudited-arithmetic-", ".xml")
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
            assertTrue(checked.reason.contains("outside checker arithmetic auditing"), checked.reason)
            assertNull(checked.checkedWitness)
        } finally {
            model.delete()
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = [
        "<cumulative><origins>x y</origins><lengths>l l</lengths><heights>h h</heights>" +
            "<condition>(le,1)</condition></cumulative>|0 0 1 1500000000 1|unknown",
        "<cumulative><origins>x y</origins><lengths>l l</lengths><heights>h h</heights>" +
            "<condition>(le,2)</condition></cumulative>|0 0 1 1 1|valid",
        "<cumulative><origins>x y</origins><lengths>l l</lengths><ends>e e</ends><heights>h h</heights>" +
            "<condition>(le,2)</condition></cumulative>|0 0 1 1 2|invalid",
        "<cumulative><origins>x y</origins><lengths>l l</lengths><ends>e e</ends><heights>h h</heights>" +
            "<condition>(le,2)</condition></cumulative>|0 0 1 1 1|valid",
        "<noOverlap><origins>x y</origins><lengths>l l</lengths></noOverlap>" +
            "|1500000000 1500000000 1500000000 1 1|unknown",
        "<noOverlap><origins>x y</origins><lengths>l l</lengths></noOverlap>|0 1 1 1 1|valid",
    ])
    fun `variable scheduling arithmetic includes endpoints loads and explicit ends`(
        constraint: String,
        values: String,
        status: String,
    ) {
        val model = File.createTempFile("xcsp-variable-scheduling-", ".xml")
        try {
            model.writeText(
                """
                <instance format="XCSP3" type="CSP">
                  <variables>
                    <var id="x">0..2000000000</var><var id="y">0..2000000000</var>
                    <var id="l">0..2000000000</var><var id="h">0..2000000000</var><var id="e">0..2000000000</var>
                  </variables>
                  <constraints>$constraint</constraints>
                </instance>
                """.trimIndent(),
            )
            val witness = "<instantiation><list>x y l h e</list><values>$values</values></instantiation>"

            val checked = Xcsp3SourceChecker.inspect(model, witness, null)

            assertEquals(status, checked.status, checked.reason)
            if (status == "valid") assertNotNull(checked.checkedWitness) else assertNull(checked.checkedWitness)
        } finally {
            model.delete()
        }
    }

    @Test
    fun `the pinned checker accepts the wrapped expression objective`() {
        val model = File.createTempFile("xcsp-wrapped-objective-", ".xml")
        try {
            model.writeText(
                """
                <instance format="XCSP3" type="COP">
                  <variables><var id="x">0..1500000000</var></variables>
                  <objectives><minimize>mul(x,x,x)</minimize></objectives>
                </instance>
                """.trimIndent(),
            )
            val objective = "-7250882478129283072"
            val witness = "<instantiation cost='$objective'><list>x</list><values>1500000000</values></instantiation>"

            val checker = SolutionChecker(false, model.absolutePath, witness.byteInputStream())
            val checked = Xcsp3SourceChecker.inspect(model, witness, objective)

            assertTrue(checker.invalidObjs.isEmpty())
            assertEquals("unknown", checked.status)
            assertNull(checked.checkedWitness)
        } finally {
            model.delete()
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = [
        "sum|5 -5|0|valid",
        "sum|5 -5|1|invalid",
        "product|1 1|5062500000000000000000000000000000000|valid",
        "minimum|5 -5|-11250000000000000000|valid",
        "maximum|5 -5|11250000000000000000|valid",
        "nValues|5 -5|2|valid",
    ])
    fun `expression list objectives retain exact aggregate arithmetic`(
        type: String,
        coefficients: String,
        objective: String,
        status: String,
    ) {
        val model = File.createTempFile("xcsp-expression-aggregate-", ".xml")
        try {
            model.writeText(
                """
                <instance format="XCSP3" type="COP">
                  <variables><var id="x">0..1500000000</var></variables>
                  <objectives><minimize type="$type">
                    <list>mul(x,x) mul(x,x)</list><coeffs>$coefficients</coeffs>
                  </minimize></objectives>
                </instance>
                """.trimIndent(),
            )
            val witness = "<instantiation><list>x</list><values>1500000000</values></instantiation>"

            val checked = Xcsp3SourceChecker.inspect(model, witness, objective)

            assertEquals(status, checked.status, checked.reason)
            if (status == "valid") assertNotNull(checked.checkedWitness) else assertNull(checked.checkedWitness)
        } finally {
            model.delete()
        }
    }
}
