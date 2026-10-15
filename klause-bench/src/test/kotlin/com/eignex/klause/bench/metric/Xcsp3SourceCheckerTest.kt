package com.eignex.klause.bench.metric

import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.CpSearchComponent
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchConflictResolution
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchLearnedConflictResult
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.solver.search.SearchSession
import java.io.File
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

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

    @Test
    fun `retracted target holes should not refute a checked source witness`() {
        val model = File(
            CorpusFetcher.workspaceRoot(),
            "klause-bench/src/test/resources/xcsp3/reified-hole-tiny.xml",
        )
        val witness = "<instantiation><list>x b c</list><values>1 0 0</values></instantiation>"
        val parsed = Xcsp3.parse(model.readText())
        val targetAbsent = parsed.problem.factors.filterIsInstance<ReifiedLinear>().single {
            it.op == LinearOp.NE && it.vars.contentEquals(intArrayOf(parsed.intVarNames.getValue("x")))
        }.auxBoolVar
        val cp = CpSearchComponent(PropagationSession(parsed.problem))
        cp.rebase()
        val shared = SearchSession(listOf(cp), atoms = SearchAtomRegistry(parsed.problem.numBoolVars))
        assertIs<ComponentResult.Consistent>(shared.initialize())
        assertEquals("valid", Xcsp3SourceChecker.inspect(model, witness, null).status)

        assertIs<ComponentResult.Consistent>(
            shared.push(SearchDecision.IntEqual(parsed.intVarNames.getValue("c"), 1L)),
        )
        val conflict = assertIs<ComponentResult.Conflict>(
            shared.push(SearchDecision.Bool(Lit.make(targetAbsent, false))),
        )
        when (val resolution = cp.resolveConflict(shared)) {
            is SearchConflictResolution.Backjump -> {
                shared.popTo(resolution.conflict.decisionLevel)
                assertTrue(
                    resolution.conflict.apply(shared) != SearchLearnedConflictResult.Exhausted,
                    "A checked source witness rules out root exhaustion",
                )
            }

            SearchConflictResolution.Chronological -> {
                shared.popTo(0)
                shared.learn(conflict.explanation)
            }

            SearchConflictResolution.Exhausted -> fail("A checked source witness rules out root exhaustion")
        }
        shared.popTo(0)

        for ((name, value) in listOf("x" to 1L, "b" to 0L, "c" to 0L)) {
            assertIs<ComponentResult.Consistent>(
                shared.push(SearchDecision.IntEqual(parsed.intVarNames.getValue(name), value)),
            )
        }
        assertIs<SearchResult.Satisfied>(shared.solve(parsed.problem.numBoolVars))
    }

    @ParameterizedTest
    @ValueSource(strings = ["satOptimized", "conflictDriven"])
    fun `source region count witnesses should survive complete search`(preset: String) {
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
    fun `inconsistent source region counts should be refuted by complete search`(preset: String) {
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
    fun `a source valid optimization witness should retain the checked assignment`() {
        val witness = "<instantiation><list>a b c</list><values>2 2 0</values></instantiation>"

        val checked = Xcsp3SourceChecker.inspect(source, witness, "10")

        assertEquals("valid", checked.status)
        assertEquals("xcsp3-source", checked.scope)
        assertEquals("org.xcsp:xcsp3-tools:2.5", checked.checker)
        assertNotNull(checked.checkedWitness)
    }

    @Test
    fun `a source constraint violation should reject a domain valid assignment`() {
        val witness = "<instantiation><list>a b c</list><values>2 2 1</values></instantiation>"

        val checked = Xcsp3SourceChecker.inspect(source, witness, "11")

        assertEquals("invalid", checked.status)
        assertNull(checked.checkedWitness)
    }

    @ParameterizedTest
    @ValueSource(strings = ["9", "10.0", "1/2", "NaN"])
    fun `incorrect and malformed source objectives should be rejected`(objective: String) {
        val witness = "<instantiation><list>a b c</list><values>2 2 0</values></instantiation>"

        assertEquals("invalid", Xcsp3SourceChecker.inspect(source, witness, objective).status)
    }

    @Test
    fun `optimization without a reported objective should remain unchecked`() {
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
    fun `malformed incomplete duplicate unknown and out of domain assignments should be rejected`(witness: String) {
        assertEquals("invalid", Xcsp3SourceChecker.inspect(source, witness, "10").status)
    }

    @Test
    fun `source satisfaction checks should include every expanded array coordinate`() {
        val model = File(source.parentFile, "graph-coloring-tiny.xml")
        val witness = "<instantiation><list>v[0] v[1] v[2] v[3]</list><values>1 2 1 2</values></instantiation>"

        val checked = Xcsp3SourceChecker.inspect(model, witness, null)

        assertEquals("valid", checked.status)
        assertNotNull(checked.checkedWitness)
    }

    @Test
    fun `reified source constraints should remain unchecked`() {
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
    fun `a missing unconstrained source coordinate should not earn witness credit`() {
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
    fun `source objectives should remain exact beyond the signed word range`(objective: String, status: String) {
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
    fun `compact array assignments should remain unchecked until expanded`() {
        val model = File(source.parentFile, "graph-coloring-tiny.xml")
        val witness = "<instantiation><list>v[]</list><values>1 2 1 2</values></instantiation>"

        assertEquals("unknown", Xcsp3SourceChecker.inspect(model, witness, null).status)
    }

    @Test
    fun `an instantiation cost should not disagree with its reported objective`() {
        val witness = "<instantiation cost='9'><list>a b c</list><values>2 2 0</values></instantiation>"

        assertEquals("invalid", Xcsp3SourceChecker.inspect(source, witness, "10").status)
    }
}
