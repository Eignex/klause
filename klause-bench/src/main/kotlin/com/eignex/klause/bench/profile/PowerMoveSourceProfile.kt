package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.SolveEvidence
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.MiniZincCompatibility
import com.eignex.klause.bench.runner.MiniZincRunner
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import java.io.File
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/** Capture an original MiniZinc source, its compiled FlatZinc and its native arithmetic relations. */
object PowerMoveSourceProfile {
    /** Capture one suite problem's compilation; any other arguments run [IncrementalMoveProfile] unchanged. */
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        if ("suite" in options) captureCompilation(options) else IncrementalMoveProfile.main(args)
    }

    private fun captureCompilation(options: Map<String, String>) {
        require(options.keys.all { it in setOf("suite", "problem", "factorKind", "exact") })
        val kind = options["factorKind"] ?: "Power"
        require(kind in setOf("Power", "Abs", "RealAbs", "GridAbs", "GridPower", "GridProduct", "ContinuousProduct"))
        val exact = options["exact"]?.toBooleanStrict() ?: false
        val ref = Catalog.uncapped(options.getValue("suite")).problems.single {
            it.name == options.getValue("problem")
        }
        val out = File(requireNotNull(System.getenv("OUT")))
        val original = CorpusFetcher.resolve(ref.source)
        original.copyTo(out.resolve("source.mzn"), overwrite = true)
        ref.data?.let { CorpusFetcher.resolve(it).copyTo(out.resolve("source.dzn"), overwrite = true) }
        val hashes = SolveEvidence.sourceHashes(ref)
        val compiled = MiniZincRunner().compileFzn(ref, fresh = true)
        compiled.copyTo(out.resolve("compiled.fzn"), overwrite = true)
        val program = parseFlatZinc(compiled.readText(), exactFloats = exact)
        Reports.writeJson(out.resolve("build-provenance.json").path, InstalledBuild.reference)
        Reports.writeJson(out.resolve("compiler.json").path, MiniZincCompatibility.compiler)
        val factors = program.problem.factors
        out.resolve("retention.json").writeText(buildJsonObject {
            put("revision", Reports.readGitSha())
            put("compilerExit", 0)
            put("factorKind", kind)
            put("exactFloats", exact)
            put("solverInvocations", 0)
            put("sourceHashes", Reports.json.encodeToJsonElement(hashes))
            put("factors", buildJsonArray {
                for (factor in factors) add(buildJsonObject {
                    put("type", factor::class.simpleName)
                    if (factor is Power) {
                        put("base", factor.base.toString())
                        put("exponent", factor.exponent)
                        put("result", factor.result.toString())
                    }
                })
            })
        }.toString())
        check(hashes == SolveEvidence.sourceHashes(ref)) { "source changed during compilation" }
        check(factors.any { it::class.simpleName == kind }) { "compiled source retains no native $kind relation" }
        println("Captured original MiniZinc, compiled FlatZinc and native $kind relations")
    }
}
