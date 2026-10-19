package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.bench.report.Reports
import java.io.File
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MpsSourceValidationTest {
    @TempDir
    lateinit var directory: File

    @ParameterizedTest
    @CsvSource(
        "mps/blend-tiny,3,1,9,valid",
        "mps/blend-tiny,3,1,8,invalid",
        "mps/blend-tiny,4,0,8,invalid",
        "mps/blend-tiny,0,0,0,invalid",
        "mps/blend-tiny,2.5,1.5,9.5,invalid",
    )
    fun `supplied points should retain only independently accepted feasibility`(
        fixture: String, x: String, y: String, objective: String, expected: String,
    ) {
        val ref = ProblemRef(fixture, Format.MPS, ProblemSource.Vendored("klause-bench/smoke-corpus/$fixture.mps"),
            Category.OPTIMIZATION, Expected.Unknown)
        val raw = "o $objective\nv X1=$x X2=$y\n"
        val record = SolveRecord(fixture, "klause", null, 1, "free", 1, 10_000, "optimize", false,
            true, objective.toDouble(), 1, proven = true, gitSha = "test", timestamp = "test", command = "cli --exact",
            validationPolicy = MPS_SOURCE_POLICY, sourceHashes = SolveEvidence.sourceHashes(ref),
            sourceOutputSha256 = sha256(raw.toByteArray()), finalWitness = "v X1=$x X2=$y", exactObjective = objective)

        val result = MpsSourceValidation.validate(ref, record, raw, true, directory)
        val admitted = record.sourceChecked(result.validation, approximation = false, witnessOnly = true)

        assertEquals(expected, result.validation.status, result.validation.reason)
        assertEquals(if (expected == "valid") true else null, admitted.feasible)
        assertFalse(admitted.proven)
        val receipt = Reports.json.decodeFromString<MpsSourceValidation.Receipt>(
            result.stats.getValue("sourceValidationReceiptJson"))
        assertEquals(0, receipt.solverSearchQueries)
        assertFalse(receipt.proofCertificateChecked)
        assertEquals(1, receipt.processes.size)
        assertFalse(directory.walkTopDown().any { it.name == "solchecker" })
    }

    @ParameterizedTest
    @CsvSource("false,valid,false", "true,invalid,true")
    fun `default MIPLIB tolerance should remain distinct from exact feasibility`(
        exact: Boolean, expected: String, gzip: Boolean,
    ) {
        val fixture = "mps/float-tiny"
        val ref = ProblemRef(fixture, Format.MPS, ProblemSource.Vendored("klause-bench/smoke-corpus/$fixture.mps"),
            Category.OPTIMIZATION, Expected.Unknown)
        val raw = "o 0\nv X1=4.00000001\n"
        val record = SolveRecord(fixture, "klause", null, 1, "free", 1, 10_000, "optimize", false,
            true, 0.0, 1, proven = true, gitSha = "test", timestamp = "test", command = if (exact) "cli --exact" else "cli",
            validationPolicy = if (gzip) MPS_GZIP_SOURCE_POLICY else MPS_SOURCE_POLICY,
            sourceHashes = SolveEvidence.sourceHashes(ref), sourceOutputSha256 = sha256(raw.toByteArray()),
            finalWitness = "v X1=4.00000001", exactObjective = "0")

        val result = if (gzip) MpsSourceValidation.validateGzip(ref, record, raw, exact, directory)
            else MpsSourceValidation.validate(ref, record, raw, exact, directory)

        assertEquals(expected, result.validation.status, result.validation.reason)
        val receipt = Reports.json.decodeFromString<MpsSourceValidation.Receipt>(result.stats.getValue("sourceValidationReceiptJson"))
        assertEquals(false, receipt.exactFeasible)
        if (!exact) assertEquals(true, receipt.toleranceAccepted)
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = ["v X1=3", "v X1=3 Z=1", "v X1=3 X2=1 Z=0"])
    fun `missing or unknown coordinates should remain unverified`(point: String) {
        val ref = ProblemRef("fixture", Format.MPS, ProblemSource.Vendored("klause-bench/smoke-corpus/mps/blend-tiny.mps"),
            Category.OPTIMIZATION, Expected.Unknown)
        val raw = "o 9\nv X1=3 X2=1\n".replace("v X1=3 X2=1", point)
        val record = SolveRecord("fixture", "klause", null, 1, "free", 1, 10_000, "optimize", false,
            true, 9.0, 1, proven = true, gitSha = "test", timestamp = "test", command = "cli --exact",
            validationPolicy = MPS_SOURCE_POLICY, sourceHashes = SolveEvidence.sourceHashes(ref),
            sourceOutputSha256 = sha256(raw.toByteArray()), finalWitness = point, exactObjective = "9")

        val result = MpsSourceValidation.validate(ref, record, raw, true, directory)

        assertEquals("unknown", result.validation.status)
        assertNull(result.validation.checkedWitness)
    }

    @Test
    fun `source hash changes should withhold checked witness`() {
        val ref = ProblemRef("fixture", Format.MPS, ProblemSource.Vendored("klause-bench/smoke-corpus/mps/blend-tiny.mps"),
            Category.OPTIMIZATION, Expected.Unknown)
        val record = SolveRecord("fixture", "klause", null, 1, "free", 1, 10_000, "optimize", false,
            true, 9.0, 1, proven = false, gitSha = "test", timestamp = "test", command = "cli", finalWitness = "v X1=3 X2=1")

        val result = MpsSourceValidation.validate(ref, record, "o 9\nv X1=3 X2=1\n", false, directory)

        assertEquals("unknown", result.validation.status)
        assertNull(result.validation.checkedWitness)
        assertNotNull(result.stats["sourceValidationDirectory"])
    }
}
