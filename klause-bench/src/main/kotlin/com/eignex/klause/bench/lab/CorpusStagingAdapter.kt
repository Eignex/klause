package com.eignex.klause.bench.lab

import com.eignex.klause.bench.profile.CorpusStagingProfile
import com.eignex.lab.bench.BenchCase
import com.eignex.lab.bench.BenchCli
import com.eignex.lab.bench.BenchContext
import com.eignex.lab.bench.BenchDescriptor
import com.eignex.lab.bench.BenchProtocol
import com.eignex.lab.bench.BenchRequest
import com.eignex.lab.bench.BenchResult
import com.eignex.lab.bench.BenchStatus
import com.eignex.lab.bench.BenchSuite
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun main(args: Array<String>) {
    val protocolOutput = System.out
    System.setOut(System.err)
    configureLabPaths()
    BenchCli.execute(CorpusStagingSuite, args, stdout = protocolOutput)
}

internal object CorpusStagingSuite : BenchSuite {
    override fun describe() = BenchDescriptor("corpus-staging", "Source IO diagnostics without solver execution")

    override fun plan(request: BenchRequest): List<BenchCase> = KlauseLabSuite.plan(
        request.copy(parameters = request.parameters + ("input-only" to "true")),
    ).map { it.copy(attributes = it.attributes + ("recordSchema" to "corpus-staging-v1")) }

    override fun run(case: BenchCase, context: BenchContext): BenchResult {
        val out = context.outputDirectory.toFile().apply { mkdirs() }
        out.resolve("input.json").writeText(BenchProtocol.json.encodeToString(BenchCase.serializer(), case))
        val failure = runCatching {
            LabInputs.verify(case, context)
            val model = case.inputs.single { it.role == "model" }
            CorpusStagingProfile.capture(LabInputs.path(model, context).toFile(), out,
                case.parameters.getValue("timeout-ms").toLong())
        }.exceptionOrNull()
        return BenchResult(case.id, if (failure == null) BenchStatus.SUCCESS else BenchStatus.FAILED,
            artifacts = context.artifacts(),
            details = buildJsonObject {
                put("solverExecuted", false)
                put("throughputQualification", false)
                put("inputDigest", case.attributes["labInputDigest"])
                put("failure", failure?.toString())
            }, message = failure?.toString())
    }
}
