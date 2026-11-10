package com.eignex.klause.bench.lab

import com.eignex.klause.bench.lab.SourceEligibility.Coverage
import com.eignex.klause.bench.lab.SourceEligibility.Exposure
import com.eignex.klause.bench.lab.SourceEligibility.Identity
import com.eignex.klause.bench.lab.SourceEligibility.Manifest
import com.eignex.klause.bench.lab.SourceEligibility.Relation
import com.eignex.klause.bench.lab.SourceEligibility.Status
import com.eignex.klause.bench.lab.SourceEligibility.Verified
import com.eignex.klause.bench.source.CommittedCorpusIndexes
import com.eignex.klause.bench.source.CorpusIndex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceEligibilityTest {
    private fun hash(digit: Char) = digit.toString().repeat(64)

    private val candidate =
        Identity("miplib2017/timtab1CUTS.mps.zst", packedSha256 = hash('1'), plainSha256 = hash('2'))
    private val receipt = Verified(listOf(candidate), "job 1 identity")
    private val covered = listOf(Coverage("miplib2017", "all miplib2017 campaign requests audited"))
    private val cases = """[{"id":"timtab1CUTS","inputs":[{"path":"miplib2017/timtab1CUTS.mps.zst",""" +
        """"root":"CORPUS","role":"model","sha256":"${hash('7')}"}],"attributes":{"suite":"miplib2017",""" +
        """"problem":"timtab1CUTS","format":"MPS","category":"OPTIMIZATION"}}]"""

    private fun exposure(vararg inputs: Identity, outcome: String = "no-witness") =
        Exposure(inputs.toList(), "applicability screen", outcome, "job 2")

    private fun classify(manifest: Manifest, inputs: List<Identity> = listOf(candidate)) =
        SourceEligibility.classify("timtab1CUTS", inputs, manifest)

    @Test
    fun `identical stored bytes under another path are exposed`() {
        val manifest = Manifest(
            coverage = covered,
            exposures = listOf(exposure(Identity("miplib2010/timtab1CUTS.mps.zst", packedSha256 = hash('1')))),
        )

        val report = classify(manifest)

        assertEquals(Status.EXPOSED, report.status)
        assertEquals("stored-bytes", report.exposures.single().via)
    }

    @Test
    fun `a different encoding with the same verified decoded bytes is exposed`() {
        val plainFile = Identity("miplib2017/timtab1CUTS.mps", packedSha256 = hash('2'))
        val manifest =
            Manifest(coverage = covered, identities = listOf(receipt), exposures = listOf(exposure(plainFile)))

        val report = classify(manifest)

        assertEquals(Status.EXPOSED, report.status)
        assertEquals("decoded-identity", report.exposures.single().via)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `only a recorded relation lets a related family expose a source`(recorded: Boolean) {
        val base = Identity("miplib2017/timtab1.mps.zst", packedSha256 = hash('3'), plainSha256 = hash('4'))
        val relations =
            if (recorded) listOf(Relation(listOf(candidate, base), "family", "cuts variant")) else emptyList()
        val manifest = Manifest(
            coverage = covered,
            identities = listOf(receipt),
            exposures = listOf(exposure(base, outcome = "unsuitable")),
            relations = relations,
        )

        val report = classify(manifest)

        assertEquals(if (recorded) Status.EXPOSED else Status.ELIGIBLE, report.status)
    }

    @Test
    fun `an unexposed source outside the declared coverage is unresolved`() {
        val manifest = Manifest(coverage = listOf(Coverage("miplib2010", "audited")), identities = listOf(receipt))

        val report = classify(manifest)

        assertEquals(Status.UNRESOLVED, report.status)
        assertTrue(report.reasons.single().contains("no complete exposure coverage for miplib2017"), report.reasons[0])
    }

    @Test
    fun `a decoded hash declared only by the descriptor is unresolved`() {
        val report = classify(Manifest(coverage = covered))

        assertEquals(Status.UNRESOLVED, report.status)
        assertTrue(report.reasons.single().contains("no verified decoded sha256"), report.reasons[0])
    }

    @Test
    fun `stored bytes bound to two decoded hashes are unresolved`() {
        val conflicting = Verified(listOf(candidate.copy(plainSha256 = hash('9'))), "job 3 identity")
        val report = classify(Manifest(coverage = covered, identities = listOf(receipt, conflicting)))

        assertEquals(Status.UNRESOLVED, report.status)
        assertTrue(report.reasons.single().contains("conflicting decoded identities"), report.reasons[0])
    }

    @Test
    fun `retained rows become exposures for every outcome`() {
        val file = Files.createTempFile("lab-cases-", ".jsonl").toFile()
        try {
            val input = """"inputs":[{"path":"miplib2017/a.mps.zst","root":"CORPUS","role":"model",""" +
                """"sha256":"${hash('1')}"}]"""
            val rows = listOf(
                """{"index":0,"status":"DONE","problem":{"benchCase":{"id":"a",$input}},""" +
                    """"record":{"feasible":null,"proven":false,"sourceHashes":{"model":"${hash('2')}"}}}""",
                """{"index":1,"status":"DONE","problem":{"benchCase":{"id":"a",$input}},""" +
                    """"record":{"feasible":null,"proven":false,"stats":{"unsupported":"x"}}}""",
                """{"index":2,"status":"FAILED","problem":{"benchCase":{"id":"a",$input}}}""",
                """{"index":3,"status":"FAILED","problem":{"collection":"miplib2017"}}""",
            )
            file.writeText(rows.joinToString("\n"))

            val manifest = SourceEligibility.exposures(listOf(file), "screen", "job 4")

            assertEquals(listOf("no-witness", "unsuitable", "failed:FAILED"), manifest.exposures.map { it.outcome })
            assertEquals(hash('2'), manifest.exposures.first().inputs.single().plainSha256)
            assertEquals("miplib2017", manifest.gaps.single().collection)
        } finally {
            file.delete()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["array", "request", "experiment", "job", "encoded-job"])
    fun `every descriptor form yields its frozen cases`(form: String) {
        val request = """{"parameters":{"cases":${JsonPrimitive(cases)}}}"""
        val experiment = """{"bench":{"request":$request}}"""
        val text = when (form) {
            "array" -> cases
            "request" -> request
            "experiment" -> experiment
            "job" -> """{"id":11664,"experiment":$experiment}"""
            else -> """{"id":11664,"experiment":${JsonPrimitive(experiment)}}"""
        }

        val templates = SourceEligibility.descriptor(Json.parseToJsonElement(text))

        assertEquals(listOf("timtab1CUTS"), templates.map { it.id })
    }

    @Test
    fun `descriptor checks name the missing decoded hash and a stored hash the committed index contradicts`() {
        val workspace = Files.createTempDirectory("eligibility-").toFile()
        try {
            val entry = CorpusIndex.Entry("timtab1CUTS.mps.zst", hash('1'), 10)
            CommittedCorpusIndexes.write(CorpusIndex("miplib2017", listOf(entry)), workspace)
            val templates = SourceEligibility.descriptor(Json.parseToJsonElement(cases))

            val report = SourceEligibility.check(templates, null, workspace)

            assertEquals(2, report.descriptorErrors.size, report.descriptorErrors.toString())
            assertTrue(report.descriptorErrors.any { "sourcePlainSha256 is missing" in it })
            assertTrue(report.descriptorErrors.any { "committed index records ${hash('1')}" in it })
            assertEquals(Status.UNRESOLVED, report.cases.single().status)
        } finally {
            workspace.deleteRecursively()
        }
    }
}
