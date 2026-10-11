package com.eignex.klause.bench.metric

import com.eignex.klause.bench.source.CorpusFetcher
import java.io.File
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

    @Test
    fun `incorrect and malformed source objectives are rejected`() {
        val witness = "<instantiation><list>a b c</list><values>2 2 0</values></instantiation>"

        for (objective in listOf("9", "10.0", "1/2", "NaN")) {
            assertEquals("invalid", Xcsp3SourceChecker.inspect(source, witness, objective).status, objective)
        }
    }

    @Test
    fun `optimization without a reported objective remains unchecked`() {
        val witness = "<instantiation><list>a b c</list><values>2 2 0</values></instantiation>"

        assertEquals("unknown", Xcsp3SourceChecker.inspect(source, witness, null).status)
    }

    @Test
    fun `malformed incomplete duplicate unknown and out of domain assignments are rejected`() {
        for (witness in listOf(
            "<instantiation><list>a b c</list><values>2 2 0</values>",
            "<instantiation><list>a b</list><values>2 2</values></instantiation>",
            "<instantiation><list>a a c</list><values>2 2 0</values></instantiation>",
            "<instantiation><list>a b unknown</list><values>2 2 0</values></instantiation>",
            "<instantiation><list>a b c</list><values>3 1 0</values></instantiation>",
            "<instantiation><list>a b c</list><values>2 2 *</values></instantiation>",
            "<instantiation><values>2 2 0</values><list>a b c</list></instantiation>",
        )) {
            assertEquals("invalid", Xcsp3SourceChecker.inspect(source, witness, "10").status, witness)
        }
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
    fun `an instantiation cost cannot disagree with its reported objective`() {
        val witness = "<instantiation cost='9'><list>a b c</list><values>2 2 0</values></instantiation>"

        assertEquals("invalid", Xcsp3SourceChecker.inspect(source, witness, "10").status)
    }
}
