package com.eignex.klause.bench.metric

import com.eignex.klause.bench.source.CorpusFetcher
import java.io.File
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class Xcsp3SourceCheckerTest {
    private val source = File(CorpusFetcher.workspaceRoot(), "klause-bench/smoke-corpus/xcsp3/sum-opt-tiny.xml")

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
}
