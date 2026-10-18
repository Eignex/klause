package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.corpus
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.formats.flatzinc.UnsupportedFlatZincException
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SolveMetricTest {
    private val ref = ProblemRef("fam/inst", Format.MINIZINC, corpus("absent.mzn"), Category.CSP, Expected.Unknown)

    private fun stats(failure: Throwable) = SolveMetric.loadFailureRecord(
        ref,
        SolverInvocation.KLAUSE,
        SolverInvocation.Settings(),
        Budget(1000),
        "now",
        null,
        failure,
    ).stats

    @Test
    fun `exact and default runs should use different result and cache names`() {
        val settings = SolverInvocation.Settings()
        val budget = Budget(1000)

        val default = SolveMetric.configTag(SolverInvocation.KLAUSE, settings, budget)
        val exact = SolveMetric.configTag(SolverInvocation.KLAUSE, settings.copy(exact = true), budget)

        assertNotEquals(default, exact)
    }

    @Test
    fun `a declined model should be recorded as unsupported with its reason`() {
        val failure = IllegalStateException(
            "load failed",
            UnsupportedFlatZincException("unbounded `float` not supported", 3, 1),
        )

        assertEquals(setOf("unsupported"), stats(failure).keys)
    }

    @Test
    fun `a model that does not compile should be recorded as a load error`() {
        val failure = IllegalArgumentException("minizinc compile failed (exit 1) for x.mzn: Error: include error\nmore")

        assertEquals(
            mapOf("loadError" to "minizinc compile failed (exit 1) for x.mzn: Error: include error / more"),
            stats(failure),
        )
    }

    @Test
    fun `a failure record should retain captured hashes when the source is unavailable`() {
        val hashes = mapOf("model" to "captured-source-identity")

        val record = SolveMetric.loadFailureRecord(
            ref, SolverInvocation.KLAUSE, SolverInvocation.Settings(), Budget(1000), "now", null,
            IllegalStateException("reference failed"), sourceHashes = hashes,
        )

        assertEquals(hashes, record.sourceHashes)
    }

    @Test
    fun `source checking should retain complete subprocess diagnostics`() {
        val mps = ProblemRef("bounded-decimal-constant", Format.MPS,
            corpus("mps-linear-source-v1/bounded-decimal-constant.mps"), Category.OPTIMIZATION, Expected.Unknown)
        val entry = ResolvedProblem(mps, lazy { error("source parsing must stay lazy") })
        val diagnostics = "source preparation: factors 3, ints 1, reals 0\n"

        val (result, _) = SolveMetric.solve(entry, SolverInvocation.KLAUSE,
            SolverInvocation.Settings(params = listOf("source-validation=true")), Budget(),
            UUID.randomUUID().toString(), "now", "fixture", installedBuild = { null },
            invoke = { _, _, _, _, _ ->
                SolverInvocation.Result(null, null, null, proven = false, stats = emptyMap(), rawOutput = "",
                    command = "cli", stderr = diagnostics, stderrComplete = true)
            }, validateMps = { _, _, _, _, _ ->
                MpsSourceValidation.Result(SourceValidation("unknown", "diagnostic only"))
            })

        assertEquals(diagnostics, result.stderr)
        assertEquals(true, result.stderrComplete)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `MPS checking should select its transport policy without changing native settings`(gzip: Boolean) {
        val mps = ProblemRef("bounded-decimal-constant", Format.MPS,
            corpus("mps-linear-source-v1/bounded-decimal-constant.mps"), Category.OPTIMIZATION, Expected.Unknown)
        val entry = ResolvedProblem(mps, lazy { error("source parsing must stay lazy") })
        val settings = SolverInvocation.Settings(params = listOf("source-validation=true",
            "source-validation-gzip=$gzip", "bt-arm=satOptimized"))
        val raw = "o 1/8\nv I=0 X=1/10\n"
        val policies = mutableListOf<String>()
        var invoked: SolverInvocation.Settings? = null
        val validate: (ProblemRef, SolveRecord, String, Boolean, File) -> MpsSourceValidation.Result =
            { _, producer, output, _, _ ->
                assertEquals(raw, output)
                assertEquals(sha256(raw.toByteArray()), producer.sourceOutputSha256)
                policies += producer.validationPolicy
                MpsSourceValidation.Result(SourceValidation("valid", "checked",
                    checkedWitness = producer.finalWitness, checker = MPS_GROUND_CHECKER))
            }

        val (result, _) = SolveMetric.solve(entry, SolverInvocation.KLAUSE, settings, Budget(),
            UUID.randomUUID().toString(), "now", "fixture", installedBuild = { null },
            invoke = { _, _, native, _, _ ->
                invoked = native
                SolverInvocation.Result(true, 0.125, 1, proven = false, stats = emptyMap(), rawOutput = raw,
                    command = "cli", exactObjective = "1/8")
            }, validateMps = { a, b, c, d, e ->
                check(!gzip)
                validate(a, b, c, d, e)
            }, validateMpsGzip = { a, b, c, d, e ->
                check(gzip)
                validate(a, b, c, d, e)
            })

        val policy = if (gzip) MPS_GZIP_SOURCE_POLICY else MPS_SOURCE_POLICY
        assertEquals(listOf(policy), policies)
        assertEquals(policy, result.validationPolicy)
        assertEquals(settings.copy(params = listOf("bt-arm=satOptimized")), invoked)
        assertEquals(true, result.feasible)
        assertEquals(false, result.proven)
    }
}
