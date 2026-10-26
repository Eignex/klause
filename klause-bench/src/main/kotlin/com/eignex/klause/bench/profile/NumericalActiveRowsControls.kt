package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.profile.NumericalFrontierSnapshot.Companion.field
import com.eignex.klause.bench.profile.NumericalFrontierSnapshot.Companion.stats
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.optimization.withLinearAuthority
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.util.Cancellation
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object NumericalActiveRowsControls {
    fun run(out: File) {
        Reports.writeJson(out.resolve("build-provenance.json").path, InstalledBuild.reference)
        val output = out.resolve("active-controls.jsonl")
        output.writeText("")
        for (name in listOf("closed", "strict", "guarded", "inactive", "unresolved", "boolean-term", "integer-pin")) {
            val source = source(name)
            val system = NumericalFrontierSnapshot.system(source)
            val bytes = system.toString().toByteArray()
            out.resolve("$name-source.json").writeBytes(bytes)
            repeat(4) { round ->
                for (active in if (round % 2 == 0) listOf(false, true) else listOf(true, false)) {
                    val stop = Cancellation.after(10.seconds)
                    val started = System.nanoTime()
                    val invocation = NumericalFrontierSnapshot.open(source,
                        TheoryParams(timeout = stop, cancellation = stop, randomSeed = 3L), activeRows = active)
                    val result = try {
                        NumericalFrontierSnapshot.run(invocation) { _, _ -> }
                    } finally {
                        (invocation as AutoCloseable).close()
                    }
                    val elapsed = System.nanoTime() - started
                    val progress = stats(invocation)
                    val terminal = field(result, "terminal") as SourceEvidence?
                    val bound = field(result, "bound") as SourceEvidence?
                    val checkStop = Cancellation.after(10.seconds)
                    val entry = buildJsonObject {
                        put("source", name)
                        put("sourceSha256", sha256(bytes))
                        put("sourceKind", "self-authored-native-exact-affine")
                        put("revision", Reports.readGitSha())
                        put("round", round)
                        put("warmup", round == 0)
                        put("activeRows", active)
                        put("lifecycleNs", elapsed)
                        put("stopped", stop())
                        put("os", System.getProperty("os.name"))
                        put("arch", System.getProperty("os.arch"))
                        put("seed", 3)
                        put("processors", Runtime.getRuntime().availableProcessors())
                        put("nativeTerminal", terminal?.claim?.let { it::class.simpleName })
                        put("value", field(result, "value")?.toString())
                        put("reason", field(result, "reason").toString())
                        put("nativeTerminalCheck", terminal?.let {
                            SourceEvidenceVerifier(source.withLinearAuthority()).verify(it, checkStop).toString()
                        })
                        put("nativeBoundCheck", bound?.let {
                            SourceEvidenceVerifier(source.withLinearAuthority()).verify(it, checkStop).toString()
                        })
                        put("independentProofCheck", false)
                        put("booleanDecisions", progress.openTheory.openBoolDecisions)
                        put("theoryChecks", progress.openTheory.openTheoryChecks)
                        put("sourceLp", buildJsonObject {
                            val work = progress.smt.sourceLp
                            put("operations", work.operations)
                            put("preparationWork", work.preparationWork)
                            put("floatWork", work.floatWork)
                            put("continuationWork", work.continuationWork)
                            put("refinementWork", work.refinementWork)
                            put("boundCertificationWork", work.boundCertificationWork)
                            put("conflictCertificationWork", work.conflictCertificationWork)
                            put("modeledAllocation", work.modeledAllocation)
                        })
                        put("stages", buildJsonObject {
                            progress.smt.completeSearchStages.orEmpty().forEach { (key, value) -> put(key, value) }
                        })
                    }
                    output.appendText(entry.toString() + "\n")
                }
            }
        }
    }

    private fun source(name: String): SourceProofContext {
        val x = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val booleans = (0..2).map { SourceCoordinate(SourceCoordinate.Kind.Boolean, it) }
        val zero = BigFraction.ZERO
        val one = BigFraction.ONE
        val condition = when (name) {
            "guarded" -> SourceCondition(bools = mapOf(0 to true))
            "inactive" -> SourceCondition(bools = mapOf(0 to false))
            else -> SourceCondition()
        }
        val scope = SourceScope(SourceRevision("active-$name-v1", "native-exact-affine-v1"), condition,
            SourceObjective("boolean-cost", ObjectiveSense.MINIMIZE))
        val rows = linkedMapOf("lower" to SourcePremise(SourceInequality(mapOf(x to one.negated()), zero)))
        when (name) {
            "closed", "strict" -> rows["upper"] = SourcePremise(SourceInequality(mapOf(x to one),
                if (name == "strict") zero else one.negated(), strict = name == "strict"))
            "guarded", "inactive", "unresolved" -> rows["upper"] = SourcePremise(
                SourceInequality(mapOf(x to one), one.negated()), mapOf(0 to true))
            "boolean-term" -> rows["upper"] = SourcePremise(
                SourceInequality(mapOf(x to one, booleans[0] to one.negated()), one.negated()))
            "integer-pin" -> {
                rows["integer-lower"] = SourcePremise(SourceInequality(mapOf(integer to one.negated()), one.negated()))
                rows["integer-upper"] = SourcePremise(SourceInequality(mapOf(integer to one), one))
                rows["indicator"] = SourcePremise(SourceInequality(emptyMap(), one.negated()),
                    emptyMap(), mapOf(integer to one))
            }
            else -> error("unknown active row control")
        }
        return SourceProofContext(scope, (booleans + listOf(x, integer)).toSet(), rows,
            mapOf(booleans[2] to BigFraction.ofLong(7)), BigFraction.ofLong(3))
    }
}
