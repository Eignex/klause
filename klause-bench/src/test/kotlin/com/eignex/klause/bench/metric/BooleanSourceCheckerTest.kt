package com.eignex.klause.bench.metric

import com.eignex.klause.bench.metric.BooleanSourceChecker.Answer
import com.eignex.klause.bench.metric.BooleanSourceChecker.Prover
import com.eignex.klause.bench.metric.BooleanSourceChecker.Query
import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BooleanSourceCheckerTest {
    // Enumerates every point, so proof checks on these small sources need no external solver.
    private val exhaustive = object : Prover {
        override val identity = mapOf("checker" to "exhaustive")

        override fun decide(query: Query): Answer {
            val size = query.source.size
            for (mask in 0L until (1L shl size)) {
                val values = BooleanArray(size + 1) { it > 0 && ((mask shr (it - 1)) and 1L) == 1L }
                val outcome = BooleanSourceChecker.evaluate(query.source, values)
                val cost = outcome.cost ?: BigInteger.ZERO
                if (outcome.violation == null && query.costBelow?.let { cost < it } != false) return Answer.Sat
            }
            return Answer.Unsat
        }
    }

    private fun case(
        format: String = "DIMACS",
        feasible: Boolean? = true,
        proven: Boolean = false,
        witness: String? = "v -1 2",
        exactObjective: String? = null,
        objective: Int? = null,
        coreBound: String? = null,
    ): JsonObject = buildJsonObject {
        put("index", 0)
        put("status", "DONE")
        putJsonObject("problem") { put("format", format) }
        putJsonObject("record") {
            put("feasible", feasible)
            put("proven", proven)
            put("finalWitness", witness)
            put("exactObjective", exactObjective)
            put("objective", objective)
            putJsonObject("sourceHashes") { put("model", "source") }
            if (coreBound != null) putJsonObject("stats") { put("coreGuidedLowerBound", coreBound) }
        }
    }

    private fun check(case: JsonObject, source: String, prover: Prover = exhaustive) =
        BooleanSourceChecker.checkCase(case, source, prover)

    private fun checkAll(source: String, vararg cases: JsonObject) =
        BooleanSourceChecker.checkCases(cases.toList(), exhaustive) { source }

    @Test
    fun `multiline clauses and a SATLIB terminator should preserve the source clauses`() {
        val result = check(case(proven = true), "p cnf 2 2\n1\n2 0\n-1 0\n%\n0\n")

        assertEquals("source satisfaction", result.proof)
    }

    @ParameterizedTest
    @MethodSource("weightedWitnesses")
    fun `weighted source objective should match signed and bitstring witnesses`(source: String, witness: String) {
        val result = check(case("WCNF", witness = witness, exactObjective = "3"), source)

        assertEquals(BigInteger.valueOf(3), result.objective)
        assertEquals("source feasible", result.witness)
    }

    @Test
    fun `numeric objective disagreement should be checked per record`() {
        val first = case("WCNF", objective = 3)
        val second = case("WCNF", objective = 2)

        val result = checkAll("h 1 2 0\n3 1 0\n", first, second)

        assertEquals(BigInteger.valueOf(3), result[0].check?.objective)
        assertNotNull(result[1].error)
    }

    @ParameterizedTest
    @ValueSource(strings = ["v 1 1", "v 1", "v 1 3"])
    fun `duplicate missing and out of range coordinates should be rejected`(witness: String) {
        val result = checkAll("p cnf 2 1\n1 2 0\n", case(witness = witness))

        assertNotNull(result[0].error)
    }

    @Test
    fun `core bound disagreement should be checked per record`() {
        val source = "h -1 -2 0\nh -1 -3 0\nh -2 -3 0\n1 1 0\n1 2 0\n1 3 0\n"
        val first = case("WCNF", witness = "v 000", exactObjective = "3", coreBound = "2")
        val second = case("WCNF", witness = "v 000", exactObjective = "3", coreBound = "3")

        val result = checkAll(source, first, second)

        assertEquals(BigInteger.valueOf(2), result[0].check?.checkedLowerBound)
        assertNull(result[0].check?.proof)
        assertTrue(result[1].error.orEmpty().contains("source counterexample"))
    }

    @Test
    fun `wbo core bound should be checked without an incumbent`() {
        val source = "soft:; [1] +1 x1 >= 1; [1] +1 x2 >= 1; [1] +1 x3 >= 1; +1 x1 +1 x2 +1 x3 <= 1;"

        val result = check(case("OPB", feasible = null, witness = null, coreBound = "2"), source)

        assertEquals(BigInteger.valueOf(2), result.checkedLowerBound)
        assertEquals("none", result.claim)
        assertNull(result.proof)
    }

    @Test
    fun `feasible boolean source should not certify a refutation`() {
        val result = checkAll("p cnf 2 1\n1 2 0\n", case(feasible = false, proven = true, witness = null))

        assertTrue(result[0].error.orEmpty().contains("source counterexample"))
    }

    @Test
    fun `pigeonhole source should be refuted without the prover`() {
        val source = "p cnf 6 9\n1 2 0\n3 4 0\n5 6 0\n-1 -3 0\n-1 -5 0\n-3 -5 0\n-2 -4 0\n-2 -6 0\n-4 -6 0\n"
        val unused = object : Prover {
            override val identity = emptyMap<String, String>()

            override fun decide(query: Query): Answer = error("prover consulted")
        }

        val result = check(case(feasible = false, proven = true, witness = null), source, unused)

        assertEquals("source pigeonhole refutation", result.proof)
    }

    @Test
    fun `suboptimal opb witness should not certify an optimum`() {
        val source = "* #variable= 2 #constraint= 1\nmin: +1 x1 +2 x2;\n+1 ~x1 +1 x2 >= 1;\n"

        val result = checkAll(source, case("OPB", proven = true, witness = "v -x1 x2", exactObjective = "2"))

        assertTrue(result[0].error.orEmpty().contains("source counterexample"))
    }

    @ParameterizedTest
    @MethodSource("satisfactionSources")
    fun `satisfaction sources should not validate a claimed objective`(
        format: String,
        source: String,
        witness: String,
    ) {
        val result = checkAll(source, case(format, witness = witness, exactObjective = "1"))

        assertTrue(result[0].error.orEmpty().contains("source has no objective"))
    }

    @Test
    fun `opb satisfaction should need no optimization claim`() {
        val source = "* #variable= 2 #constraint= 1\n+1 x1 +1 x2 >= 1;\n"

        val result = check(case("OPB", proven = true, witness = "v -x1 x2"), source)

        assertEquals("source satisfaction", result.proof)
    }

    @ParameterizedTest
    @ValueSource(strings = ["v x1 x2", "v -x1 x2", "v x1 -x2", "v -x1 -x2"])
    fun `opb product constraint should require every literal`(witness: String) {
        val result = checkAll("+1 x1 x2 >= 1;", case("OPB", witness = witness))

        assertEquals(witness == "v x1 x2", result[0].error == null)
    }

    @ParameterizedTest
    @ValueSource(strings = ["+2 x1 ~x2 x1 >= 2;", "+4 x1 ~x1 +1 ~x2 >= 1;"])
    fun `opb products should preserve complements and repeated literals`(source: String) {
        val result = check(case("OPB", witness = "v x1 -x2"), source)

        assertEquals("source feasible", result.witness)
    }

    @Test
    fun `opb product optimum should exclude better original objectives`() {
        val source = "min: +3 x1 x2 +1 x1 +2 x2; +1 x1 +1 x2 >= 1;"

        val result = check(case("OPB", proven = true, witness = "v x1 -x2", exactObjective = "1"), source)

        assertEquals(BigInteger.ONE, result.objective)
        assertEquals("strictly better source objective excluded", result.proof)
    }

    @Test
    fun `suboptimal opb product witness should not certify an optimum`() {
        val source = "min: +3 x1 x2 +1 x1 +2 x2; +1 x1 +1 x2 >= 1;"

        val result = checkAll(source, case("OPB", proven = true, witness = "v x1 x2", exactObjective = "6"))

        assertTrue(result[0].error.orEmpty().contains("source counterexample"))
    }

    @Test
    fun `opb product refutation should check the original relations`() {
        val result = check(case("OPB", feasible = false, proven = true, witness = null), "+1 x1 x2 >= 1; +1 x1 <= 0;")

        assertEquals("source refutation", result.proof)
    }

    @Test
    fun `feasible opb product source should not certify a refutation`() {
        val result = checkAll("+1 x1 x2 >= 1;", case("OPB", feasible = false, proven = true, witness = null))

        assertTrue(result[0].error.orEmpty().contains("source counterexample"))
    }

    @Test
    fun `wbo product penalties should preserve the original soft objective`() {
        val source = "soft:; [5] +1 x1 x2 >= 1; [2] +1 ~x1 >= 1; +1 x2 = 1;"

        val result = check(case("OPB", proven = true, witness = "v x1 x2", exactObjective = "2"), source)

        assertEquals(BigInteger.valueOf(2), result.objective)
        assertEquals("strictly better source objective excluded", result.proof)
    }

    @ParameterizedTest
    @ValueSource(strings = ["+1 x1 invalid >= 1;", "+1 >= 1;", "* #variable= 2\n+1 x1 x3 >= 1;"])
    fun `malformed or out of range opb products should be rejected`(source: String) {
        val result = checkAll(source, case("OPB", witness = "v x1 x2"))

        assertNotNull(result[0].error)
    }

    @Test
    fun `utf8 source comments should preserve hashed bytes`() {
        val text = "* x1 → x2\n+1 x1 +1 x2 >= 1;\n"
        val raw = text.toByteArray()
        val root = Files.createTempDirectory("boolean-source").toFile()
        try {
            File(root, "${sha256(raw)}.txt").writeBytes(raw)
            val record = buildJsonObject { putJsonObject("sourceHashes") { put("model", sha256(raw)) } }

            val result = BooleanSourceChecker.source(record, root)

            assertEquals(text, result)
        } finally {
            root.deleteRecursively()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "* #variable= 2 #constraint= 1\n"])
    fun `optional opb header should preserve constraints and objective`(header: String) {
        val source = header + "min: +1 x1 +2 x2;\n+1 x1 +1 x2 >= 1;\n"

        val result = check(case("OPB", witness = "v -x1 x2", exactObjective = "2"), source)

        assertEquals(BigInteger.valueOf(2), result.objective)
        assertEquals("source feasible", result.witness)
    }

    @Test
    fun `declared opb variable range should be enforced`() {
        val source = "* #variable= 1 #constraint= 1\nmin: +2 x2;\n+1 x1 >= 0;\n"

        val result = checkAll(source, case("OPB", witness = "v -x1 x2", exactObjective = "2"))

        assertNotNull(result[0].error)
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "soft: 9; [5] +1 x1 >= 1; [3] +1 ~x1 <= 0; +1 x2 >= 1; | 8",
            "soft:; [5] +1 x1 = 1; [3] +1 ~x1 >= 1; +1 x2 = 1; | 5",
        ],
    )
    fun `wbo objective should charge only violated soft relations`(source: String, objective: String) {
        val result = check(case("OPB", witness = "v -x1 x2", exactObjective = objective), source)

        assertEquals(BigInteger(objective), result.objective)
    }

    @Test
    fun `wbo soft top should be a strict hard cost ceiling`() {
        val source = "soft: 5; [5] +1 x1 >= 1; +1 x2 >= 1;"

        val result = checkAll(source, case("OPB", witness = "v -x1 x2", exactObjective = "5"))

        assertTrue(result[0].error.orEmpty().contains("WBO soft top exceeded"))
    }

    @Test
    fun `wbo proof should exclude better original weighted violations`() {
        val source = "soft: 9; [5] +1 x1 >= 1; [3] +1 x2 >= 1; +1 x1 +1 x2 <= 1;"

        val result = check(case("OPB", proven = true, witness = "v x1 -x2", exactObjective = "3"), source)

        assertEquals("strictly better source objective excluded", result.proof)
    }

    @Test
    fun `suboptimal wbo witness should not certify an optimum`() {
        val source = "soft: 9; [5] +1 x1 >= 1; [3] +1 x2 >= 1; +1 x1 +1 x2 <= 1;"

        val result = checkAll(source, case("OPB", proven = true, witness = "v -x1 x2", exactObjective = "5"))

        assertTrue(result[0].error.orEmpty().contains("source counterexample"))
    }

    @Test
    fun `violated opb product constraint should be rejected`() {
        val source = "* #variable= 2 #constraint= 1\nmin: +2 x2;\n+1 x1 x2 >= 1;\n"

        val result = checkAll(source, case("OPB", witness = "v -x1 x2", exactObjective = "2"))

        assertNotNull(result[0].error)
    }

    @Test
    fun `undecided optimality check should leave the proof unknown`() {
        val undecided = object : Prover {
            override val identity = mapOf("checker" to "undecided")

            override fun decide(query: Query): Answer = Answer.Unknown("timeout")
        }

        val result = check(case("WCNF", proven = true, exactObjective = "3"), "h 1 2 0\n3 1 0\n", undecided)

        assertEquals("unknown", result.proof)
        assertEquals("timeout", result.reason)
    }

    @Test
    fun `undecided floor check should leave the bound check unknown`() {
        val undecided = object : Prover {
            override val identity = mapOf("checker" to "undecided")

            override fun decide(query: Query): Answer = Answer.Unknown("timeout")
        }

        val result = check(case("WCNF", exactObjective = "3", coreBound = "1"), "h 1 2 0\n3 1 0\n", undecided)

        assertEquals("unknown", result.boundCheck)
        assertNull(result.checkedLowerBound)
    }

    @Test
    fun `weighted cnf query should assert hard clauses and bound the soft cost`() {
        val source = BooleanSourceChecker.parseCnf("p wcnf 2 2 10\n10 1 -2 0\n3 1 0\n", weighted = true)
        val query = StringBuilder()

        BooleanSourceQuery.write(Query(source, BigInteger.valueOf(3)), query)

        assertEquals(
            "(set-option :timeout 10000)\n(declare-const x1 Bool)\n(declare-const x2 Bool)\n" +
                "(assert (or x1 (not x2)))\n(assert (< (ite x1 0 3) 3))\n(check-sat)\n",
            query.toString(),
        )
    }

    @Test
    fun `wbo query should keep products negative coefficients and the soft top`() {
        val source = BooleanSourceChecker.parseOpb("soft: 9; [5] +1 x1 ~x2 >= 1; -2 x2 = -2;")
        val query = StringBuilder()

        BooleanSourceQuery.write(Query(source, BigInteger.valueOf(5)), query)

        assertEquals(
            "(set-option :timeout 10000)\n(declare-const x1 Bool)\n(declare-const x2 Bool)\n" +
                "(assert (= (ite x2 (- 2) 0) (- 2)))\n" +
                "(define-fun soft () Int (ite (>= (ite (and x1 (not x2)) 1 0) 1) 0 5))\n" +
                "(assert (< soft 9))\n(assert (< soft 5))\n(check-sat)\n",
            query.toString(),
        )
    }

    @ParameterizedTest
    @CsvSource(
        "unsat, 0, false, unsat",
        "sat, 0, false, sat",
        "unknown, 0, false, unknown",
        "timeout, 0, false, unknown",
        "unsat, 0, true, unknown",
        "unsat, 1, false, unknown",
    )
    fun `z3 run should settle a query only when it ends cleanly`(
        stdout: String,
        exit: Int,
        timedOut: Boolean,
        expected: String,
    ) {
        val memory = SourceToolMemory(SourceToolMemory.LINUX, SourceToolMemory.LIMIT, complete = true, exceeded = false)
        val process = SmtToolProcess(listOf("z3"), exit, 1, timedOut, true, "$stdout\n", "", memory)

        val answer = Z3BooleanProver.interpret(process)

        assertEquals(expected, when (answer) {
            Answer.Sat -> "sat"
            Answer.Unsat -> "unsat"
            is Answer.Unknown -> "unknown"
        })
    }

    @Test
    fun `gzip case files should be read`() {
        val file = Files.createTempFile("boolean-cases", ".json.gz").toFile()
        try {
            GZIPOutputStream(file.outputStream()).use { it.write("[{\"index\": 4}]".toByteArray()) }

            val cases = BooleanSourceChecker.readCases(file)

            assertEquals("4", cases.single()["index"].toString())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `xz case files should be declined`() {
        assertFailsWith<IllegalArgumentException> { BooleanSourceChecker.readCases(File("cases.json.xz")) }
    }

    companion object {
        @JvmStatic
        fun weightedWitnesses(): List<Arguments> = listOf(
            Arguments.of("p wcnf 2 2 10\n10 1 2 0\n3 1 0\n", "v -1 2"),
            Arguments.of("h 1 2 0\n3 1 0\n", "v 01"),
        )

        @JvmStatic
        fun satisfactionSources(): List<Arguments> = listOf(
            Arguments.of("DIMACS", "p cnf 2 1\n1 2 0\n", "v -1 2"),
            Arguments.of("OPB", "* #variable= 2 #constraint= 1\n+1 x1 +1 x2 >= 1;\n", "v -x1 x2"),
        )
    }
}
